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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 *
 * <p>
 *     {@link RecvByteBufAllocator}自动增加和减少反馈时预测的缓冲区大小。
 * </p>
 * <p>
 *    如果先前的读取完全填充了分配的缓冲区，它会逐渐增加预期的可读字节数。
 *    如果读取操作不能连续两次填充一定量的分配缓冲区，则逐渐减少预期的可读字节数。
 *    否则，它会继续返回相同的预测
 * </p>
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    //默认最小分配的，64B
    static final int DEFAULT_MINIMUM = 64; //64B
    //默认初始化1024
    static final int DEFAULT_INITIAL = 1024; //1KB
    //默认64KB
    static final int DEFAULT_MAXIMUM = 65536; //64KB

    //增加
    private static final int INDEX_INCREMENT = 4;
    //减少
    private static final int INDEX_DECREMENT = 1;

    //规格数组
    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        //[16,512),每16递增，512,2048....Int MAXVALUE
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    /**
     * 处理器
     */
    private final class HandleImpl extends MaxMessageHandle {
        //规格最小Index
        private final int minIndex;
        //规格最大Index
        private final int maxIndex;
        //当前Index
        private int index;
        //下一个接收buffer大小
        private int nextReceiveBufferSize;
        //现在要减
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 如果我们按照我们的要求阅读，我们应该检查是否需要增加下一个猜测的大小。
            // 这有助于在大量数据待处理时更快地进行调整，并且可以避免返回选择器以检查更多数据。
            // 返回选择器可能会增加大数据传输的显着延迟。
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * 根据规格进行记录
         * @param actualReadBytes
         */
        private void record(int actualReadBytes) {
            //如果实际的的读取字节小于减去1索引的位置
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT - 1)]) {
                if (decreaseNow) { //如果是减，使用设置成减一的
                    index = max(index - INDEX_DECREMENT, minIndex);
                    //使用减小的分配
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    //设置false，避免每一次都进行相减操作
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) { //如果大于，尝试调高
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                //避免每一次的相减的操作
                decreaseNow = false;
            }
        }

        /**
         * 分配处理器完成
         */
        @Override
        public void readComplete() {
            //记录数据
            record(totalBytesRead());
        }
    }

    //预期缓冲区大小的包含下限
    private final int minIndex;
    //预期缓冲区大小的包含上限
    private final int maxIndex;
    //没有收到反馈时的初始缓冲区大小
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     * <p>
     *     使用默认参数创建新的预测变量。 使用默认参数时，预期的缓冲区大小从{@code 1024}开始，不会低于{@code 64}，并且不会超过{@code 65536}。
     * </p>
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
