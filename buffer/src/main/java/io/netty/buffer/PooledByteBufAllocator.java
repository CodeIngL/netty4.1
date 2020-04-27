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

import static io.netty.buffer.UnsafeByteBufUtil.newUnsafeDirectByteBuf;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static io.netty.util.internal.PlatformDependent.hasUnsafe;
import static io.netty.util.internal.SystemPropertyUtil.getInt;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 池化的内存byteBuffer分配器
 */
public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PooledByteBufAllocator.class);
    //默认堆上的arena数量，一般默认处理器*2
    private static final int DEFAULT_NUM_HEAP_ARENA;
    //默认直接内存的arena数量，一般默认处理器*2
    private static final int DEFAULT_NUM_DIRECT_ARENA;

    //默认页大小默认8192=8K
    private static final int DEFAULT_PAGE_SIZE;
    //最大order默认11，得出chunk大小默认16M
    private static final int DEFAULT_MAX_ORDER; // 8192 << 11 = 16 MiB per chunk
    //默认tinycache大小512
    private static final int DEFAULT_TINY_CACHE_SIZE;
    //默认smallcache大小256
    private static final int DEFAULT_SMALL_CACHE_SIZE;
    //默认normalcache大小64
    private static final int DEFAULT_NORMAL_CACHE_SIZE;
    //默认cache的buffer最大容量32kb
    private static final int DEFAULT_MAX_CACHED_BUFFER_CAPACITY;
    //如果不经常使用缓存entry将被释放的分配阈值数默认8192
    private static final int DEFAULT_CACHE_TRIM_INTERVAL;
    //默认缓存修剪间隔毫秒
    private static final long DEFAULT_CACHE_TRIM_INTERVAL_MILLIS;
    //是否为所有的线程使用缓存
    private static final boolean DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    // 直接内存缓存对齐，默认是0
    private static final int DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT;
    //每个块默认最大缓存字节缓冲区数量
    static final int DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK;

    //最小页大小
    private static final int MIN_PAGE_SIZE = 4096;
    //最大chunk大小
    private static final int MAX_CHUNK_SIZE = (int) (((long) Integer.MAX_VALUE + 1) / 2);

    /**
     * 清理任务
     */
    private final Runnable trimTask = new Runnable() {
        @Override
        public void run() {
            PooledByteBufAllocator.this.trimCurrentThreadCache();
        }
    };

    /**
     * 静态代码块初始化池化分配器
     */
    static {
        //默认页大小，8KB
        int defaultPageSize = getInt("io.netty.allocator.pageSize", 8192);
        Throwable pageSizeFallbackCause = null;
        try {
            validateAndCalculatePageShifts(defaultPageSize);
        } catch (Throwable t) {
            pageSizeFallbackCause = t;
            defaultPageSize = 8192;
        }
        DEFAULT_PAGE_SIZE = defaultPageSize;

        //默认的深度是11，跟内存chunk相关
        int defaultMaxOrder = getInt("io.netty.allocator.maxOrder", 11);
        Throwable maxOrderFallbackCause = null;
        try {
            validateAndCalculateChunkSize(DEFAULT_PAGE_SIZE, defaultMaxOrder);
        } catch (Throwable t) {
            maxOrderFallbackCause = t;
            defaultMaxOrder = 11;
        }
        DEFAULT_MAX_ORDER = defaultMaxOrder;

        // Determine reasonable default for nHeapArena and nDirectArena.
        // Assuming each arena has 3 chunks, the pool should not consume more than 50% of max memory.
        // 确定nHeapArena和nDirectArena的合理默认值。 假设每个arena有3个区块，则pool不应超过最大内存的50％。
        final Runtime runtime = Runtime.getRuntime();

        /*
         * We use 2 * available processors by default to reduce contention as we use 2 * available processors for the
         * number of EventLoops in NIO and EPOLL as well. If we choose a smaller number we will run into hot spots as
         * allocation and de-allocation needs to be synchronized on the PoolArena.
         *
         * See https://github.com/netty/netty/issues/3888.
         */
        /**
         * 我们默认使用2 * available processors来减少争用，因为我们使用2 * available processors 来处理NIO和EPOLL中的EventLoops数量。
         * 如果我们选择较小的数字，我们将遇到热点，因为需要在PoolArena上对分配和取消分配进行同步操作。
         *
         * 请参阅https://github.com/netty/netty/issues/3888。
         */
        final int defaultMinNumArena = NettyRuntime.availableProcessors() * 2; //处理器*2
        final int defaultChunkSize = DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER;//默认16M
        //假设每一个arena有3个区块，pool不超过最大内存的50%
        DEFAULT_NUM_HEAP_ARENA = Math.max(0,
                getInt("io.netty.allocator.numHeapArenas",
                        (int) Math.min(defaultMinNumArena, runtime.maxMemory() / defaultChunkSize / 2 / 3)));
        //假设每一个arena有3个区块，pool不超过最大内存的50%
        DEFAULT_NUM_DIRECT_ARENA = Math.max(0,
                getInt("io.netty.allocator.numDirectArenas",
                        (int) Math.min(defaultMinNumArena, PlatformDependent.maxDirectMemory() / defaultChunkSize / 2 / 3)));

        // cache sizes
        // 缓存的个数数量
        DEFAULT_TINY_CACHE_SIZE = getInt("io.netty.allocator.tinyCacheSize", 512);
        DEFAULT_SMALL_CACHE_SIZE = getInt("io.netty.allocator.smallCacheSize", 256);
        DEFAULT_NORMAL_CACHE_SIZE = getInt("io.netty.allocator.normalCacheSize", 64);

        // 32 kb is the default maximum capacity of the cached buffer. Similar to what is explained in
        // 'Scalable memory allocation using jemalloc'
        // 32 kb是缓存缓冲区的默认最大容量。 类似于'使用jemalloc进行可扩展内存分配'中的解释
        //最大规格，对于normal级别的缓存来说
        DEFAULT_MAX_CACHED_BUFFER_CAPACITY = getInt("io.netty.allocator.maxCachedBufferCapacity", 32 * 1024);

        // the number of threshold of allocations when cached entries will be freed up if not frequently used
        // 如果不经常使用缓存entry将被释放的分配阈值数
        DEFAULT_CACHE_TRIM_INTERVAL = getInt("io.netty.allocator.cacheTrimInterval", 8192);

        DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong("io.netty.allocation.cacheTrimIntervalMillis", 0);

        DEFAULT_USE_CACHE_FOR_ALL_THREADS = SystemPropertyUtil.getBoolean("io.netty.allocator.useCacheForAllThreads", true);

        DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT = getInt("io.netty.allocator.directMemoryCacheAlignment", 0);

        // Use 1023 by default as we use an ArrayDeque as backing storage which will then allocate an internal array
        // of 1024 elements. Otherwise we would allocate 2048 and only use 1024 which is wasteful.
        // 默认情况下使用1023，因为我们使用ArrayDeque作为后备存储，然后将分配1024个元素的内部数组。
        // 否则我们将分配2048并且仅使用1024这是浪费
        DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK = getInt("io.netty.allocator.maxCachedByteBuffersPerChunk", 1023);

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.allocator.numHeapArenas: {}", DEFAULT_NUM_HEAP_ARENA);
            logger.debug("-Dio.netty.allocator.numDirectArenas: {}", DEFAULT_NUM_DIRECT_ARENA);
            if (pageSizeFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE);
            } else {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE, pageSizeFallbackCause);
            }
            if (maxOrderFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER);
            } else {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER, maxOrderFallbackCause);
            }
            logger.debug("-Dio.netty.allocator.chunkSize: {}", DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER);
            logger.debug("-Dio.netty.allocator.tinyCacheSize: {}", DEFAULT_TINY_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.smallCacheSize: {}", DEFAULT_SMALL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.normalCacheSize: {}", DEFAULT_NORMAL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.maxCachedBufferCapacity: {}", DEFAULT_MAX_CACHED_BUFFER_CAPACITY);
            logger.debug("-Dio.netty.allocator.cacheTrimInterval: {}", DEFAULT_CACHE_TRIM_INTERVAL);
            logger.debug("-Dio.netty.allocator.cacheTrimIntervalMillis: {}", DEFAULT_CACHE_TRIM_INTERVAL_MILLIS);
            logger.debug("-Dio.netty.allocator.useCacheForAllThreads: {}", DEFAULT_USE_CACHE_FOR_ALL_THREADS);
            logger.debug("-Dio.netty.allocator.maxCachedByteBuffersPerChunk: {}", DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK);
        }
    }

    public static final PooledByteBufAllocator DEFAULT = new PooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    //堆内arena
    private final PoolArena<byte[]>[] heapArenas;
    //堆外arena
    private final PoolArena<ByteBuffer>[] directArenas;
    //thread级别缓存tiny数量，默认512
    private final int tinyCacheSize;
    //thread级别缓存small数量，默认256
    private final int smallCacheSize;
    //thread级别缓存normal数量，默认64
    private final int normalCacheSize;
    //堆内统计信息
    private final List<PoolArenaMetric> heapArenaMetrics;
    //堆外统计信息
    private final List<PoolArenaMetric> directArenaMetrics;
    //特殊的ThreadLocal
    private final PoolThreadLocalCache threadCache;
    //默认8192*2^11（16M），内存块大小
    private final int chunkSize;
    //分配统计信息
    private final PooledByteBufAllocatorMetric metric;

    //-------------------池化的构造函数----------------------//

    public PooledByteBufAllocator() {
        this(false);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(boolean preferDirect) {
        this(preferDirect, DEFAULT_NUM_HEAP_ARENA, DEFAULT_NUM_DIRECT_ARENA, DEFAULT_PAGE_SIZE, DEFAULT_MAX_ORDER);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(false, nHeapArena, nDirectArena, pageSize, maxOrder);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
                DEFAULT_TINY_CACHE_SIZE, DEFAULT_SMALL_CACHE_SIZE, DEFAULT_NORMAL_CACHE_SIZE);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder, tinyCacheSize, smallCacheSize,
                normalCacheSize, DEFAULT_USE_CACHE_FOR_ALL_THREADS, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder, int tinyCacheSize,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
                tinyCacheSize, smallCacheSize, normalCacheSize,
                useCacheForAllThreads, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * 池化的ByteBuf的分配器的核心构造函数
     *
     * @param preferDirect               对直接内存的偏向
     * @param nHeapArena                 heapArena数量
     * @param nDirectArena               DirectArena数量
     * @param pageSize                   页大小
     * @param maxOrder                   最大深度
     * @param tinyCacheSize
     * @param smallCacheSize
     * @param normalCacheSize
     * @param useCacheForAllThreads
     * @param directMemoryCacheAlignment
     */
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        super(preferDirect);//是否更倾向使用直接内存进行分配
        //构建线程级别的缓存
        threadCache = new PoolThreadLocalCache(useCacheForAllThreads);
        //tiny大小
        this.tinyCacheSize = tinyCacheSize; //默认512个
        //small大小
        this.smallCacheSize = smallCacheSize; //默认256个
        //normal大小
        this.normalCacheSize = normalCacheSize; //默认64个
        //chunk大小, pageSize<<maxOrder,默认8192*2^11（16M），4K页，深度11
        chunkSize = validateAndCalculateChunkSize(pageSize, maxOrder); //默认8192*2^11（16M）

        //检查参数
        checkPositiveOrZero(nHeapArena, "nHeapArena");
        //检查参数
        checkPositiveOrZero(nDirectArena, "nDirectArena");

        //检查参数,直接内存缓存对齐此参数是2的N次幂，能够填充的前提条件是java中支持unsafe
        checkPositiveOrZero(directMemoryCacheAlignment, "directMemoryCacheAlignment");
        if (directMemoryCacheAlignment > 0 && !isDirectMemoryCacheAlignmentSupported()) {
            throw new IllegalArgumentException("directMemoryCacheAlignment is not supported");
        }
        if ((directMemoryCacheAlignment & -directMemoryCacheAlignment) != directMemoryCacheAlignment) {
            throw new IllegalArgumentException("directMemoryCacheAlignment: "
                    + directMemoryCacheAlignment + " (expected: power of two)");
        }

        //计算页的移位，页大小是2的M次幂
        int pageShifts = validateAndCalculatePageShifts(pageSize);//默认13，8192=2^13

        //存在heapArena数量
        if (nHeapArena > 0) { //默认处理器数量*2
            //构建数组
            heapArenas = newArenaArray(nHeapArena);
            //构建统计数组
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(heapArenas.length);
            for (int i = 0; i < heapArenas.length; i++) {
                //初始化每一个数组元素
                PoolArena.HeapArena arena = new PoolArena.HeapArena(this,
                        pageSize, maxOrder, pageShifts, chunkSize,
                        directMemoryCacheAlignment);
                heapArenas[i] = arena;
                metrics.add(arena);
            }
            heapArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            heapArenas = null;
            heapArenaMetrics = Collections.emptyList();
        }

        //存在nDirectArena数量
        if (nDirectArena > 0) {  //默认处理器数量*2
            //构建数组
            directArenas = newArenaArray(nDirectArena);
            //构建统计数组
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(directArenas.length);
            for (int i = 0; i < directArenas.length; i++) {
                //初始化每一个数组元素
                PoolArena.DirectArena arena = new PoolArena.DirectArena(
                        this, pageSize, maxOrder, pageShifts, chunkSize, directMemoryCacheAlignment);
                directArenas[i] = arena;
                metrics.add(arena);
            }
            directArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            directArenas = null;
            directArenaMetrics = Collections.emptyList();
        }
        //统计
        metric = new PooledByteBufAllocatorMetric(this);
    }

    //-----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> PoolArena<T>[] newArenaArray(int size) {
        return new PoolArena[size];
    }

    private static int validateAndCalculatePageShifts(int pageSize) {
        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: " + MIN_PAGE_SIZE + ")");
        }

        //检验一个2幂次数
        if ((pageSize & pageSize - 1) != 0) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: power of 2)");
        }

        // Logarithm base 2. At this point we know that pageSize is a power of two.
        // 对数基数2.此时我们知道pageSize是2的幂。
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize);
    }

    /**
     * chunkSize等于pageSize<<maxOrder
     * @param pageSize
     * @param maxOrder 最大order
     * @return
     */
    private static int validateAndCalculateChunkSize(int pageSize, int maxOrder) {
        //chunk最大的深度不能超过14
        if (maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder: " + maxOrder + " (expected: 0-14)");
        }

        // Ensure the resulting chunkSize does not overflow.
        // 确保生成的chunkSize不会溢出。
        int chunkSize = pageSize;
        for (int i = maxOrder; i > 0; i--) {
            //chunkSize不能大于最大标准的一半
            if (chunkSize > MAX_CHUNK_SIZE / 2) {
                throw new IllegalArgumentException(String.format("pageSize (%d) << maxOrder (%d) must not exceed %d", pageSize, maxOrder, MAX_CHUNK_SIZE));
            }
            chunkSize <<= 1;
        }
        return chunkSize;
    }

    /**
     * 构建新建的heapBuffer
     *
     * @param initialCapacity 初始化容量
     * @param maxCapacity     最大容量
     * @return
     */
    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        PoolThreadCache cache = threadCache.get(); //从缓存中获得当前线程的cache，不存在会构建
        PoolArena<byte[]> heapArena = cache.heapArena; //获得与线程相关的堆上池化arena

        final ByteBuf buf;
        if (heapArena != null) { //存在
            //使用池化arena分配一块buffer
            buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
        } else { //不存在，什么情景发生，我们指定了不使用内存池，nHeapBuffer为0
            buf = hasUnsafe() ?
                    new UnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        //泄漏感知
        return toLeakAwareBuffer(buf);
    }

    /**
     * 获得池化的一块直接内存
     *
     * @param initialCapacity 初始容量
     * @param maxCapacity     最大容量
     * @return
     */
    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        //获得当前线程的缓存
        PoolThreadCache cache = threadCache.get();
        //获得和线程绑定的相关的arena
        PoolArena<ByteBuffer> directArena = cache.directArena;

        final ByteBuf buf;
        if (directArena != null) {
            //arena是最直接的分配入口，通过与线程级别的cache结合，进行相关的内存的分配
            buf = directArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            //使用UnpooledUnsafeDirectByteBuf
            //使用UnpooledDirectByteBuf
            buf = hasUnsafe() ?
                    newUnsafeDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }
        //进行简单的封装
        return toLeakAwareBuffer(buf);
    }

    /**
     * Default number of heap arenas - System Property: io.netty.allocator.numHeapArenas - default 2 * cores
     */
    public static int defaultNumHeapArena() {
        return DEFAULT_NUM_HEAP_ARENA;
    }

    /**
     * Default number of direct arenas - System Property: io.netty.allocator.numDirectArenas - default 2 * cores
     */
    public static int defaultNumDirectArena() {
        return DEFAULT_NUM_DIRECT_ARENA;
    }

    /**
     * Default buffer page size - System Property: io.netty.allocator.pageSize - default 8192
     */
    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * Default maximum order - System Property: io.netty.allocator.maxOrder - default 11
     */
    public static int defaultMaxOrder() {
        return DEFAULT_MAX_ORDER;
    }

    /**
     * Default thread caching behavior - System Property: io.netty.allocator.useCacheForAllThreads - default true
     */
    public static boolean defaultUseCacheForAllThreads() {
        return DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    }

    /**
     * Default prefer direct - System Property: io.netty.noPreferDirect - default false
     */
    public static boolean defaultPreferDirect() {
        return PlatformDependent.directBufferPreferred();
    }

    /**
     * Default tiny cache size - System Property: io.netty.allocator.tinyCacheSize - default 512
     */
    public static int defaultTinyCacheSize() {
        return DEFAULT_TINY_CACHE_SIZE;
    }

    /**
     * Default small cache size - System Property: io.netty.allocator.smallCacheSize - default 256
     */
    public static int defaultSmallCacheSize() {
        return DEFAULT_SMALL_CACHE_SIZE;
    }

    /**
     * Default normal cache size - System Property: io.netty.allocator.normalCacheSize - default 64
     */
    public static int defaultNormalCacheSize() {
        return DEFAULT_NORMAL_CACHE_SIZE;
    }

    /**
     * Return {@code true} if direct memory cache alignment is supported, {@code false} otherwise.
     */
    public static boolean isDirectMemoryCacheAlignmentSupported() {
        return hasUnsafe();
    }

    @Override
    public boolean isDirectBufferPooled() {
        return directArenas != null;
    }

    /**
     * Returns {@code true} if the calling {@link Thread} has a {@link ThreadLocal} cache for the allocated
     * buffers.
     */
    @Deprecated
    public boolean hasThreadLocalCache() {
        return threadCache.isSet();
    }

    /**
     * Free all cached buffers for the calling {@link Thread}.
     */
    @Deprecated
    public void freeThreadLocalCache() {
        threadCache.remove();
    }

    /**
     * threadLocal分配
     */
    final class PoolThreadLocalCache extends FastThreadLocal<PoolThreadCache> {
        //是否对所有线程都采用cache
        private final boolean useCacheForAllThreads;

        PoolThreadLocalCache(boolean useCacheForAllThreads) {
            this.useCacheForAllThreads = useCacheForAllThreads;
        }

        /**
         * per 线程不存在的线程变量的时候，进行初始化分配，
         * 并创建可能的修剪任务进行清理
         * @return
         */
        @Override
        protected synchronized PoolThreadCache initialValue() {
            //获得绑定最少的堆内的池化Arena
            final PoolArena<byte[]> heapArena = leastUsedArena(heapArenas);
            //获得绑定最少的堆外的池化Arena
            final PoolArena<ByteBuffer> directArena = leastUsedArena(directArenas);

            final Thread current = Thread.currentThread();//当前线程
            if (useCacheForAllThreads || current instanceof FastThreadLocalThread) {//1.为所有线程构建缓存||2.当前线程是FastThreadLocalThread
                //构建一个池化Cache
                final PoolThreadCache cache = new PoolThreadCache(
                        heapArena, directArena, tinyCacheSize, smallCacheSize, normalCacheSize,
                        DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL);

                //创建任务
                if (DEFAULT_CACHE_TRIM_INTERVAL_MILLIS > 0) { //如果需要修剪，我们定时的调度去修剪缓存，默认的修剪时间是0
                    final EventExecutor executor = ThreadExecutorMap.currentExecutor();
                    if (executor != null) {
                        executor.scheduleAtFixedRate(trimTask, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
                return cache;
            }
            // 没有缓存所以只使用0作为大小。No caching so just use 0 as sizes.
            return new PoolThreadCache(heapArena, directArena, 0, 0, 0, 0, 0);
        }

        @Override
        protected void onRemoval(PoolThreadCache threadCache) {
            threadCache.free(false);
        }

        /**
         * 从数据列表中选择绑定最少使用的PoolArena
         * 我们总是遍历所有arena来进行查找当前被引用最少的arena
         *
         * @param arenas
         * @param <T>
         * @return
         */
        private <T> PoolArena<T> leastUsedArena(PoolArena<T>[] arenas) {
            //列表为空，直接返回null
            if (arenas == null || arenas.length == 0) {
                return null;
            }

            //第一个
            PoolArena<T> minArena = arenas[0];
            //遍历所用的元素，获得最小的arena
            for (int i = 1; i < arenas.length; i++) {
                PoolArena<T> arena = arenas[i];
                if (arena.numThreadCaches.get() < minArena.numThreadCaches.get()) {
                    minArena = arena;
                }
            }

            return minArena;
        }
    }

    @Override
    public PooledByteBufAllocatorMetric metric() {
        return metric;
    }

    /**
     * Return the number of heap arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numHeapArenas()}.
     */
    @Deprecated
    public int numHeapArenas() {
        return heapArenaMetrics.size();
    }

    /**
     * Return the number of direct arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numDirectArenas()}.
     */
    @Deprecated
    public int numDirectArenas() {
        return directArenaMetrics.size();
    }

    /**
     * Return a {@link List} of all heap {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#heapArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> heapArenas() {
        return heapArenaMetrics;
    }

    /**
     * Return a {@link List} of all direct {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#directArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> directArenas() {
        return directArenaMetrics;
    }

    /**
     * Return the number of thread local caches used by this {@link PooledByteBufAllocator}.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numThreadLocalCaches()}.
     */
    @Deprecated
    public int numThreadLocalCaches() {
        PoolArena<?>[] arenas = heapArenas != null ? heapArenas : directArenas;
        if (arenas == null) {
            return 0;
        }

        int total = 0;
        for (PoolArena<?> arena : arenas) {
            total += arena.numThreadCaches.get();
        }

        return total;
    }

    /**
     * Return the size of the tiny cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#tinyCacheSize()}.
     */
    @Deprecated
    public int tinyCacheSize() {
        return tinyCacheSize;
    }

    /**
     * Return the size of the small cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#smallCacheSize()}.
     */
    @Deprecated
    public int smallCacheSize() {
        return smallCacheSize;
    }

    /**
     * Return the size of the normal cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#normalCacheSize()}.
     */
    @Deprecated
    public int normalCacheSize() {
        return normalCacheSize;
    }

    /**
     * Return the chunk size for an arena.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#chunkSize()}.
     */
    @Deprecated
    public final int chunkSize() {
        return chunkSize;
    }

    final long usedHeapMemory() {
        return usedMemory(heapArenas);
    }

    final long usedDirectMemory() {
        return usedMemory(directArenas);
    }

    private static long usedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numActiveBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    final PoolThreadCache threadCache() {
        PoolThreadCache cache = threadCache.get();
        assert cache != null;
        return cache;
    }

    /**
     * Trim thread local cache for the current {@link Thread}, which will give back any cached memory that was not
     * allocated frequently since the last trim operation.
     * <p>
     * Returns {@code true} if a cache for the current {@link Thread} exists and so was trimmed, false otherwise.
     * <p>
     * 修剪当前 {@link Thread}的线程本地缓存，这将返回自上次修剪操作以来未经常分配的任何缓存内存。
     * </p>
     * <p>
     * 如果当前{@link Thread}的缓存存在且因此被修剪则返回{@code true}，否则返回false
     * </p>
     */
    public boolean trimCurrentThreadCache() {
        PoolThreadCache cache = threadCache.getIfExists();
        if (cache != null) {
            //修剪缓存
            cache.trim();
            return true;
        }
        return false;
    }

    /**
     * Returns the status of the allocator (which contains all metrics) as string. Be aware this may be expensive
     * and so should not called too frequently.
     */
    public String dumpStats() {
        int heapArenasLen = heapArenas == null ? 0 : heapArenas.length;
        StringBuilder buf = new StringBuilder(512)
                .append(heapArenasLen)
                .append(" heap arena(s):")
                .append(StringUtil.NEWLINE);
        if (heapArenasLen > 0) {
            for (PoolArena<byte[]> a : heapArenas) {
                buf.append(a);
            }
        }

        int directArenasLen = directArenas == null ? 0 : directArenas.length;

        buf.append(directArenasLen)
                .append(" direct arena(s):")
                .append(StringUtil.NEWLINE);
        if (directArenasLen > 0) {
            for (PoolArena<ByteBuffer> a : directArenas) {
                buf.append(a);
            }
        }

        return buf.toString();
    }
}
