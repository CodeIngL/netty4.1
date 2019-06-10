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

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AbstractConstant;
import io.netty.util.ConstantPool;

import java.net.InetAddress;
import java.net.NetworkInterface;

/**
 * A {@link ChannelOption} allows to configure a {@link ChannelConfig} in a type-safe
 * way. Which {@link ChannelOption} is supported depends on the actual implementation
 * of {@link ChannelConfig} and may depend on the nature of the transport it belongs
 * to.
 *
 * @param <T> the type of the value which is valid for the {@link ChannelOption}
 */
public class ChannelOption<T> extends AbstractConstant<ChannelOption<T>> {

    private static final ConstantPool<ChannelOption<Object>> pool = new ConstantPool<ChannelOption<Object>>() {
        @Override
        protected ChannelOption<Object> newConstant(int id, String name) {
            return new ChannelOption<Object>(id, name);
        }
    };

    /**
     * Returns the {@link ChannelOption} of the specified name.
     */
    @SuppressWarnings("unchecked")
    public static <T> ChannelOption<T> valueOf(String name) {
        return (ChannelOption<T>) pool.valueOf(name);
    }

    /**
     * Shortcut of {@link #valueOf(String) valueOf(firstNameComponent.getName() + "#" + secondNameComponent)}.
     */
    @SuppressWarnings("unchecked")
    public static <T> ChannelOption<T> valueOf(Class<?> firstNameComponent, String secondNameComponent) {
        return (ChannelOption<T>) pool.valueOf(firstNameComponent, secondNameComponent);
    }

    /**
     * Returns {@code true} if a {@link ChannelOption} exists for the given {@code name}.
     */
    public static boolean exists(String name) {
        return pool.exists(name);
    }

    /**
     * Creates a new {@link ChannelOption} for the given {@code name} or fail with an
     * {@link IllegalArgumentException} if a {@link ChannelOption} for the given {@code name} exists.
     *
     * @deprecated use {@link #valueOf(String)}.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static <T> ChannelOption<T> newInstance(String name) {
        return (ChannelOption<T>) pool.newInstance(name);
    }

    public static final ChannelOption<ByteBufAllocator> ALLOCATOR = valueOf("ALLOCATOR");
    public static final ChannelOption<RecvByteBufAllocator> RCVBUF_ALLOCATOR = valueOf("RCVBUF_ALLOCATOR");
    public static final ChannelOption<MessageSizeEstimator> MESSAGE_SIZE_ESTIMATOR = valueOf("MESSAGE_SIZE_ESTIMATOR");

    public static final ChannelOption<Integer> CONNECT_TIMEOUT_MILLIS = valueOf("CONNECT_TIMEOUT_MILLIS");
    /**
     * @deprecated Use {@link MaxMessagesRecvByteBufAllocator}
     * and {@link MaxMessagesRecvByteBufAllocator#maxMessagesPerRead(int)}.
     */
    @Deprecated
    public static final ChannelOption<Integer> MAX_MESSAGES_PER_READ = valueOf("MAX_MESSAGES_PER_READ");
    public static final ChannelOption<Integer> WRITE_SPIN_COUNT = valueOf("WRITE_SPIN_COUNT");
    /**
     * @deprecated Use {@link #WRITE_BUFFER_WATER_MARK}
     */
    @Deprecated
    public static final ChannelOption<Integer> WRITE_BUFFER_HIGH_WATER_MARK = valueOf("WRITE_BUFFER_HIGH_WATER_MARK");
    /**
     * @deprecated Use {@link #WRITE_BUFFER_WATER_MARK}
     */
    @Deprecated
    public static final ChannelOption<Integer> WRITE_BUFFER_LOW_WATER_MARK = valueOf("WRITE_BUFFER_LOW_WATER_MARK");
    public static final ChannelOption<WriteBufferWaterMark> WRITE_BUFFER_WATER_MARK =
            valueOf("WRITE_BUFFER_WATER_MARK");

    public static final ChannelOption<Boolean> ALLOW_HALF_CLOSURE = valueOf("ALLOW_HALF_CLOSURE");
    public static final ChannelOption<Boolean> AUTO_READ = valueOf("AUTO_READ");

