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


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 *
 * <p>
 *     使用线程缓存进行分配。 在jemalloc和使用jemalloc的可扩展内存分配的描述技术之后，该实现被模块化。
 * </p>
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    //堆上的缓存
    final PoolArena<byte[]> heapArena;
    //直接内存的缓存
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    // 保持不同大小类的缓存，这些类tiny，small，normal。
    //tiny堆上分配
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches;
    //small堆上分配
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    //tiny堆外分配
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    //small堆外分配
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    //normal堆上分配
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    //normal堆外分配
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // Used for bitshifting when calculate the index of normal caches later
    // 用于稍后计算正常高速缓存的索引时的位移
    // 直接内存
    private final int numShiftsNormalDirect;
    // 堆上内存
    private final int numShiftsNormalHeap;
    // 分配次数到达该阈值则检测释放，默认是8192
    private final int freeSweepAllocationThreshold;
    private final AtomicBoolean freed = new AtomicBoolean();

    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 池化的ThreadCache
     * @param heapArena 将使用的堆内Arena，来自全局的
     * @param directArena 将使用的堆外Arena，来自全局的
     * @param tinyCacheSize tinyCache大小默认512
     * @param smallCacheSize smallCache大小默认256
     * @param normalCacheSize normalChache大小默认64
     * @param maxCachedBufferCapacity 最大能够被缓存的Buffer容量，默认32KB
     * @param freeSweepAllocationThreshold  清理交换分配的阈值， 默认8192
     */
    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {
        //检查最大容量
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        //空闲的交换内存
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        //堆内Arena
        this.heapArena = heapArena;
        //堆外Arena
        this.directArena = directArena;
        if (directArena != null) { //使用堆外内存
            tinySubPageDirectCaches = createSubPageCaches(tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageDirectCaches = createSubPageCaches(smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalDirect = log2(directArena.pageSize);
            normalDirectCaches = createNormalCaches(normalCacheSize, maxCachedBufferCapacity, directArena);

            //完成绑定，我们把arena被引用的数量进行++
            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            // 没有配置directArea所以只是取消所有缓存
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }
        if (heapArena != null) { //使用堆内内存
            // Create the caches for the heap allocations
            // 为堆分配创建高速缓存
            tinySubPageHeapCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            //返回移位数量
            numShiftsNormalHeap = log2(heapArena.pageSize);
            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            //完成绑定，我们把arena被引用的数量进行++
            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: " + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    /**
     * 构建Thread级别的，SubPages内存区域缓存，对应SubPages只有tiny和small
     * @param cacheSize tiny small 各个级别的定义，tiny默认512，small 默认256
     * @param numCaches Cache的数量，不同的size级别使用不同的数值 tiny默认32 small 默认4
     * @param sizeClass tiny，small
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
            }
            return cache;
        } else {
            return null;
        }
    }

    /**
     * 构建Thread级别的，Normal级别内存区域缓存
     * @param cacheSize 默认64
     * @param maxCachedBufferCapacity  最大能够被缓存的Buffer容量，默认32KB，最大能被调整为一个chunk大小
     * @param area 对应的areana
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {

        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity); //前者默认16M，后者默认32KB，pasgSize默认8K，最大normal能缓存的规格只能是设定的并且不超过一个chunk大小
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1); //对应产出的数组大小，不同规格的类型，一个普通pagesSize是8k,因此让我们是用最大缓存值来进行划分

            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[arraySize]; //构建缓存
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new NormalMemoryRegionCache<T>(cacheSize); //每一种规格缓存的数量总是有上限。
            }
            return cache;
        } else {
            return null;
        }
    }

    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }

    /**
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * <p>
     *     尝试从缓存中分配一个tiny缓冲区。 如果成功{@code false}，则返回{@code true}
     * </p>
     */
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForTiny(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * <p>
     *     尝试从缓存中分配一个small缓冲区。 如果成功{@code false}，则返回{@code true}
     * </p>
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForSmall(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * <p>
     *     尝试从缓存中分配一个normal缓冲区。 如果成功{@code false}，则返回{@code true}
     * </p>
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForNormal(area, normCapacity), buf, reqCapacity);
    }

    /**
     * 使用线程级别的维护的缓存进行分配相关的内存
     * @param cache 缓存
     * @param buf 待初始化的buffer
     * @param reqCapacity 请求分配容量
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // 没有找到缓存所以只在这里返回false,分配失败
            return false;
        }
        //尝试进行分配
        boolean allocated = cache.allocate(buf, reqCapacity);

        if (++ allocations >= freeSweepAllocationThreshold) {
            //分配到达阈值，我们尝试进行修剪并重置
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     * <p>
     *     如果有足够的空间，请添加{@link PoolChunk}和{@code handle}并处理缓存。 如果它适合缓存，则返回true，否则返回false
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        MemoryRegionCache<?> cache = cache(area, normCapacity, sizeClass);
        if (cache == null) {
            return false;
        }
        return cache.add(chunk, nioBuffer, handle);
    }

    /**
     * 查找对应的线程级别的缓存块
     * @param area
     * @param normCapacity
     * @param sizeClass
     * @return
     */
    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
            case Normal:
                return cacheForNormal(area, normCapacity);
            case Small:
                return cacheForSmall(area, normCapacity);
            case Tiny:
                return cacheForTiny(area, normCapacity);
            default:
                throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free(true);
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     *  <p>
     *      如果将要使用此高速缓存的线程存在以从缓存中释放资源，则应调用此方法
     *  </p>
     */
    void free(boolean finalizer) {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(tinySubPageDirectCaches, finalizer) +
                    free(smallSubPageDirectCaches, finalizer) +
                    free(normalDirectCaches, finalizer) +
                    free(tinySubPageHeapCaches, finalizer) +
                    free(smallSubPageHeapCaches, finalizer) +
                    free(normalHeapCaches, finalizer);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches, boolean finalizer) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c, finalizer);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache, boolean finalizer) {
        if (cache == null) {
            return 0;
        }
        return cache.free(finalizer);
    }

    /**
     * 进行对线程缓存进行清理
     */
    void trim() {
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    /**
     * 内存缓存的清理
     * @param cache
     */
    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    /**
     * 分配tiny缓存
     * @param area
     * @param normCapacity 规范的要求的容量
     * @return
     */
    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        //获得规格的索引
        int idx = PoolArena.tinyIdx(normCapacity);
        if (area.isDirect()) {
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    /**
     * 分配small缓存
     * @param area
     * @param normCapacity
     * @return
     */
    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        int idx = PoolArena.smallIdx(normCapacity);
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, idx);
        }
        return cache(smallSubPageHeapCaches, idx);
    }

    /**
     * 分配normal缓存
     * @param area
     * @param normCapacity
     * @return
     */
    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            return cache(normalDirectCaches, idx);
        }
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        return cache(normalHeapCaches, idx);
    }

    /**
     * 构建cache，从缓存中获得
     * @param cache
     * @param idx
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        return cache[idx];
    }


    //subPage内存cache和正常的cache

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity);
        }
    }


    //--------------关于线程的Cache
    /**
     * 线程级别的缓存
     * 内存区域的缓存，线程级别的缓存
     * @param <T>
     */
    private abstract static class MemoryRegionCache<T> {
        //规格tiny为512，small为256，normal64
        private final int size;
        //队列条目，由规格来确定队列的容量大小
        private final Queue<Entry<T>> queue;
        //cache类型大小
        private final SizeClass sizeClass;
        //已经产生的分配次数
        private int allocations;

        /**
         * 线程内存级缓存
         * @param size 数量
         * @param sizeClass 规格类型
         */
        MemoryRegionCache(int size, SizeClass sizeClass) {
            //大小
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            //队列
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            //类型
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         * <p>
         *     使用提供的块启动{@link PooledByteBuf}并处理容量限制。
         * </p>
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity);

        /**
         * Add to cache if not already full.
         * <p>
         *     如果尚未填充，请添加到缓存。
         * @param handle 内存块的句柄
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle) {
            //添加的缓存以entry的形式存在，带入queue
            Entry<T> entry = newEntry(chunk, nioBuffer, handle);
            boolean queued = queue.offer(entry);
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                // 如果无法缓存块，请立即回收该条目
                entry.recycle();
            }

            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         * <p>
         *     如果可能，从缓存中分配一些内容，并从缓存中删除该entry。
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            //尝试从队列中拉却相关的缓存条目，使用条目完成对缓冲区的初始化
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }
            //初始化缓冲区
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity);
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            // 分配不是线程安全的，因为这只是始终从同一个线程调用。
            ++ allocations;
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         * <p>
         *     清除此cache并释放所有以前缓存的{@link PoolChunk}和{@code handle}。
         */
        public final int free(boolean finalizer) {
            return free(Integer.MAX_VALUE, finalizer);
        }

        /**
         * 清理
         * @param max
         * @param finalizer
         * @return
         */
        private int free(int max, boolean finalizer) {
            int numFreed = 0; //被释放的数量
            for (; numFreed < max; numFreed++) {
                Entry<T> entry = queue.poll(); //拉取条目
                if (entry != null) { //释放条目
                    freeEntry(entry, finalizer);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         * <p>
         *     如果没有足够频繁地分配，可以释放缓存的{@link PoolChunk}。
         */
        public final void trim() {
            //空闲的缓存数量
            int free = size - allocations;
            //设置0
            allocations = 0;

            // We not even allocated all the number that are
            // 我们甚至没有分配所有的number
            if (free > 0) {
                free(free, false);
            }
        }

        /***
         * 释放条目
         * @param entry
         * @param finalizer
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry, boolean finalizer) {
            //获得chunk
            PoolChunk chunk = entry.chunk;
            //获得句柄
            long handle = entry.handle;
            //获得对应的buffer
            ByteBuffer nioBuffer = entry.nioBuffer;

            if (!finalizer) {
                // recycle now so PoolChunk can be GC'ed. This will only be done if this is not freed because of
                // a finalizer.
                entry.recycle();
            }

            chunk.arena.freeChunk(chunk, handle, sizeClass, nioBuffer, finalizer);
        }

        /**
         * 缓存条目
         * @param <T>
         */
        static final class Entry<T> {

            final Handle<Entry<?>> recyclerHandle;
            //所属的chunk
            PoolChunk<T> chunk;
            //相关的缓冲区
            ByteBuffer nioBuffer;
            //对应的句柄
            long handle = -1;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };
    }
}
