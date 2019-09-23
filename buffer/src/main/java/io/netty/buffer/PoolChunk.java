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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 * <p>
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 * <p>
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * <p>
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 * <p>
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 * <p>
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 * <p>
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 * <p>
 * depth=maxOrder is the last level and the leafs consist of pages
 * <p>
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 * <p>
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 * memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 * is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 * <p>
 * As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 * <p>
 * Initialization -
 * In the beginning we construct the memoryMap array by storing the depth of a node at each node
 * i.e., memoryMap[id] = depth_of_id
 * <p>
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 * some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 * is thus marked as unusable
 * <p>
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 * <p>
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 * <p>
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 * note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 * <p>
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 * <p>
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * <p>
 * 来自PoolChunk对PageRun/PoolSubpage分配的算法描述：
 * 以下术语对于理解代码非常重要
 * > page - page是内存chunk的可分配最小单元
 * > chunk - chunk是page的集合
 * > 在此代码中 chunkSize = 2^{maxOrder} * pageSize
 * </p>
 * <p>
 * 开始我们分配一个size=chunkSize的byte数组
 * </p>
 * <p>
 * 每当需要创建给定大小的ByteBuf时，
 * 我们搜索byte数组中有足够空闲空间的第一个位置来容纳请求的size并返回一个（long）句柄，这个句柄对偏移信息进行了编码，
 * （此内存段随后被标记为reserved，因此它总是被一个ByteBuf使用而不再使用）
 * </p>
 * <p>
 * 为简单起见，根据PoolArena＃normalizeCapacity方法对所有大小进行标准化
 * 这个规范确保当我们requestSize>= pageSize的内存段时，normalizedCapacity等于下一个最接近的2的幂次方。
 * </p>
 * <p>
 * 搜索至少具有requestSize的chunk中的第一个偏移量，我们构造一个完整的平衡二叉树并将其存储在一个数组中（就像堆(堆排)一样）-属性memoryMap
 * </p>
 * <p>
 * 这颗树看起来像这样（括号中提到的每个节点的大小）
 * </p>
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 * <p>
 * depth=maxOrder是最下一层和叶子由页面组成
 * <p>
 * 这个树可用在chunkArray中搜索如下翻译：
 * 为了分配一个大小为chunkSize/2^k的内存段，我们搜索高度为k的第一个（左起）未使用的节点
 * <p>
 * 算法：
 * ---------
 * 使用表示法在memoryMap中对树进行编码
 * memoryMap[id]=x => 在以id为根的子树中的可自由分配的第一个节点位于深度x(从深度=0开始计算))
 * 即，在深度区间[depth_of_id，x)中，不存在任何空闲节点
 * <p>
 * 当我们分配和释放节点时，我们更新存储在memoryMap中的值，以便维护该属性
 * <p>
 * <p>
 * 初始化 -
 * 在开始时，我们通过在每个节点本身存储深度来构造memoryMap数组，即memoryMap[id]=depth_of_id
 * <p>
 * 观察：
 * -------------
 * 1）memoryMap[id]=depth_of_id => it是free/unallocated
 * 2）memoryMap[id]>depth_of_id => 至少有一个子节点被分配，所以我们不能分配它，但是它的一些子节点仍然可以根据它们的可用性进行分配
 * 3）memoryMap[id]=maxOrder+1 => 节点已完全分配，因此无法分配任何子节点，因此将其标记为不可用
 * <p>
 * 算法：[allocateNode(d)=>我们希望在高度h找到第一个节点（从左侧）可以分配]
 * ----------
 * 1）从root开始（即，深度=0或id=1）
 * 2）如果memoryMap[1]>d =>无法从此chunk中分配
 * 3）左节点值<= h;我们可以从左子树分配所以向左移动并重复直到找到
 * 4）否则尝试右子树
 * <p>
 * 算法：[allocateRun(size)]
 * ----------
 * 1）计算d=log_2(chunkSize/size)
 * 2）返回allocateNode(d)
 * <p>
 * 算法：[allocateSubpage(size)]
 * ----------
 * 1）使用allocateNode(maxOrder)查找空（即未使用）叶（即page）
 * 2）使用这个handle构造PoolSubpage对象，或者如果它已经存在，只需调用init(normCapacity)，
 * 注意当我们init()时，将这个PoolSubpage对象添加到PoolArena中的subpagesPool
 * <p>
 * 注意：
 * -----
 * 在提高缓存一致性的实现中，
 * 我们在memoryMap和depthMap中分别存储2条信息depth_of_id和x作为两个字节值
 * memoryMap[id]=depth_of_id 如上所述
 * depthMap[id]=x表示可以自由分配的第一个节点位于深度x（来自root）
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;
    final T memory;
    final boolean unpooled;
    final int offset;
    //内存map
    private final byte[] memoryMap;
    //深度map
    private final byte[] depthMap;
    //子页pages。如果有子页会在相应的位置上放置
    private final PoolSubpage<T>[] subpages;
    /**
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     * <p>
     * 用于确定请求的容量是否等于或大于pageSize。pageSize掩码
     * </p>
     */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    //最大可分配子页数
    private final int maxSubpageAllocs;
    /**
     * Used to mark memory as unusable
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    // 用作从内存创建的ByteBuffer的缓存。 这些只是重复，因此只是内存本身的容器。 这些通常需要在Pooled*ByteBuf中进行操作，因此可能会产生额外的GC，这可以通过缓存重复项来大大减少。
    // 如果PoolChunk未被池化，那么这可能为空，因为池中的ByteBuffer实例在这里没有任何意义。
    private final Deque<ByteBuffer> cachedNioBuffers;

    //chunk的空闲内存数量
    private int freeBytes;

    PoolChunkList<T> parent;
    //前向指针
    PoolChunk<T> prev;
    //后向指针
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 构建一个chunk
     *
     * @param arena      构建chunk的arena
     * @param memory     存储的内存，对于heap是byte[]，对于的direct是ByteBuffer，默认分配chunkSize大（16M)
     * @param pageSize   页大小，默认8K
     * @param maxOrder   最大深度，默认11
     * @param pageShifts 页的移位数默认13
     * @param chunkSize  默认16M
     * @param offset     偏移量
     */
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena; //构建chunk的arena
        this.memory = memory; //存储内存
        this.pageSize = pageSize; //页大小
        this.pageShifts = pageShifts; //页移位
        this.maxOrder = maxOrder; //最大深度
        this.chunkSize = chunkSize; //chunkSize
        this.offset = offset; //偏移量
        unusable = (byte) (maxOrder + 1); //不可用的深度
        log2ChunkSize = log2(chunkSize); //log2
        subpageOverflowMask = ~(pageSize - 1); //pageSize掩码
        freeBytes = chunkSize; //空闲的大小，chunkSize 16M

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;//最大可允许分配的子页数，2^maxOrder

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1];// 内存map，
        depthMap = new byte[memoryMap.length];// 深度map
        //初始化
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++d) { // move down the tree one level at a time，一次向下移动一层
            int depth = 1 << d;//深度右移
            for (int p = 0; p < depth; ++p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;// 在每个级别从左到右遍历并将值设置为子树所在的深度
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex++;
            }
        }

        //构建子页数组
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /**
     * Creates a special chunk that is not pooled.
     * <p>
     * 创建一个不池化的特殊块。
     * </p>
     */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    /**
     * 返回块的当前使用百分比。
     *
     * @return
     */
    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    /**
     * 使用率
     * 使用率/chunk大小
     *
     * @param freeBytes
     * @return
     */
    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * chunk进行分配
     *
     * @param buf          待分配的buf
     * @param reqCapacity  请求的容量
     * @param normCapacity 请求的容量，对应的规范容量
     * @return
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        //代表了分配内存的句柄信息，使用一个long来表征
        final long handle;

        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            //也就是普通容量规格的请求，即大于一页，使用run分配，直接在内存块的位置
            handle = allocateRun(normCapacity);
        } else {
            //使用子页分配，也就是较小的规格的请求
            handle = allocateSubpage(normCapacity);
        }

        //句柄非法，分配失败
        if (handle < 0) {
            return false;
        }

        //缓存一种形式，用于临时分配
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;

        //为buf进行初始化分配
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     * <p>
     * allocate使用的更新方法仅当分配了后继者并且其所有前任需要更新其状态时才会触发此事件。以id为根的子树具有一些可用空间的最小深度
     * </p>
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1; //父Id
            byte val1 = value(id); //左子树值
            byte val2 = value(id ^ 1); //右子树值
            byte val = val1 < val2 ? val1 : val2; //那个小使用那个
            setValue(parentId, val); //更新新值
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     * <p>
     * 当我们在深度d查询空闲节点时，在memoryMap中分配索引的算法
     * <p>
     * a，首先从根节点开始查找，如果根节点的val(memoryMap[id])超过了最大深度,说明没有可用内存分配了，直接返回-1.如果当前节点的val<d,id<<1进行下一层的匹配
     * </p>
     * <p>
     * b，如果当前节点val>d,表示字节点已经被分配过，且剩余空间不够，需要在这一层的其他节点上进行查找。
     * </p>
     * <p>
     * c，分配成功后更新节点为不可用，memoryMap[id] = 12，
     * </p>
     * <p>
     * d，更新父节点
     * </p>
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = -(1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable 超过了最大深度，不可用，返回-1
            return -1;
        }

        //对于深度为d的所有ID，id＆initial==1<<d
        //对于深度小于d的所有ID，id＆initial==0
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d", value, id & initial, d);
        setValue(id, unusable); // mark as unusable
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     * <p>
     * 分配一系列页面（> = 1）
     * </p>
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        //深度
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        int id = allocateNode(d);
        if (id < 0) {
            //分配失败
            return id;
        }
        //内存更新，空闲内存减少
        freeBytes -= runLength(id);
        //返回句柄
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * <p>
     * 创建/初始化normCapacity的新PoolSubpage
     * 这里创建/初始化的任何PoolSubpage都添加到拥有此PoolChunk的PoolArena中的subpage池中
     * </p>
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 获取PoolArena拥有的PoolSubPage池的head并在其上进行同步。
        // 这是必要的，因为我们可以将其添加回来，从而改变链表结构。
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity); //获得容量对应的子页
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves//子页面只能从页面分配，即叶子
        synchronized (head) {
            //获得索引
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            //子页数组
            final PoolSubpage<T>[] subpages = this.subpages;
            //页大小
            final int pageSize = this.pageSize;

            //减去页大小，即使是子页也需要直接减掉
            freeBytes -= pageSize;

            //获得子页
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                //如果没有就构建，并初始化 空的构建，并进行init初始化
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                //初始化
                subpage.init(head, normCapacity);
            }
            //分配
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * <p>
     * 释放一个subpage或一个运行的page
     * <p>
     * 当subpage从PoolSubpage中释放时，它可能会被添加回拥有者(PoolArena)的subpage pool
     * <p>
     * 如果PoolArena中的subpage pool至少有一个给定elemSize的其他PoolSubpage，我们可以完全释放拥有的Page，以便它可用于后续分配
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle); //内存块的地址
        int bitmapIdx = bitmapIdx(handle); //位图的地址，如果存在的话

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // 获取PoolArena拥有的PoolSubPage pool的head并在其上进行同步。 这是需要的，因为我们可能会添加它，因此改变链表结构。
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        //更新算法，重置位置和深度，表示没有被分配
        setValue(memoryMapIdx, depth(memoryMapIdx));
        //向上更新
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /**
     * 初始化
     *
     * @param buf
     * @param nioBuffer
     * @param handle
     * @param reqCapacity
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        //索引
        int memoryMapIdx = memoryMapIdx(handle);
        //索引
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx == 0) {
            //不存在位图信息，是普通的规格分配初始化
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset, reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            //存在位图信息，是较小规格分配存储初始化
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    /**
     * 使用子页来初始化
     *
     * @param buf
     * @param nioBuffer
     * @param handle
     * @param bitmapIdx
     * @param reqCapacity
     */
    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        //buf进行初始化
        buf.init(
                this, nioBuffer, handle,
                runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        // 表示树中节点“id”支持的#bytes大小
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        // 表示从字节数组块开始的#bytes中的从0开始的偏移量
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
