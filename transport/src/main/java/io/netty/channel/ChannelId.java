/*
 * Copyright 2013 The Netty Project
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

import java.io.Serializable;

/**
 * Represents the globally unique identifier of a {@link Channel}.
 * <p>
 * The identifier is generated from various sources listed in the following:
 * <ul>
 * <li>MAC address (EUI-48 or EUI-64) or the network adapter, preferably a globally unique one,</li>
 * <li>the current process ID,</li>
 * <li>{@link System#currentTimeMillis()},</li>
 * <li>{@link System#nanoTime()},</li>
 * <li>a random 32-bit integer, and</li>
 * <li>a sequentially incremented 32-bit integer.</li>
 * </ul>
 * </p>
 * <p>
 * The global uniqueness of the generated identifier mostly depends on the MAC address and the current process ID,
 * which are auto-detected at the class-loading time in best-effort manner.  If all attempts to acquire them fail,
 * a warning message is logged, and random values will be used instead.  Alternatively, you can specify them manually
 * via system properties:
 * <ul>
 * <li>{@code io.netty.machineId} - hexadecimal representation of 48 (or 64) bit integer,
 *     optionally separated by colon or hyphen.</li>
 * <li>{@code io.netty.processId} - an integer between 0 and 65535</li>
 * </ul>
 * </p>
 * <p>
 *     表示频道的全局唯一标识符。
 * 标识符是从以下列出的各种来源生成的：
 * MAC地址（EUI-48或EUI-64）或网络适配器，最好是全局唯一的，
 * 当前的进程ID，
 * System.currentTimeMillis（），
 * System.nanoTime（），
 * 一个随机的32位整数，以及
 * 顺序递增的32位整数。
 * 生成的标识符的全局唯一性主要取决于MAC地址和当前进程ID，它们是在类加载时以尽力而为的方式自动检测到的。 如果所有尝试获取它们的尝试均失败，则会记录一条警告消息，并使用随机值代替。 另外，您可以通过系统属性手动指定它们：
 * </p>
 * <p></p>
 * <p></p>
 *
 */
public interface ChannelId extends Serializable, Comparable<ChannelId> {
    /**
     * Returns the short but globally non-unique string representation of the {@link ChannelId}.
     */
    String asShortText();

    /**
     * Returns the long yet globally unique string representation of the {@link ChannelId}.
     */
    String asLongText();
}
