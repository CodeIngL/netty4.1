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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

/**
 * 支持池化的Arena
 *
 * @param <T>
 */
abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    /**
     * 类型
     */
    enum SizeClass {
        Tiny,
        Small,
        Normal
    }

    //32
    static final int numTinySubpagePools = 512 >>> 4;

    //分配器
    final PooledByteBufAllocator parent;

    private final int maxOrder; //默认11
    final int pageSize; //默认8K
    final int pageShifts; //默认移位13
    final int chunkSize; //默认8192*2^11（16M）
    final int subpageOverflowMask; //~(pageSize - 1)
    final int numSmallSubpagePools; //pageShifts - 9默认4
    //针对的是容量大运一个chunk的请求。默认是0
    //本地大容量的填充，我们最好使用规格化的方式进行，比如填充，而不是假设我们申请16M1B，底层我们应该使用多大的空间呢。
    final int directMemoryCacheAlignment;
    //填充的掩码值
    final int directMemoryCacheAlignmentMask;
    //使用的tiny子页
    private final PoolSubpage<T>[] tinySubpagePools;
    //使用的small子页
    private final PoolSubpage<T>[] smallSubpagePools;

    //poolChunkList列表，使用率不同的poolchunk会在其中发生移动
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    //初始化列表队列
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    // 我们需要在这里使用LongCounter，因为这不是通过synchronized块来保护的。
    //计数维护tiny
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    //计数维护small
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    //计数维护huge
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    //
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    // 此arena支持的ThreadCache的数量。 每当被线程持有引用的时候就会进行++，反应被持有线程的数量
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
                        int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        //页大小
        this.pageSize = pageSize;
        //最大深度
        this.maxOrder = maxOrder;
        //页移位
        this.pageShifts = pageShifts;
        //chunk大小。pagesSize<<maxOrder，默认
        this.chunkSize = chunkSize;
        //直接对其填充
        directMemoryCacheAlignment = cacheAlignment;
        //掩码
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        //掩码
        subpageOverflowMask = ~(pageSize - 1);
        //tiny子页，32长度
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        //small数量，pageShifts移位-9，默认4
        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        //使用量
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);//[100,MAX_VALUE]
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);//[75,100]
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);//[50,100]
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);//[25,75]
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);//[1,50]
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);//[MIN_VALUE,25]

        //构建链表
        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        //统计
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /**
     * 构建head
     *
     * @param pageSize
     * @return
     */
    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    /**
     * 分配
     *
     * @param cache
     * @param reqCapacity
     * @param maxCapacity
     * @return
     */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity); //创建一个新的buf，里面除了maxCapacity，没有other,也就是没有绑定相关的内存，即未初始化
        allocate(cache, buf, reqCapacity);//使用线程cacahe来为这个新的buf分配空间
        return buf;
    }

    /**
     * 右移4位（/16)
     *
     * @param normCapacity
     * @return
     */
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    /**
     * 右移10位（/1024)
     * 根据剩余的1的个数进行叠加获得
     *
     * @param normCapacity
     * @return
     */
    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    /**
     * arena分配，分配buf空间
     * <p>
     * 1，首先从PoolThreadCache中进行分配,不是huge的话
     * </p>
     * <p>
     * 2，如果是分配小内存(<8192),则由tinySubpagePools或smallSubpagePools进行分配，如果没有合适subpage，则采用方法allocateNormal分配内存。
     * </p>
     * <p>
     * 3，分配pageSize以上的内存由allocateNormal进行分配
     * </p>
     * <p>
     * 4, 更大的使用allocateHuge分配
     * </p>
     *
     * @param cache 线程缓存
     * @param buf 待初始化的buffer
     * @param reqCapacity 请求的容量
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int normCapacity = normalizeCapacity(reqCapacity); //规范化请求的容量
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize 容量需要在tiny或者small上分配
            int tableIdx; //索引
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                //使用线程级别的缓存分配，尝试使用
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    return;
                }
                //规格位置索引
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                //使用线程级别的缓存分配
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    return;
                }
                //规格位置索引
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            //获得对应索引表的头部
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             * <p>
             *     在头上同步。 这需要{@link PoolChunk#allocateSubpage(int)}和{@link PoolChunk#free(long)}也可以修改双向链表。
             */
            synchronized (head) {
                //尝试在arena本身上的优化手段进行相关的分配
                final PoolSubpage<T> s = head.next; //下一个
                if (s != head) {
                    //说明索引表中该规格下，挂着相关的对象
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    //尝试用这个对象，进行分配
                    long handle = s.allocate();
                    //保证句柄是正确的
                    assert handle >= 0;
                    //使用子页来完成请求的buffer分配
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    //增加tiny or small上的统计
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            //转入正常的分配流程
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            //增加tiny or small上的统计
            incTinySmallAllocation(tiny);
            return;
        }

        //普通规格的分配

        if (normCapacity <= chunkSize) {//小于chunkSize
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            //大规格的,大于chunkSize的分配
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    // 必须在synchronized(this) { ... }块内调用方法
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        //使用不同的chunk进行尝试为我们buf进行分配空间
        //分配成功，直接返回
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
                q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
                q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        // 构建一个新的chunk
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        //使用chunk进行分配
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        //新建的chunk都添加进的init checkList
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            //tiny
            allocationsTiny.increment();
        } else {
            //small
            allocationsSmall.increment();
        }
    }

    /**
     * 分配大空间
     *
     * @param buf
     * @param reqCapacity
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        //直接构造一个不支持池化的内存块
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        //统计值
        activeBytesHuge.add(chunk.chunkSize());
        //使用内存块为这个buf进行初始化
        buf.initUnpooled(chunk, reqCapacity);
        //统计
        allocationsHuge.increment();
    }

    /**
     * arena的释放操作
     *
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param normCapacity
     * @param cache
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) { //如果chunk不是pool的，直接简单操作
            int size = chunk.chunkSize();
            destroyChunk(chunk); //销毁
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            //获得要释放块的规格类型
            SizeClass sizeClass = sizeClass(normCapacity);
            //存在缓存，我们添加到线程缓存中，直接让线程缓存循环使用
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                // 缓存存在因此不要释放他
                return;
            }
            //否则释放，对应的区域
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    /**
     * 释放chunk中句柄对应的内存区域
     *
     * @param chunk     内存块
     * @param handle    句柄
     * @param sizeClass 类型
     * @param nioBuffer
     * @param finalizer
     */
    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            // 如果由于PoolThreadCache终结器而没有调用freeChunk，我们只调用它，否则由于例如tomcat中的延迟类加载，这可能会失败。
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }
            //是否需要销毁chunk
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        //在整个列表中都是小于最小使用量，我们进行chunk的销毁
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            // 保持同步锁时不需要调用destroyChunk。
            destroyChunk(chunk);
        }
    }

    /**
     * @param elemSize 元素大小
     * @return
     */
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx; //坐标
        PoolSubpage<T>[] table; //表
        if (isTiny(elemSize)) { // < 512 小于512,
            tableIdx = elemSize >>> 4;//右移4位
            table = tinySubpagePools; //使用tiny分配
        } else {
            tableIdx = 0;
            elemSize >>>= 10; //移动10位
            while (elemSize != 0) { //得到坐标
                elemSize >>>= 1;
                tableIdx++;
            }
            table = smallSubpagePools; //使用small分配
        }

        return table[tableIdx];
    }

    /**
     * 对请求的容量规范化
     *
     * @param reqCapacity
     * @return
     */
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");

        if (reqCapacity >= chunkSize) { //16M，大于内存块，如果我们设定了直接的填充，我们将进行填充，使得容量规范化
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            int normalizedCapacity = reqCapacity;
            normalizedCapacity--;
            normalizedCapacity |= normalizedCapacity >>> 1;
            normalizedCapacity |= normalizedCapacity >>> 2;
            normalizedCapacity |= normalizedCapacity >>> 4;
            normalizedCapacity |= normalizedCapacity >>> 8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced,量子间隔
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    int alignCapacity(int reqCapacity) {
        //尾缀
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        //向上规范化
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    /**
     * 重新分配
     *
     * @param buf           缓冲区
     * @param newCapacity   新容量
     * @param freeOldMemory 是否释放旧的内存
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        //参数校验
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        //如果支持释放旧有的内存，我们尝试进行内存的释放
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m : chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    /**
     * 构建一个新Chunk，arena提供了构建新Chunk的功能，如果需要将构建新的chunk
     *
     * @param pageSize   页大小
     * @param maxOrder   深度
     * @param pageShifts 页移位
     * @param chunkSize  块大小
     * @return 新的块
     */
    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);

    /**
     * 构建一块未池化的块
     *
     * @param capacity
     * @return
     */
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);

    /**
     * 构建一个缓存区，尚未初始化，初始化是值得是我们需要使用内存和这块缓冲区进行绑定
     * 由于分配变量非常频繁，我们需要使用对象池来完成减小对象的大量分配
     *
     * @param maxCapacity 最大容量
     * @return 一个尚未初始化的缓冲区
     */
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);

    /**
     * 内存copy
     *
     * @param src
     * @param srcOffset
     * @param dst
     * @param dstOffset
     * @param length
     */
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);

    /**
     * 销毁一块chunk
     *
     * @param chunk
     */
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
                .append("Chunk(s) at 0~25%:")
                .append(StringUtil.NEWLINE)
                .append(qInit)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 0~50%:")
                .append(StringUtil.NEWLINE)
                .append(q000)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 25~75%:")
                .append(StringUtil.NEWLINE)
                .append(q025)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 50~100%:")
                .append(StringUtil.NEWLINE)
                .append(q050)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 75~100%:")
                .append(StringUtil.NEWLINE)
                .append(q075)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 100%:")
                .append(StringUtil.NEWLINE)
                .append(q100)
                .append(StringUtil.NEWLINE)
                .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
                .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList : chunkLists) {
            chunkList.destroy(this);
        }
    }

    /**
     * 堆上的支持池化的Arena
     */
    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                  int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        /**
         * 创建byte数组
         *
         * @param size
         * @return
         */
        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        /**
         * 指定容量构建一个池化的ByteBuffer
         *
         * @param maxCapacity
         * @return
         */
        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            //存在unsafe工具，使用unsafe否则使用一般的情况
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    /**
     * 直接内存的支持池化的Arena
     */
    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                    int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        /**
         * 构建一个新Chunk，arena提供了构建新Chunk的功能，如果需要将构建新的chunk
         *
         * @param pageSize   页大小
         * @param maxOrder   深度
         * @param pageShifts 页移位
         * @param chunkSize  块大小
         * @return 新的块
         */
        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this, allocateDirect(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
            }
            //后面需要加上填充的部分，如果有的话
            final ByteBuffer memory = allocateDirect(chunkSize + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize, maxOrder, pageShifts, chunkSize, offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        /**
         * 构建池化的直接ByteBuffer，根据有无unsafe使用不同方式
         *
         * @param maxCapacity 最大容量
         * @return
         */
        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                // 我们必须复制NIO缓冲区，因为它们可能被其他Netty缓冲区访问。
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
