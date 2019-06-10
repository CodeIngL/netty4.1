/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import io.netty.util.internal.UnstableApi;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Allocates a new receive buffer whose capacity is probably large enough to read all inbound data and small enough
 * not to waste its space.
 * <p>
 * 分配一个新的接收缓冲区，其容量可能足以读取所有inbound数据，并且足够小，不会浪费其空间。
 * <p>
 * 接收数据的ByteBuf分配器，他通过配合使用一个处理器来使用一个具有实际分配功能的ByteBuf的分配器进行的ByteBuf的分配
 */
public interface RecvByteBufAllocator {
    /**
     * Creates a new handle.  The handle provides the actual operations and keeps the internal information which is
     * required for predicting an optimal buffer capacity.
     */
    Handle newHandle();

    /**
     * @deprecated Use {@link ExtendedHandle}.
     */
    @Deprecated
    interface Handle {
        /**
         * Creates a new receive buffer whose capacity is probably large enough to read all inbound data and small
         * enough not to waste its space.
         */
        ByteBuf allocate(ByteBufAllocator alloc);

        /**
         * Similar to {@link #allocate(ByteBufAllocator)} except that it does not allocate anything but just tells the
         * capacity.
         * <p>
         *     与{@link #allocate(ByteBufAllocator)}类似，除了它不分配任何东西，只是告诉容量。
         * </p>
         */
        int guess();

        /**
         * Reset any counters that have accumulated and recommend how many messages/bytes should be read for the next
         * read loop.
         * <p>
         * This may be used by {@link #continueReading()} to determine if the read operation should complete.
         * </p>
         * This is only ever a hint and may be ignored by the implementation.
         * <p>
         * 重置已累积的所有计数器，并建议下一个读取循环应读取多少消息/字节。
         * 这可以由{@link #continueReading()}用于确定读操作是否应该完成。
         * 这只是一个提示，可能会被实现忽略
         *
         * @param config The channel configuration which may impact this object's behavior.
         */
        void reset(ChannelConfig config);

        /**
         * Increment the number of messages that have been read for the current read loop.
         * <p>
         *     增加为当前读取循环读取的消息数。
         * </p>
         *
         * @param numMessages The amount to increment by.
         */
        void incMessagesRead(int numMessages);

        /**
         * Set the bytes that have been read for the last read operation.
         * This may be used to increment the number of bytes that have been read.
         * <p>
         * 设置上次读取操作已读取的字节数。 这可用于增加已读取的字节数。
         * </p>
         *
         * @param bytes The number of bytes from the previous read operation. This may be negative if an read error
         *              occurs. If a negative value is seen it is expected to be return on the next call to
         *              {@link #lastBytesRead()}. A negative value will signal a termination condition enforced externally
         *              to this class and is not required to be enforced in {@link #continueReading()}.
         *              <p>
         *              bytes  - 上一次读操作的字节数。 如果发生读取错误，这可能是负数的。
         *              如果看到负值，则预计在下次调用lastBytesRead（）时返回。
         *              负值将表示在此类外部强制执行的终止条件，并且不需要在continueReading()中强制执行
         *              </p>
         */
        void lastBytesRead(int bytes);

        /**
         * Get the amount of bytes for the previous read operation.
         *
         * @return The amount of bytes for the previous read operation.
         */
        int lastBytesRead();

        /**
         * Set how many bytes the read operation will (or did) attempt to read.
         *
         * @param bytes How many bytes the read operation will (or did) attempt to read.
         */
        void attemptedBytesRead(int bytes);

        /**
         * Get how many bytes the read operation will (or did) attempt to read.
         *
         * @return How many bytes the read operation will (or did) attempt to read.
         */
        int attemptedBytesRead();

        /**
         * Determine if the current read loop should continue.
         * <p>
         * 确定当前的读取循环是否应该继续
         *
         * @return {@code true} if the read loop should continue reading. {@code false} if the read loop is complete.
         */
        boolean continueReading();

        /**
         * The read has completed.
         */
        void readComplete();
    }

    @SuppressWarnings("deprecation")
    @UnstableApi
    interface ExtendedHandle extends Handle {
        /**
         * Same as {@link Handle#continueReading()} except "more data" is determined by the supplier parameter.
         *
         * @param maybeMoreDataSupplier A supplier that determines if there maybe more data to read.
         */
        boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier);
    }

    /**
     * A {@link Handle} which delegates all call to some other {@link Handle}.
     */
    class DelegatingHandle implements Handle {
        private final Handle delegate;

        public DelegatingHandle(Handle delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
        }

        /**
         * Get the {@link Handle} which all methods will be delegated to.
         *
         * @return the {@link Handle} which all methods will be delegated to.
         */
        protected final Handle delegate() {
            return delegate;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return delegate.allocate(alloc);
        }

        @Override
        public int guess() {
            return delegate.guess();
        }

        @Override
        public void reset(ChannelConfig config) {
            delegate.reset(config);
        }

        @Override
        public void incMessagesRead(int numMessages) {
            delegate.incMessagesRead(numMessages);
        }

        @Override
        public void lastBytesRead(int bytes) {
            delegate.lastBytesRead(bytes);
        }

        @Override
        public int lastBytesRead() {
            return delegate.lastBytesRead();
        }

        @Override
        public boolean continueReading() {
            return delegate.continueReading();
        }

        @Override
        public int attemptedBytesRead() {
            return delegate.attemptedBytesRead();
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            delegate.attemptedBytesRead(bytes);
        }

        @Override
        public void readComplete() {
            delegate.readComplete();
        }
    }
}
