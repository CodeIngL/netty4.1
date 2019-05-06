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
package io.netty.buffer;

/**
 * Implementations are responsible to allocate buffers. Implementations of this interface are expected to be
 * thread-safe.
 * <p>
 * 实现负责分配缓冲区。 此接口的实现应该是线程安全的
 */
public interface ByteBufAllocator {

    /**
     * 默认的byteBuf分配类型
     */
    ByteBufAllocator DEFAULT = ByteBufUtil.DEFAULT_ALLOCATOR;

    /**
     * Allocate a {@link ByteBuf}. If it is a direct or heap buffer
     * depends on the actual implementation.
     * <p>
     * 分配{@link ByteBuf}。 是否直接内存或堆内存缓冲区取决于实际的实现。
     */
    ByteBuf buffer();

    /**
     * Allocate a {@link ByteBuf} with the given initial capacity.
     * If it is a direct or heap buffer depends on the actual implementation.
     * <p>
     * 使用给定的初始容量分配{@link ByteBuf}。 是否直接内存或堆内存缓冲区取决于实际的实现。
     */
    ByteBuf buffer(int initialCapacity);

    /**
     * Allocate a {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity. If it is a direct or heap buffer depends on the actual
     * implementation.
     * <p>
     * 使用给定的初始容量和给定的最大容量分配{@link ByteBuf} 。 是否直接内存或堆内存缓冲区取决于实际的实现。
     */
    ByteBuf buffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     * <p>
     * 分配ByteBuf，最好是适合I/O的直接内存缓冲区
     */
    ByteBuf ioBuffer();

    /**
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     * <p>
     * 分配ByteBuf，最好是适合I/O的直接内存缓冲区
     */
    ByteBuf ioBuffer(int initialCapacity);

    /**
     * Allocate a {@link ByteBuf}, preferably a direct buffer which is suitable for I/O.
     * <p>
     * 分配ByteBuf，最好是适合I/O的直接内存缓冲区
     */
    ByteBuf ioBuffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a heap {@link ByteBuf}.
     * <p>
     * 分配堆上{@link ByteBuf}。
     */
    ByteBuf heapBuffer();

    /**
     * Allocate a heap {@link ByteBuf} with the given initial capacity.
     * <p>
     * 使用给定的初始容量分配堆上{@link ByteBuf}。
     */
    ByteBuf heapBuffer(int initialCapacity);

    /**
     * Allocate a heap {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity.
     * <p>
     * 使用给定的初始容量和给定的最大容量分配堆{@link ByteBuf}。
     */
    ByteBuf heapBuffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a direct {@link ByteBuf}.
     * <p>
     * 分配堆外{@link ByteBuf}。
     */
    ByteBuf directBuffer();

    /**
     * Allocate a direct {@link ByteBuf} with the given initial capacity.
     * <p>
     * 使用给定的初始容量分配堆外{@link ByteBuf}。
     */
    ByteBuf directBuffer(int initialCapacity);

    /**
     * Allocate a direct {@link ByteBuf} with the given initial capacity and the given
     * maximal capacity.
     * <p>
     * 使用给定的初始容量和给定的最大容量分配堆{@link ByteBuf}。
     */
    ByteBuf directBuffer(int initialCapacity, int maxCapacity);

    /**
     * Allocate a {@link CompositeByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation.
     * <p>
     * 分配{@link CompositeByteBuf}。 是否直接内存或堆内存缓冲区取决于实际的实现。
     */
    CompositeByteBuf compositeBuffer();

    /**
     * Allocate a {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     * If it is a direct or heap buffer depends on the actual implementation.
     * <p>
     * 使用给定的可存储在其中的最大components数分配{@link CompositeByteBuf}。 是否直接内存或堆内存缓冲区取决于实际的实现。
     * </p>
     */
    CompositeByteBuf compositeBuffer(int maxNumComponents);

    /**
     * Allocate a heap {@link CompositeByteBuf}.
     * <p>
     * 分配堆上{@link CompositeByteBuf}。
     */
    CompositeByteBuf compositeHeapBuffer();

    /**
     * Allocate a heap {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     * <p>
     * 使用给定的可存储在其中的最大components数分配堆{@link CompositeByteBuf}。
     */
    CompositeByteBuf compositeHeapBuffer(int maxNumComponents);

    /**
     * Allocate a direct {@link CompositeByteBuf}.
     * <p>
     * 分配堆外{@link CompositeByteBuf}。
     */
    CompositeByteBuf compositeDirectBuffer();

    /**
     * Allocate a direct {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     * <p>
     * 使用给定的可存储在其中的最大components数分配堆外{@link CompositeByteBuf}。
     */
    CompositeByteBuf compositeDirectBuffer(int maxNumComponents);

    /**
     * Returns {@code true} if direct {@link ByteBuf}'s are pooled
     * <p>
     * 如果堆外{@link ByteBuf}被池化，则返回{@code true}
     * </p>
     */
    boolean isDirectBufferPooled();

    /**
     * Calculate the new capacity of a {@link ByteBuf} that is used when a {@link ByteBuf} needs to expand by the
     * {@code minNewCapacity} with {@code maxCapacity} as upper-bound.
     * <p>
     * 计算{@link ByteBuf} 的新容量，当{@link ByteBuf}需要通过{@code minNewCapacity}以{@code maxCapacity}作为上限进行扩展时使用
     */
    int calculateNewCapacity(int minNewCapacity, int maxCapacity);
}
