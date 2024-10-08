/*
 * Copyright (c) 2012, 2024 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.memcached;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.memcached.pool.ObjectPool;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@link org.glassfish.grizzly.filterchain.Filter} implementation for memcached
 * <p>
 * This filter has an unbounded {@link BlockingQueue} per a connection for storing user's request.
 * When the response will be received, the corresponding request will be removed in queue
 * and the filter will pass the complete result to original request and notify the waiting sender.
 * <p>
 * If the memcached's command is a kind of quiet's, it is possible for the server not to send the response to client.
 * Then the filter skips the quiet request with {@link ParsingStatus#NO_REPLY} status and processes next request.
 * <p>
 * This filter has two options.
 * 1) {@code localParsingOptimizing}:
 * the input buffer has more than 1 complete memcached message,
 * the filter will parse the input buffer continuously in the same thread and local loop
 * without going through filter chains and spliting up the input buffer if this flag is true.
 * <p>
 * 2) {@code onceAllocationOptimizing}:
 * Before multi-command(bulk-command) like getMulti and setMulti will be sent to the server, individual packets should be allocated.
 * If this flag is true, the filter will calculate the total buffer size of individual requests in advance
 * and will allocate only a {@link Buffer} once.
 *
 * @author Bongjae Chang
 */
public class MemcachedClientFilter extends BaseFilter {

    private static final Logger logger = Grizzly.logger(MemcachedClientFilter.class);

    private static final int MAX_WRITE_BUFFER_SIZE_FOR_OPTIMIZING = 1024 * 1024; // 1m

    private static final int HEADER_LENGTH = 24;
    private static final byte REQUEST_MAGIC_NUMBER = (byte) (0x80 & 0xFF);
    private static final byte RESPONSE_MAGIC_NUMBER = (byte) (0x81 & 0xFF);

    public enum ParsingStatus {
        NONE, READ_HEADER, READ_EXTRAS, READ_KEY, READ_VALUE, DONE, NO_REPLY
    }

    private final Attribute<ParsingStatus> statusAttribute = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("MemcachedClientFilter.Status");
    private final Attribute<MemcachedResponse> responseAttribute =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("MemcachedClientFilter.Response",
                                                              new Supplier<MemcachedResponse>() {
                                                                  @Override
                                                                  public MemcachedResponse get() {
                                                                      return MemcachedResponse.create();
                                                                  }
                                                              });

    private final Attribute<BlockingQueue<MemcachedRequest>> requestQueueAttribute =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.<BlockingQueue<MemcachedRequest>>createAttribute(
                    "MemcachedClientFilter.RequestQueue", new Supplier<BlockingQueue<MemcachedRequest>>() {
                        @Override
                        public BlockingQueue<MemcachedRequest> get() {
                            return new LinkedTransferQueue<>();
                        }
                    });

    private final Attribute<ObjectPool<SocketAddress, Connection<SocketAddress>>> connectionPoolAttribute =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(GrizzlyMemcachedCache.CONNECTION_POOL_ATTRIBUTE_NAME);

    private final boolean localParsingOptimizing;
    private final boolean onceAllocationOptimizing;

    public MemcachedClientFilter() {
        this(false, true);
    }

