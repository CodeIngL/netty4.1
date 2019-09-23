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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 * <p>
 * 该类是Bootstrap的子类可以轻易的对ServerChannel进行引导（引导辅助类）
 * <p>
 * 任何返回this（ServerBootstrap）提供了对ServerBootstrap的相关配置
 * <p>
 * 对于ServerBootstrap来说，用户最为核心的操作是开启网络的监听，即所谓的绑定。
 *
 * @see #bind()
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    //子通道配置项
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    //子通道配置属性
    private final Map<AttributeKey<?>, Object> childAttrs = new LinkedHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    //对于io事件循环，该引导类往往有一个监听事件循环和对应其衍生出来的事件循环我们称之为子事件循环
    private volatile EventLoopGroup childGroup;
    //对于io事件循环，该引导类往往本身从父类继承时，完成了本级的通道处理器赋值，由于子事件的存在，自然有子通道处理器。
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() {
    }

    /**
     * 从一个引导类进行copy
     *
     * @param bootstrap
     */
    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        synchronized (bootstrap.childAttrs) {
            childAttrs.putAll(bootstrap.childAttrs);
        }
    }


    //------------------------------以类builder的方式构建ServerBootstrap的配置---------------------------//
    //---------------------------------------------------------------------------------------------------//
    //---------------------------------------------------------------------------------------------------//
    //---------------------------------------------------------------------------------------------------//

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     * <p>
     * 指定的EventLoopGroup用于本身通道（socket监听者)和子通道（socket接收者），
     * 使用该方法指定父子层级共用一个事件循环组
     *
     * @see #group(EventLoopGroup, EventLoopGroup)
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     * <p>
     * 指定的EventLoopGroup用于本身通道（socket监听者)和子通道（socket接收者），
     * 这些事件循环器被用来处理所有的事件和socketChannel以及Channel的Io事件两个层级各自分开
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        //设置本层级事件循环Group
        super.group(parentGroup);
        if (childGroup == null) {
            throw new NullPointerException("childGroup");
        }
        //设置子层级事件循环Group
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = childGroup;
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     * <p>
     * 为子层级的chennel指定相关的option，
     * 允许使用指定的ChannelOption（用于Channel实体），当获得channel的时候（一般在接收者接收channel的时候创建）。使用null值来移除先前
     * 的设定
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        if (childOption == null) {
            throw new NullPointerException("childOption");
        }
        if (value == null) {
            synchronized (childOptions) {
                childOptions.remove(childOption);
            }
        } else {
            synchronized (childOptions) {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     * 允许使用指定的AttributeKey（用于Channel实体），使用null值来移除先前
     * 的设定
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        if (childKey == null) {
            throw new NullPointerException("childKey");
        }
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     * 设置ChannelHandler来为服务请求Channel的服务
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        if (childHandler == null) {
            throw new NullPointerException("childHandler");
        }
        this.childHandler = childHandler;
        return this;
    }

    //------------------------------其他方法---------------------------//

    /**
     * 本级进行初始化channel，这个初始化动作是为了完成channel的某些关系或者属性的设置，而不是新建（构造实例)
     *
     * @param channel
     * @throws Exception
     */
    @Override
    void init(Channel channel) throws Exception {
        //获得配置
        final Map<ChannelOption<?>, Object> options = options0();
        synchronized (options) {
            //对配置类进行配置
            setChannelOptions(channel, options, logger);
        }

        //获得属性
        final Map<AttributeKey<?>, Object> attrs = attrs0();
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e : attrs.entrySet()) {
                @SuppressWarnings("unchecked")
                AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
                //进行属性的设置
                channel.attr(key).set(e.getValue());
            }
        }

        //获得pipeline
        ChannelPipeline p = channel.pipeline();

        //子事件循环器
        final EventLoopGroup currentChildGroup = childGroup;
        //子事件处理器
        final ChannelHandler currentChildHandler = childHandler;
        //当前的子配置项
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        //当前的子配置属性
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs;
        //进行复制
        synchronized (childOptions) {
            currentChildOptions = childOptions.entrySet().toArray(newOptionArray(0));
        }
        //进行复制
        synchronized (childAttrs) {
            currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(0));
        }

        //添加一层抽象
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        //向管道添加ServerBootstrapAcceptor，对于本级来说，从职责上定位是一个监听性质的事件循环，
                        //对应事件道来，需要产生或者传递给子事件循环，子事件的相关配置，这里就能够被带下去。
                        pipeline.addLast(new ServerBootstrapAcceptor(ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private static Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Map.Entry[size];
    }


    //-----------------------bootstrap数据流入栈处理handler，refactor产生子channel核心连接--------------//

    /**
     * 这是一个非常重要的类，负责OP_ACCEPT的处理的核心处理
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        //子事件循环组
        private final EventLoopGroup childGroup;
        //子处理器
        private final ChannelHandler childHandler;
        //子options
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        //子attrs
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        //是否开启自动read功能
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            // 计划重新启用auto-read的任务。 在我们尝试提交之前创建此Runnable非常重要，否则URLClassLoader可能无法加载该类，因为它已经达到了文件限制。
            //
            // 请参阅https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * ServerBootstrap的辅助类，用于帮助SocketServer的处理，
         * msg是子Channel对于nio来说是NioSocketChannel，
         * 其绑定了产生的他的Channel（NioServerSocketChannel）和与其绑定的Socket
         *
         * @param ctx
         * @param msg
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            //获得普通的Channel，监听处理连接消息并产生一个代表连接的Channel
            final Channel child = (Channel) msg;

            //为消息channel添加添处理器
            child.pipeline().addLast(childHandler);

            //为消息channel设置配置项
            setChannelOptions(child, childOptions, logger);

            //为消息channel设置配置属性
            for (Entry<AttributeKey<?>, Object> e : childAttrs) {
                child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }

            try {
                //将消息channel注册到子的事件循环组中，并添加相应的监听器，用于关闭该channel
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                // 停止接受新连接1秒钟以允许通道恢复
                // 请参阅https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }


    //-----------------------------------基本的get和clone----------------------------//

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        return copiedMap(childOptions);
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
