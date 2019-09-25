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

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

import java.nio.ByteBuffer;

final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    //所属的arena
    private final PoolArena<T> arena;
    //下一个chunkList
    private final PoolChunkList<T> nextList;
    //最小使用量
    private final int minUsage;
    //最大使用量
    private final int maxUsage;
    //最大容量
    private final int maxCapacity;
    //连边
    private PoolChunk<T> head;

    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    // 在PoolArena构造函数中创建PoolChunkList的链接列表时，这只会更新一次
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     * <p>
     * 使用给定的{@code minUsage}和{@code maxUsage}设置计算可以从属于{@link PoolChunkList} 的 {@link PoolChunk}中分配的缓冲区的最大容量。
     * </p>
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            // 如果minUsage为100，我们无法从此列表中分配任何内容。
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        // 计算可从此PoolChunkList中的PoolChunk分配的最大字节数。
        // 
        // 举个例子：
        // - 如果PoolChunkList的minUsage==25，我们可以分配最多75％的chunkSize，因为这是此PoolChunkList中任何PoolChunk中可用的最大数量
        return (int) (chunkSize * (100L - minUsage) / 100L);
    }

    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }


    /**
     * 使用chunkList尝试为我们的buf进行内存的分配
     *
     * @param buf
     * @param reqCapacity
     * @param normCapacity
     * @return
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 此PoolChunkList为空，或者请求的容量大于此PoolChunkList中包含的PoolChunks可以处理的容量。
        if (normCapacity > maxCapacity) {
            return false;
        }

        //从头遍历到尾
        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            // 使用其中chunk进行分配
            if (cur.allocate(buf, reqCapacity, normCapacity)) {
                //分配成功，如果chunk的内存使用率已经大于该chunkList设定值，我们从当前规格的list中，尝试删除他，并将移动到下一个我们设定的规格列表中。
                if (cur.usage() >= maxUsage) {
                    //删除当前规格中的cur
                    remove(cur);
                    //移动到下一个规格中
                    nextList.add(cur);
                }
                return true;
            }
        }
        //PoolChunkList为空直接返回
        return false;
    }

    /**
     * list中释放chunk对应的句柄对应的缓存块
     *
     * @param chunk
     * @param handle
     * @param nioBuffer
     * @return
     */
    boolean free(PoolChunk<T> chunk, long handle, ByteBuffer nioBuffer) {
        chunk.free(handle, nioBuffer);
        if (chunk.usage() < minUsage) {
            //使用量小于最小的使用量，让我们移动他
            remove(chunk);
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        return true;
    }

    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.usage() < minUsage) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            // 之前没有PoolChunkList，因此返回false会导致PoolChunk被破坏，并且将释放与PoolChunk关联的所有内存。
            assert chunk.usage() == 0;
            return false;
        }
        return prevList.move(chunk);
    }

    /**
     * 添加到chunk
     *
     * @param chunk
     */
    void add(PoolChunk<T> chunk) {
        //当块的使用情况大于最大的使用率，进行迁移，迁移到上方
        if (chunk.usage() >= maxUsage) {
            nextList.add(chunk);
            return;
        }
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     * <p>
     * 将{@link PoolChunk}添加到此{@link PoolChunkList}。
     * </p>
     */
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    /**
     * 删除list中cur
     *
     * @param cur chunk
     */
    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head; ; ) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head; ; ) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
