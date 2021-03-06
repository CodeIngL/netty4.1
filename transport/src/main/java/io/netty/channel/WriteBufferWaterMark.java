/*
 * Copyright 2016 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * WriteBufferWaterMark is used to set low water mark and high water mark for the write buffer.
 * <p>
 * If the number of bytes queued in the write buffer exceeds the
 * {@linkplain #high high water mark}, {@link Channel#isWritable()}
 * will start to return {@code false}.
 * <p>
 * If the number of bytes queued in the write buffer exceeds the
 * {@linkplain #high high water mark} and then
 * dropped down below the {@linkplain #low low water mark},
 * {@link Channel#isWritable()} will start to return
 * {@code true} again.
 * <p>
 * WriteBufferWaterMark用于为写入缓冲区设置低水位线和高水位线。
 * </p>
 * <p>
 * 如果写缓冲区中排队的字节数超过高水位线，则{@link Channel#isWritable()}将开始返回{@code false}。
 * </p>
 * <p>
 * 如果写缓冲区中排队的字节数超过高水位线然后降低到低水位线以下，则 {@link Channel#isWritable()}将再次开始返回true。
 * </p>
 * <p>
 * 简单pojo对象，用于描述写的水位情况
 * </p>
 */
public final class WriteBufferWaterMark {

    //默认的低水位掩码，32K
    private static final int DEFAULT_LOW_WATER_MARK = 32 * 1024;
    //默认的高水位掩码，64K
    private static final int DEFAULT_HIGH_WATER_MARK = 64 * 1024;

    //默认水位对象
    public static final WriteBufferWaterMark DEFAULT = new WriteBufferWaterMark(DEFAULT_LOW_WATER_MARK, DEFAULT_HIGH_WATER_MARK, false);

    //低水位阈值
    private final int low;
    //高水位阈值
    private final int high;

    /**
     * Create a new instance.
     *
     * @param low  low water mark for write buffer.
     * @param high high water mark for write buffer
     */
    public WriteBufferWaterMark(int low, int high) {
        this(low, high, true);
    }

    /**
     * This constructor is needed to keep backward-compatibility.
     */
    WriteBufferWaterMark(int low, int high, boolean validate) {
        if (validate) {
            //校验一下传递的参数,不需要进行校验
            checkPositiveOrZero(low, "low");
            if (high < low) {
                throw new IllegalArgumentException("write buffer's high water mark cannot be less than  low water mark (" + low + "): " + high);
            }
        }
        this.low = low;
        this.high = high;
    }

    /**
     * Returns the low water mark for the write buffer.
     */
    public int low() {
        return low;
    }

    /**
     * Returns the high water mark for the write buffer.
     */
    public int high() {
        return high;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(55)
                .append("WriteBufferWaterMark(low: ")
                .append(low)
                .append(", high: ")
                .append(high)
                .append(")");
        return builder.toString();
    }

}