    public MemcachedClientFilter(final boolean localParsingOptimizing, final boolean onceAllocationOptimizing) {
        this.localParsingOptimizing = localParsingOptimizing;
        this.onceAllocationOptimizing = onceAllocationOptimizing;
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        final Buffer input = ctx.getMessage();
        if (input == null) {
            throw new IOException("input message could not be null");
        }
        if (!input.hasRemaining()) {
            return ctx.getStopAction();
        }
        final Connection connection = ctx.getConnection();
        if (connection == null) {
            throw new IOException("connection could not be null");
        }
        MemoryManager memoryManager = ctx.getMemoryManager();
        if (memoryManager == null) {
            memoryManager = MemoryManager.DEFAULT_MEMORY_MANAGER;
        }

        ParsingStatus status = statusAttribute.get(connection);
        if (status == null) {
            status = ParsingStatus.NONE;
            statusAttribute.set(connection, status);
        }

        final BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
        if (requestQueue == null) {
            throw new IOException("request queue must not be null");
        }

        short keyLength;
        byte extraLength;
        int totalBodyLength;
        int valueLength;
        MemcachedRequest sentRequest;
        MemcachedResponse response = responseAttribute.get(connection);
        while (true) {
            switch (status) {
                case NONE:
                    if (input.remaining() < HEADER_LENGTH) {
                        return ctx.getStopAction(input);
                    }

                    status = ParsingStatus.READ_HEADER;
                    statusAttribute.set(connection, status);
                    break;
                case READ_HEADER:
                    /*
                      |0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|
                      +---------------+---------------+---------------+---------------+
                     0| Magic         | Opcode        | Key Length                    |
                      +---------------+---------------+---------------+---------------+
                     4| Extras length | Data type     | Status                        |
                      +---------------+---------------+---------------+---------------+
                     8| Total body length                                             |
                      +---------------+---------------+---------------+---------------+
                    12| Opaque                                                        |
                      +---------------+---------------+---------------+---------------+
                    16| CAS                                                           |
                      |                                                               |
                      +---------------+---------------+---------------+---------------+
                      Total 24 bytes
                    */
                    input.mark(); // for processing the request again if there is no-reply

                    final byte magic = input.get();
                    if (magic != RESPONSE_MAGIC_NUMBER) {
                        throw new IOException("invalid magic");
                    }
                    final byte op = input.get();
                    sentRequest = requestQueue.peek();
                    if (sentRequest == null) {
                        throw new IOException("invalid response");
                    }
                    final CommandOpcodes commandOpcode = sentRequest.getOp();
                    response.setOp(commandOpcode);
                    if (op != commandOpcode.opcode()) {
                        if (sentRequest.isNoReply()) {
                            status = ParsingStatus.NO_REPLY;
                            statusAttribute.set(connection, status);
                            break;
                        } else {
                            throw new IOException("invalid op: " + op);
                        }
                    }
                    keyLength = input.getShort();
                    if (keyLength < 0) {
                        throw new IOException("invalid key length: " + keyLength);
                    }
                    response.setKeyLength(keyLength);
                    extraLength = input.get();
                    if (extraLength < 0) {
                        throw new IOException("invalid extra length: " + extraLength);
                    }
                    response.setExtraLength(extraLength);
                    response.setDataType(input.get());
                    response.setStatus(ResponseStatus.getResponseStatus(input.getShort()));
                    totalBodyLength = input.getInt();
                    if (totalBodyLength < 0) {
                        throw new IOException("invalid total body length: " + totalBodyLength);
                    }
                    response.setTotalBodyLength(totalBodyLength);
                    final int opaque = input.getInt();
                    if (sentRequest.isNoReply() && opaque != sentRequest.getOpaque()) {
                        status = ParsingStatus.NO_REPLY;
                        statusAttribute.set(connection, status);
                        break;
                    } else {
                        response.setOpaque(opaque);
                    }
                    response.setCas(input.getLong());

                    status = ParsingStatus.READ_EXTRAS;
                    statusAttribute.set(connection, status);
                    break;
                case READ_EXTRAS:
                    extraLength = response.getExtraLength();
                    if (input.remaining() < extraLength) {
                        return ctx.getStopAction(input);
                    }
                    if (extraLength == 4) {
                        response.setFlags(input.getInt());
                    } else {
                        input.position(input.position() + extraLength); // skip
                    }

                    status = ParsingStatus.READ_KEY;
                    statusAttribute.set(connection, status);
                    break;
                case READ_KEY:
                    keyLength = response.getKeyLength();
                    if (input.remaining() < keyLength) {
                        return ctx.getStopAction(input);
                    }
                    if (keyLength > 0) {
                        final int currentPosition = input.position();
                        final int limit = currentPosition + keyLength;
                        response.setDecodedKey(input, currentPosition, limit, memoryManager);
                        input.position(limit);
                    } else {
                        response.setDecodedKey(null);
                    }

                    status = ParsingStatus.READ_VALUE;
                    statusAttribute.set(connection, status);
                    break;
                case READ_VALUE:
                    totalBodyLength = response.getTotalBodyLength();
                    keyLength = response.getKeyLength();
                    extraLength = response.getExtraLength();
                    valueLength = totalBodyLength - keyLength - extraLength;
                    if (valueLength < 0) {
                        throw new IOException("invalid length fields: "
                                + "total body length=" + totalBodyLength
                                + ", key length = " + keyLength
                                + ", extra length = " + extraLength);
                    }
                    if (input.remaining() < valueLength) {
                        return ctx.getStopAction(input);
                    }

                    final int currentPosition = input.position();
                    final int limit = currentPosition + valueLength;
                    if (response.getStatus() == ResponseStatus.No_Error) {
                        if (valueLength > 0) {
                            sentRequest = requestQueue.peek();
                            if (sentRequest == null) {
                                throw new IOException("invalid response");
                            }
                            response.setDecodedValue(input, currentPosition, limit, memoryManager);
                            input.position(limit);
                        } else {
                            response.setDecodedValue(null);
                        }
                    } else {
                        response.setDecodedValue(null);
                        input.position(limit);
                    }

                    status = ParsingStatus.DONE;
                    statusAttribute.set(connection, status);
                    break;
                case DONE:
                    final boolean complete = response.complete();
                    if (complete) {
                        sentRequest = requestQueue.remove();
                        response.setResult(sentRequest.getOriginKey(), ParsingStatus.DONE);
                        if (sentRequest.disposed.compareAndSet(false, true)) {
                            sentRequest.response = response.getResult();
                            sentRequest.isError = response.isError();
                            sentRequest.notify.countDown();
                        }
                    } else {
                        sentRequest = requestQueue.peek();
                        response.setResult(sentRequest.getOriginKey(), ParsingStatus.DONE);
                        if (!sentRequest.disposed.get()) {
                            sentRequest.response = response.getResult();
                            sentRequest.isError = response.isError();
                            sentRequest.notify.countDown();
                        }
                    }

                    if (localParsingOptimizing) {
                        if (input.remaining() > 0) {
                            status = ParsingStatus.NONE;
                            statusAttribute.set(connection, status);
                            response.clear();
                            break;
                        } else {
                            input.tryDispose();
                            statusAttribute.remove(connection);
                            responseAttribute.remove(connection);
                            response.recycle();
                            return ctx.getStopAction();
                        }
                    } else {
                        // Check if the input buffer has more than 1 complete memcached message
                        // If yes - split up the first message and the remainder
                        final Buffer remainder = input.remaining() > 0 ? input.split(input.position()) : null;
                        input.tryDispose();
                        statusAttribute.remove(connection);
                        responseAttribute.remove(connection);
                        response.recycle();
                        if (remainder == null) {
                            return ctx.getStopAction();
                        } else {
                            // Instruct FilterChain to store the remainder (if any) and continue execution
                            return ctx.getInvokeAction(remainder);
                        }
                    }
                case NO_REPLY:
                    // processing next internal memcached request
                    sentRequest = requestQueue.remove();
                    response.setResult(sentRequest.getOriginKey(), ParsingStatus.NO_REPLY);
                    sentRequest.response = response.getResult();
                    sentRequest.isError = Boolean.FALSE;
                    sentRequest.notify.countDown();
                    input.reset();

                    status = ParsingStatus.READ_HEADER;
                    statusAttribute.set(connection, status);
                    response.clear();
                    break;
                default:
                    throw new IllegalStateException("invalid internal status");
            }
        }
    }

    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {
        final MemcachedRequest[] requests = ctx.getMessage();
        if (requests == null) {
            throw new IOException("Input message could not be null");
        }
        final Connection connection = ctx.getConnection();
        if (connection == null) {
            throw new IOException("connection must not be null. this connection was already closed or not opened");
        }

        final BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
        if (requestQueue == null) {
            throw new IOException("request queue must not be null. this connection was already closed or not opened. connection=" + connection);
        }
        MemoryManager memoryManager = ctx.getMemoryManager();
        if (memoryManager == null) {
            memoryManager = MemoryManager.DEFAULT_MEMORY_MANAGER;
        }

        final Buffer resultBuffer;

        if (onceAllocationOptimizing) {
            final int totalSize = calculateTotalPacketSize(requests);
            if (totalSize <= MAX_WRITE_BUFFER_SIZE_FOR_OPTIMIZING) {
                resultBuffer = makePacketsByOnceAllocation(memoryManager, connection, requests, requestQueue, totalSize);
            } else {
                resultBuffer = makePackets(memoryManager, connection, requests, requestQueue);
            }
        } else {
            resultBuffer = makePackets(memoryManager, connection, requests, requestQueue);
        }
        if (resultBuffer != null) {
            resultBuffer.allowBufferDispose(true);
            if (resultBuffer.isComposite()) {
                ((CompositeBuffer) resultBuffer).allowInternalBuffersDispose(true);
            }
            ctx.setMessage(resultBuffer);
        }
        return ctx.getInvokeAction();
    }