    /**
     * If {@code true} then the {@link Channel} is closed automatically and immediately on write failure.
     * The default value is {@code true}.
     * <p>
     * 如果{@code true}，那么{@link Channel}将自动关闭，并在写入失败时立即关闭。 默认值为{@code true}。
     */
    public static final ChannelOption<Boolean> AUTO_CLOSE = valueOf("AUTO_CLOSE");

    /**
     * 本选项开启或禁止进程发送广播消息的能力；只有数据报套接字支持广播，并且还必须是在支持广播消息的网络上；
     */
    public static final ChannelOption<Boolean> SO_BROADCAST = valueOf("SO_BROADCAST");
    /**
     * 给一个tcp套接字设置保持存活选项后，如果2小时内在该套接字的任一方向上没有数据交换，tcp就自动给对端发送一个保持存活探测分节。这是一个对端必须响应的tcp分节，它会导致以下三种情况之一；
     * <p>
     * --对端以期望的ack响应。应用进程得不到通知。在又经过无动静的2小时之后，tcp将发出另外一个探测分节；
     * <p>
     * --对端以rst响应，它告知本端tcp，对端已崩溃且重新启动。该套接字的待处理错误被置ECONNRESET，套接字本身则被关闭；
     * <p>
     * --对端对保持存活探测分节没有任何响应。源自Berkeley的tcp将另外发送8个探测分节，两两相隔75秒，视图得到一个响应。如果探测分节没有响应，该套接字的处理错误被置为ETIMEOUT；
     */
    public static final ChannelOption<Boolean> SO_KEEPALIVE = valueOf("SO_KEEPALIVE");
    /**
     * SO_SNDBUF,SO_RCVBUF
     * 每个套接字都有一个发送缓冲区和一个接收缓冲区；TCP套接字的缓冲区大小至少应该是MSS的4倍；MSS=MTU-40头部，一般以太网卡MTU是1500；典型缓冲区默认大小是8192字节或者更大；对于一次发送大量数据，可以增加到48K，64K等；为了达到最佳性能，缓冲区可能至少要与BDP(带宽延迟乘积)一样大小；对于接收大量数据的，提高接收缓冲区能够减少发送端的阻塞；
     * <p>
     * TCP设置这个两个选项注意顺序：对于客户端必须在调用connect之前，对于服务器端应该在调用listen之前，因为窗口选项是在建立连接时用syn分节与对端互换得到的；
     */
    public static final ChannelOption<Integer> SO_SNDBUF = valueOf("SO_SNDBUF");
    public static final ChannelOption<Integer> SO_RCVBUF = valueOf("SO_RCVBUF");

    /**
     * TCP先调用close()的一方会进入TIME_WAIT状态，只有设置了SO_REUSEADDR选项的socket，才可以重复绑定使用。server程序总是应该在调用bind之前设置SO_REUSEADDR套接字选项。
     * <p>
     * 这个套接字选项通知内核，如果端口忙，但TCP状态位于 TIME_WAIT ，可以重用端口。如果端口忙，而TCP状态位于其他状态，重用端口时依旧得到一个错误信息，指明"地址已经使用中"。如果你的服务程序停止后想立即重启，而新套接字依旧使用同一端口，此时SO_REUSEADDR 选项非常有用。必须意识到，此时任何非期望数据到达，都可能导致服务程序反应混乱，不过这只是一种可能，事实上很不可能
     */
    public static final ChannelOption<Boolean> SO_REUSEADDR = valueOf("SO_REUSEADDR");
    /**
     * --shutdown, SHUT_RD: 在套接字上不能再发出接收请求，进程仍可以网套接字发送时数据，套接字接收缓冲区中所有数据被丢弃；再接收到任何的tcp丢弃；对套接字发送缓冲区没有任何影响；
     * <p>
     * --shutdown, SHUT_WR: 在套接字上不能再发出发送请求，进程仍可以从套接字接收数据，套接字发送缓冲区中的内容被发送到对端，后跟正常的tcp连接终止序列，即发送fin，对套接字接收缓冲区没有任何影响；
     * <p>
     * --close，l_onoff=0: 在套接字上不能再发出发送或者接收请求；套接字发送缓冲区中的内容被发送到对端，如果描述符引用计数变为0，在发送完发送缓冲区中的数据后，跟以正常的tcp连接终止序列，套接字接收缓冲区中的内容被丢弃；
     * <p>
     * --close，l_onoff = 1, l_linger = 0: 在套接字上不能再发出发送或接收请求；如果描述符引用计数变为0，rst被发送到对端；连接状态被置为CLOSED(没有TIME_WAIT)；套接字发送缓冲区和套接字接收缓冲区中的数据被丢弃；
     * <p>
     * --close, l_onoff = 1, l_linger != 0: 在套接字上不能再发出发送或者接收请求；套接字发送缓冲区的数据被发送到对端；如果描述符引用计数为0，在发送完缓冲区中的数据后，跟以正常的tcp连接终止序列；套接字接收缓冲区的数据被丢弃；如果在连接变为CLOSED状态前延滞时间到，那么colose返回EWOULDBLOCK错误；
     */
    public static final ChannelOption<Integer> SO_LINGER = valueOf("SO_LINGER");

