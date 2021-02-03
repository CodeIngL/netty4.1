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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 * <p>
 *     {@link SingleThreadEventLoop}实现，它将{@link Channel}注册到{@link Selector}，因此在事件循环中对它们进行多路复用。
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded va*lue, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION = //disable_key_set_optimization
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3; //min_premature_selector_returns
    //默认512
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD; //selector_auto_rebuild_threshold

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    // JDK NIO bug的解决方法
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector;
    //原生的selector
    private Selector unwrappedSelector;
    //选中的键集合
    private SelectedSelectionKeySet selectedKeys;

    //提供者
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     * <p>
     *     controls的Boolean确定阻塞的Selector.select是否应该突破其select过程。
     *     在我们的例子中，我们使用带超时的select方法，该方法将在这段时间内select阻塞，除非被waken up。
     * </p>
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;

    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    /**
     * 构造函数
     * @param parent
     * @param executor
     * @param selectorProvider
     * @param strategy
     * @param rejectedExecutionHandler
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        //参数校验
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        //参数校验
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        //提供者
        provider = selectorProvider;
        //selector元组
        final SelectorTuple selectorTuple = openSelector();
        //包装的selector
        selector = selectorTuple.selector;
        //未包装的selector
        unwrappedSelector = selectorTuple.unwrappedSelector;
        //策略
        selectStrategy = strategy;
    }

    /**
     * Selector元组
     * 包装了一组原始的selector和一组包装的selector
     */
    private static final class SelectorTuple {
        //未包装的原生的selector
        final Selector unwrappedSelector;
        //包装的的selector，禁用下和selector一致
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 开启selector的。这里可以优化，重建rebuild的时候可以不用
     * @return
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            //未被包装的原始的selector,用java进行打开
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        //默认false,不进行优化
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        //可能的实现类，进行获得
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        //无法包装，使用原生的选择器实现
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
                // 确保当前的selector实现是我们可以检测的。
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        //底层的selector实现类
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    //获得两个字段之一
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    //获得两个字段之一
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    //有unsafe，进行优化，直接使用unsafe进行调用
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        // 让我们尝试使用sun.misc.Unsafe来替换SelectionKeySet。 这允许我们在没有任何额外标志的情况下在Java9 +中执行此操作。
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            //替换成netty中的SelectedSelectionKeySet
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            //替换成netty中的SelectedSelectionKeySet
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    //无法使用unsafe，我们使用反射进进行操作
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    //替换成netty中的SelectedSelectionKeySet
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    //替换成netty中的SelectedSelectionKeySet
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        //构建元祖
        return new SelectorTuple(unwrappedSelector, new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     * <p>
     *     向该事件循环的选择器注册一个任意的{@link SelectableChannel}，不一定由Netty创建。
     *     一旦注册了指定的{@link SelectableChannel}，当{@link SelectableChannel}准备就绪时，此事件循环将执行指定的任务。
     * </p>
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     * <p>
     *     用新创建的{@link Selector}替换此事件循环的当前{@link Selector}，以解决臭名昭着的epoll 100％CPU错误
     * </p>
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * 重建Selector
     */
    private void rebuildSelector0() {
        //老的selector
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        //只有存在selector我们才进行重建
        if (oldSelector == null) {
            return;
        }

        //打开选择键
        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        // 将所有channels注册到新的选择器。
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            // 是时候关闭旧的selector了，其他都注册到新的selector了
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * nio事件循环
     */
    @Override
    protected void run() {
        for (;;) {
            try {
                try {
                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE://继续
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO 由于NIO不支持忙等待，所以就是SELECT

                    case SelectStrategy.SELECT:
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).
                        //
                        //
                        //
                        //----------------------------------------------------------------------------
                        //----------------------------------------------------------------------------
                        //----------------------------------------------------------------------------
                        // 'wakenUp.compareAndSet(false, true)'总是在调用'selector.wakeup()'之前进行
                        // 调用,这样能减少过多的wake-up（Selector.wakeup() 是一个重量级操作)
                        //
                        // 然而，这样会造成一个竞争条件，竞争条件的出现在于wakeUp过早的进行设置
                        //
                        // 'wakenUp' 在一下的情况下设置为true是太早了
                        // 1) Selector 在 'wakenUp.set(false)' 和
                        //    'selector.select(...)'之间出现唤醒. (BAD)
                        // 2) Selecor 在'selector.select(...)' 和
                        //    'if (wakenUp.get()) { ... }'之间唤醒（OK)
                        //
                        // 如果是第一中情况，'wakenUp' 设置成了true,紧接着'selector.select(...)'将立即唤醒
                        // 直到'wakenUp'在下轮循环中重新被设置成false,'wakenUp.compareAndSet(false, true)' 将会失败，因此
                        // 任何尝试去唤醒Selector的操作都会失败，造成了紧跟着的'selector.select(...)'的调用引起不必要的阻塞
                        //
                        // 为了解决这个问题，在selector.select(...)后，如果wakenUp是true的状态，我们再次立即唤醒selector。
                        // 效率低是因为它唤醒了第一种情况(bad - 需要唤醒)和第二种情况(OK - 不需要唤醒)的选择器。

                        if (wakenUp.get()) {
                            //导致尚未返回的第一个select操作立即返回。
                            //如果另一个线程因为在调用select()或select(long)方法时被阻塞，则调用将立即返回。
                            // 如果当前没有select操作正在进行，那么除非在此期间调用selectNow()方法，否则select()和select(long)的下一次调用将立即返回。
                            // 在任何情况下，该调用返回的值可能都不为零。 select()或select(long)方法的后续调用将像往常一样进行阻塞，除非在调用此期间再次调用此方法。
                            // 在两个连续的select操作之间多次调用此方法与仅调用一次具有相同的效果
                            selector.wakeup();
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                //io事件百分比
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        //处理选择键
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        //运行非其他任务
                        runAllTasks();
                    }
                } else {
                    //io开始时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        //处理选择键
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        //尽可能的运行范围内的任务
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * 处理selectKey
     */
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            //优化的处理手段
            processSelectedKeysOptimized();
        } else {
            //普通的处理手段
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    /**
     * 把SelectedSelectionKeySet实例映射到selector的原生selectedKeys和publicSelectedKeys。
     * @param selectedKeys
     */
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        // 检查集合是否为空，如果是，只要每次都创建一个新的迭代器，即使没有任何处理，也只返回不创建垃圾。
        // 请参阅https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * SelectedSelectionKeySet内部使用两个大小为1024的SelectionKey数组keysA和keysB保存selectedKey
     */
    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // null数组中的条目，以便在Channel关闭后允许GC'ed
            // 请参阅https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            //获得选择键上的channel，对于server，就是serverSocketChannel
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                //处理选择键
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 最终的处理选择键
     * @param k 选择键
     * @param ch 选择键上attach的channel
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        //非法的
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            //如果ch仍然注册到此EventLoop，则仅关闭ch。
            // ch可以从事件循环中取消注册，因此SelectionKey可以作为注销过程的一部分被取消，但是该channel仍然是健康的，不应该关闭。
            //请参阅https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }


        try {
            //获得readyOps掩码
            int readyOps = k.readyOps();
            // 首先是对connect事件的检查

            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // 我们首先需要在尝试触发read(...) 或write(...)之前调用finishConnect（），否则NIO JDK通道实现可能会抛出NotYetConnectedException。
            // 符合read，或者掩码为0
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                //删除OP_CONNECT，否则Selector.select（..）将始终返回而不会阻塞
                //请参阅https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                //尝试完成连接
                unsafe.finishConnect();
            }

            //其次我们观察一下是否出现写事件

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // 首先处理OP_WRITE，因为我们可以编写一些排队的缓冲区，因此可以释放内存。
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 调用forceFlush，一旦没有什么可写的，它也将清除OP_WRITE
                ch.unsafe().forceFlush();
            }

            // 最后我们观察一下read和其他的事件
            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead to a spin loop
            // 同时检查readOps为0以解决可能导致旋转循环的JDK错误
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            //导致尚未返回的第一个select操作立即返回。
            //如果另一个线程因为在调用select()或select(long)方法时被阻塞，则调用将立即返回。
            // 如果当前没有select操作正在进行，那么除非在此期间调用selectNow()方法，否则select()和select(long)的下一次调用将立即返回。
            // 在任何情况下，该调用返回的值可能都不为零。 select()或select(long)方法的后续调用将像往常一样进行阻塞，除非在调用此期间再次调用此方法。
            // 在两个连续的select操作之间多次调用此方法与仅调用一次具有相同的效果
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            /**
             * 选择一组键，其相应的channel已准备好进行I/O操作。
             * 该方法执行非阻塞select操作。 如果自上次select操作后,现在没有可处理的channel，则此方法立即返回零。
             * 调用此方法可清除以前调用wakeup方法的效果。
             */
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            // 如果需要，恢复唤醒状态
            if (wakenUp.get()) {
                //导致尚未返回的第一个select操作立即返回。
                //如果另一个线程因为在调用select()或select(long)方法时被阻塞，则调用将立即返回。
                // 如果当前没有select操作正在进行，那么除非在此期间调用selectNow()方法，否则select()和select(long)的下一次调用将立即返回。
                // 在任何情况下，该调用返回的值可能都不为零。 select()或select(long)方法的后续调用将像往常一样进行阻塞，除非在调用此期间再次调用此方法。
                // 在两个连续的select操作之间多次调用此方法与仅调用一次具有相同的效果
                selector.wakeup();
            }
        }
    }

    /**
     *
     * @param oldWakenUp 旧的状态
     * @throws IOException
     */
    private void select(boolean oldWakenUp) throws IOException {
        //选择器
        Selector selector = this.selector;
        try {
            //select的次数
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();//当前时间
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);//select的DeadLine时间

            for (;;) {
                //超时时间
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                //负数
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                        /**
                         * 选择一组键，其相应的channel已准备好进行I/O操作。
                         * 该方法执行非阻塞select操作。 如果自上次select操作后,现在没有可处理的channel，则此方法立即返回零。
                         * 调用此方法可清除以前调用wakeup方法的效果。
                         */
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                // 如果在wakenUp值为true时提交了任务，则该任务没有机会调用Selector#wakeup。
                // 因此我们需要在执行select操作之前再次检查任务队列。
                // 如果我们不这样做，那么任务可能会被搁置，直到选择操作超时。
                // 如果IdleStateHandler存在于管道中，它可能会被挂起直到空闲超时。
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    /**
                     * 选择一组键，其相应的channel已准备好进行I/O操作。
                     * 该方法执行非阻塞select操作。 如果自上次select操作后,现在没有可处理的channel，则此方法立即返回零。
                     * 调用此方法可清除以前调用wakeup方法的效果。
                     */
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                int selectedKeys = selector.select(timeoutMillis);
                //select数量增长
                selectCnt ++;

                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    // 选择的内容，由用户唤醒或任务队列具有待处理任务。 计划任务已准备好进行处理
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    // 线程被中断所以重置选中的键并中断，这样我们就不会遇到繁忙的循环。
                    // 因为这很可能是用户或其客户端库的处理程序中的错误，我们也会记录它。
                    // 请参阅https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    //超出一次超时时间，我们重置
                    // timeoutMillis elapsed without anything selected.
                    // timeoutMillis已过，未选择任何内容。重置
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 && selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    //selector自动rebuid的阈值，当select数量大于阈值时，我们进行rebuild
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    // 代码存在于一个额外的方法中，以确保该方法不会太大而无法内联，因为此分支不太可能非常频繁地被命中。
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                //过早的返回
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.", selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?", selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        //<p>selector连续多次提前返回。 重建Selector以解决问题。
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        /**
         * 选择一组键，其相应的channel已准备好进行I/O操作。
         * 该方法执行非阻塞select操作。 如果自上次select操作后,现在没有可处理的channel，则此方法立即返回零。
         * 调用此方法可清除以前调用wakeup方法的效果。
         */
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            /**
             * 选择一组键，其相应的channel已准备好进行I/O操作。
             * 该方法执行非阻塞select操作。 如果自上次select操作后,现在没有可处理的channel，则此方法立即返回零。
             * 调用此方法可清除以前调用wakeup方法的效果。
             */
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
