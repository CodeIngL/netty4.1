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
 * 池化的子页
 *
 * @param <T>
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    //所属的chunk
    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    //页大小
    private final int pageSize;
    //位图，其长度存在一个最大个数，也就是页/最小规格
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    //实际使用位图的个数
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Special constructor that creates a linked list head
     * 创建链表头的特殊构造函数
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * 构建一个子页面
     *
     * @param head
     * @param chunk
     * @param memoryMapIdx
     * @param runOffset
     * @param pageSize
     * @param elemSize
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        //所在的chunk
        this.chunk = chunk;
        //对应的memoryMapIdx
        this.memoryMapIdx = memoryMapIdx;
        //在内存数据中的起始偏移量
        this.runOffset = runOffset;
        //页大小
        this.pageSize = pageSize;
        // 使用了一个bitmap来记录一个page的分配情况，这个bitmap数组默认size为8，因为我们最小的分配内存是16，
        // 所以一个page最多可以被分成512个小段，而一个long可以描述64位位图信息，
        // 所以只需要8个long就可以进行内存管理描述了。
        // pageSize / 16 / 64 = pagesSize/2^10
        bitmap = new long[pageSize >>> 10];
        init(head, elemSize);
    }

    /**
     * 比如当前申请16大小的内存，
     * maxNumElems=numAvail=512,
     * 说明一个page被拆成了512个内存段，
     * bitmaplength=512／64=8，
     * 然后将当前PoolSubpage加入到链表中。
     *
     * @param head
     * @param elemSize 申请的容量
     */
    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true; //没有被销毁
        this.elemSize = elemSize; //分配的大小
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize; //最大的数量和可用的数量
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6; //需要几个bit来标识 2^6=64，一个long能使用64个位置
            if ((maxNumElems & 63) != 0) { //如果有多，需要一个额外得long，上述已经得出了高位需要多少，这里至多在添加一个
                bitmapLength++;
            }

            //初始化位图数组
            for (int i = 0; i < bitmapLength; i++) {
                bitmap[i] = 0;
            }
        }
        //添加到槽位的链表中
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * <p>
     * 返回子页面分配的位图索引
     * <p>
     * a，方法getNextAvail找到当前page中可分配内存段的bitmapIdx，初始时这个值为0
     * </p>
     * <p>
     * b，q=bitmapIdx/64,确认long数组的位置，
     * </p>
     * <p>
     * c，bitmapIdx＆63确定long的bit位置，然后将该位置值为1
     * </p>
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail();
        int q = bitmapIdx >>> 6; //高位
        int r = bitmapIdx & 63; //低位
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r; //变更，打上位图

        if (--numAvail == 0) { //没有可用的，移走
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * 释放一块内存
     *
     * @return {@code true} if this subpage is in use.
     * {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail++ == 0) { //至少一个，添加到arena中
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) { // 有被占用，但不是完全被占用
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool. //唯一的一个，我们就不删除了
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool. //删除掉这个，完全被释放的subpage
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     * 添加到head上
     *
     * @param head
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    /**
     * 获得下一个可用的位置
     *
     * @return
     */
    private int getNextAvail() {
        //下一个可用的位置
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) { //大于0
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    /**
     * 查找下一个可用的位置
     *
     * @return
     */
    private int findNextAvail() {
        final long[] bitmap = this.bitmap; //位图
        final int bitmapLength = this.bitmapLength; //本规格最大有效个数
        for (int i = 0; i < bitmapLength; i++) { //遍历
            long bits = bitmap[i];
            if (~bits != 0) { //取反是0，说明存空位
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * 进行查找
     *
     * @param i
     * @param bits
     * @return
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j++) {
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    /**
     * 转换为句柄
     *
     * @param bitmapIdx
     * @return
     */
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