    private int calculateTotalPacketSize(final MemcachedRequest[] requests) {
        if (requests == null) {
            return 0;
        }
        int totalSize = requests.length * HEADER_LENGTH;
        for (MemcachedRequest request : requests) {
            totalSize += request.getExtrasLength();
            totalSize += request.getKeyLength();
            totalSize += request.getValueLength();
        }
        return totalSize;
    }

    private Buffer makePacketsByOnceAllocation(final MemoryManager memoryManager,
                                               final Connection connection,
                                               final MemcachedRequest[] requests,
                                               final BlockingQueue<MemcachedRequest> requestQueue,
                                               final int totalSize) throws IOException {
        if (memoryManager == null) {
            throw new IllegalArgumentException("memory manager must not be null");
        }
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        if (requestQueue == null) {
            throw new IllegalArgumentException("request queue must not be null");
        }
        if (totalSize < HEADER_LENGTH) {
            throw new IllegalArgumentException("invalid packet size");
        }

        final Buffer buffer = memoryManager.allocate(totalSize);
        for (MemcachedRequest request : requests) {
            // header
            final byte extrasLength = request.getExtrasLength();
            buffer.put(REQUEST_MAGIC_NUMBER);
            buffer.put(request.getOp().opcode());
            final short keyLength = request.getKeyLength();
            buffer.putShort(keyLength);
            buffer.put(extrasLength);
            buffer.put(request.getDataType());
            buffer.putShort(request.getvBucketId());
            final int totalLength = keyLength + request.getValueLength() + extrasLength;
            buffer.putInt(totalLength);
            buffer.putInt(request.getOpaque());
            buffer.putLong(request.getCas());

            // extras
            request.fillExtras(buffer);

            // key
            final Buffer keyBuffer = request.getKey();
            if (request.hasKey() && keyBuffer != null) {
                buffer.put(keyBuffer);
                keyBuffer.tryDispose();
            }

            // value
            final Buffer valueBuffer = request.getValue();
            if (request.hasValue() && valueBuffer != null) {
                buffer.put(valueBuffer);
                valueBuffer.tryDispose();
            }
            // store request
            try {
                requestQueue.put(request);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("failed to put the request", ie);
            }
        }
        buffer.flip();
        return buffer;
    }