    /**
     * backlog。这个参数经常被描述为，新连接队列的长度限制
     */
    public static final ChannelOption<Integer> SO_BACKLOG = valueOf("SO_BACKLOG");
    /**
     * 这个Socket选项在前面已经讨论过。可以通过这个选项来设置读取数据超时。当输入流的read方法被阻塞时，如果设置timeout（timeout的单位是毫秒），那么系统在等待了timeout毫秒后会抛出一个InterruptedIOException例外。在抛出例外后，输入流并未关闭，你可以继续通过read方法读取数据。
     * <p>
     * 如果将timeout设为0，就意味着read将会无限等待下去，直到服务端程序关闭这个Socket.这也是timeout的默认值。如下面的语句将读取数据超时设为30秒：
     */
    public static final ChannelOption<Integer> SO_TIMEOUT = valueOf("SO_TIMEOUT");

    /**
     * IP_TOS设置或接收随源自此套接字的每个IP数据包一起发送的服务类型（TOS）字段。 它用于优先处理网络上的数据包。 TOS是一个字节。 定义了一些标准TOS标志：IPTOS_LOWDELAY以最小化交互式流量的延迟，
     */
    public static final ChannelOption<Integer> IP_TOS = valueOf("IP_TOS");
    /**
     *
     */
    public static final ChannelOption<InetAddress> IP_MULTICAST_ADDR = valueOf("IP_MULTICAST_ADDR");
    /**
     *
     */
    public static final ChannelOption<NetworkInterface> IP_MULTICAST_IF = valueOf("IP_MULTICAST_IF");
    /**
     *
     */
    public static final ChannelOption<Integer> IP_MULTICAST_TTL = valueOf("IP_MULTICAST_TTL");
    /**
     *
     */
    public static final ChannelOption<Boolean> IP_MULTICAST_LOOP_DISABLED = valueOf("IP_MULTICAST_LOOP_DISABLED");

    /**
     * 开启本选项将禁止TCP的Nagle算法，默认情况下Nagle算法是启动的，算法目的减少小分组的发送，减少传递
     */
    public static final ChannelOption<Boolean> TCP_NODELAY = valueOf("TCP_NODELAY");

    @Deprecated
    public static final ChannelOption<Boolean> DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION =
            valueOf("DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION");

    //  Netty参数，单线程执行ChannelPipeline中的事件，默认值为True。
    // 该值控制执行ChannelPipeline中执行ChannelHandler的线程。如果为true，
    // 整个pipeline由一个线程执行，这样不需要进行线程切换以及线程同步，是Netty4的推荐做法；如果为False，ChannelHandler中的处理过程会由Group中的不同线程执行。
    public static final ChannelOption<Boolean> SINGLE_EVENTEXECUTOR_PER_GROUP =
            valueOf("SINGLE_EVENTEXECUTOR_PER_GROUP");

    /**
     * Creates a new {@link ChannelOption} with the specified unique {@code name}.
     */
    private ChannelOption(int id, String name) {
        super(id, name);
    }

    @Deprecated
    protected ChannelOption(String name) {
        this(pool.nextId(), name);
    }

    /**
     * Validate the value which is set for the {@link ChannelOption}. Sub-classes
     * may override this for special checks.
     */
    public void validate(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }
}
