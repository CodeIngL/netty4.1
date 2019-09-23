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

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

    protected PoolChunk<T> chunk;
    protected long handle;
    protected T memory;
    protected int offset;
    protected int length;
    int maxLength;
    PoolThreadCache cache;
    ByteBuffer tmpNioBuf;
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    /**
     * buf初始化，buf对象new出来后，需要通过init和对应的内存进行绑定，即完成初始化
     *
     * @param chunk     由那个chunk分配
     * @param nioBuffer
     * @param handle
     * @param offset
     * @param length
     * @param maxLength
     * @param cache
     */
    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    /**
     * 初始化
     *
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param offset
     * @param length
     * @param maxLength
     * @param cache
     */
    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        //来自哪个chunk
        this.chunk = chunk;
        //位于内存
        memory = chunk.memory;
        //临时的buf
        tmpNioBuf = nioBuffer;
        //分配器
        allocator = chunk.arena.parent;
        //缓存
        this.cache = cache;
        //句柄
        this.handle = handle;
        //偏移量
        this.offset = offset;
        //长度
        this.length = length;
        //最大长度
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     * <p>
     * 必须在重用{@link PooledByteBufAllocator}之前调用方法
     */
    final void reuse(int maxCapacity) {
        maxCapacity(maxCapacity);
        resetRefCnt();
        setIndex0(0, 0);
        discardMarks();
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);

        // If the request capacity does not require reallocation, just update the length of the memory.
        // 如果请求容量不需要重新分配，只需更新内存的长度即可。
        if (chunk.unpooled) {
            if (newCapacity == length) {
                return this;
            }
        } else {
            if (newCapacity > length) {//新容量大于length小于最大值，直接返回this就可以
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            } else if (newCapacity < length) {//新容量小于length
                if (newCapacity > maxLength >>> 1) {//新容量大于maxlength/2
                    if (maxLength <= 512) { //小于512
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else { //等于，直接返回this
                return this;
            }
        }

        // Reallocation required.
        // 需要重新分配。
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    protected final void deallocate() {
        if (handle >= 0) { //存在句柄
            final long handle = this.handle;
            this.handle = -1;
            memory = null; //内存置空，
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            recycle(); //重用对象
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    protected final int idx(int index) {
        return offset + index;
    }
}