    private Buffer makePackets(final MemoryManager memoryManager,
                               final Connection connection,
                               final MemcachedRequest[] requests,
                               final BlockingQueue<MemcachedRequest> requestQueue) throws IOException {
        if (memoryManager == null) {
            throw new IllegalArgumentException("memory manager must not be null");
        }
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        if (requestQueue == null) {
            throw new IllegalArgumentException("request queue must not be null");
        }
        Buffer resultBuffer = null;
        for (MemcachedRequest request : requests) {
            // header
            final byte extrasLength = request.getExtrasLength();
            final Buffer buffer = memoryManager.allocate(HEADER_LENGTH + extrasLength);
            buffer.put(REQUEST_MAGIC_NUMBER);
            buffer.put(request.getOp().opcode());

            final short keyLength = request.getKeyLength();
            buffer.putShort(keyLength);
            buffer.put(extrasLength);
            buffer.put(request.getDataType());
            buffer.putShort(request.getvBucketId());
            final int totalLength = keyLength + request.getValueLength() + extrasLength;
            buffer.putInt(totalLength);
            buffer.putInt(request.getOpaque());
            buffer.putLong(request.getCas());

            // extras
            request.fillExtras(buffer);

            buffer.flip();
            buffer.allowBufferDispose(true);
            if (resultBuffer == null) {
                resultBuffer = buffer;
            } else {
                resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer, buffer);
            }

            // key
            final Buffer keyBuffer = request.getKey();
            if (request.hasKey() && keyBuffer != null) {
                keyBuffer.allowBufferDispose(true);
                resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer, keyBuffer);
            }

            // value
            final Buffer valueBuffer = request.getValue();
            if (request.hasValue() && valueBuffer != null) {
                valueBuffer.allowBufferDispose(true);
                resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer, valueBuffer);
            }

            // store request
            try {
                requestQueue.put(request);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("failed to put the request", ie);
            }
        }
        return resultBuffer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        final Connection connection = ctx.getConnection();
        if (connection != null) {
            final BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
            if (requestQueue != null) {
                requestQueue.clear();
                requestQueueAttribute.remove(connection);
            }
            responseAttribute.remove(connection);
            statusAttribute.remove(connection);

            final ObjectPool connectionPool = connectionPoolAttribute.remove(connection);
            if (connectionPool != null) {
                try {
                    connectionPool.removeObject(connection.getPeerAddress(), connection);
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, "the connection has been removed in pool. connection={0}", connection);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return ctx.getInvokeAction();
    }

    @SuppressWarnings("unchecked")
    public <K, V> Map<K, V> getMultiResponse(final Connection connection,
                                             final MemcachedRequest[] requests,
                                             final long timeoutInMillis,
                                             final Map<K, V> result) throws InterruptedException, TimeoutException {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        final int requestLen = requests.length;
        if (requestLen < 1) {
            throw new IllegalArgumentException("requests must include at least one request");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }

        Object response;
        Boolean isError;
        final int lastIndex = requestLen - 1;
        // wait for receiving last packet
        if (timeoutInMillis < 0) {
            requests[lastIndex].notify.await();
            response = requests[lastIndex].response;
            isError = requests[lastIndex].isError;
        } else {
            requests[lastIndex].notify.await(timeoutInMillis, TimeUnit.MILLISECONDS);
            response = requests[lastIndex].response;
            isError = requests[lastIndex].isError;
        }
        if (response == null && isError == null) {
            throw new TimeoutException("timed out while getting the response");
        }
        if (isError != null && !isError) {
            result.put((K) requests[lastIndex].getOriginKey(), (V) response);
        } else {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "error status op={0}, key={1}",
                        new Object[]{requests[lastIndex].getOp(), requests[lastIndex].getOriginKey()});
            }
        }
        // collect previous packets
        for (int i = 0; i < requestLen - 1; i++) {
            response = requests[i].response;
            isError = requests[i].isError;
            if (response != null) {
                if (isError != null && !isError) {
                    result.put((K) requests[i].getOriginKey(), (V) response);
                } else {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, "error status op={0}, key={1}",
                                new Object[]{requests[i].getOp(), requests[i].getOriginKey()});
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public <V> V getCorrelatedResponse(final Connection connection,
                                       final MemcachedRequest request,
                                       final long timeoutInMillis) throws InterruptedException, TimeoutException {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.isNoReply()) {
            throw new IllegalArgumentException("request type is no reply");
        }

        final Object response;
        final Boolean isError;
        if (timeoutInMillis < 0) {
            request.notify.await();
            response = request.response;
            isError = request.isError;
        } else {
            request.notify.await(timeoutInMillis, TimeUnit.MILLISECONDS);
            response = request.response;
            isError = request.isError;
        }

        if (response == null && isError == null) {
            throw new TimeoutException("timed out while getting the response");
        }
        final V result;
        if (isError != null && !isError) {
            result = (V) response;
        } else {
            result = null;
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "error status op={0}, key={1}", new Object[]{request.getOp(), request.getOriginKey()});
            }
        }
        return result;
    }
}
