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

package io.netty.buffer;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.internal.ReferenceCountUpdater;

/**
 * Abstract base class for {@link ByteBuf} implementations that count references.
 * <p>
 *     用于计算引用的{@link ByteBuf}实现的抽象基类
 * </p>
 */
public abstract class AbstractReferenceCountedByteBuf extends AbstractByteBuf {
    private static final long REFCNT_FIELD_OFFSET =
            ReferenceCountUpdater.getUnsafeOffset(AbstractReferenceCountedByteBuf.class, "refCnt");
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> AIF_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    private static final ReferenceCountUpdater<AbstractReferenceCountedByteBuf> updater =
            new ReferenceCountUpdater<AbstractReferenceCountedByteBuf>() {
        @Override
        protected AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> updater() {
            return AIF_UPDATER;
        }
        @Override
        protected long unsafeOffset() {
            return REFCNT_FIELD_OFFSET;
        }
    };

    // Value might not equal "real" reference count, all access should be via the updater
    // 值可能不等于“真实”引用计数，所有访问都应该通过updater来操作这个引用计数
    @SuppressWarnings("unused")
    private volatile int refCnt = updater.initialValue();

    protected AbstractReferenceCountedByteBuf(int maxCapacity) {
        super(maxCapacity);
    }

    @Override
    boolean isAccessible() {
        // Try to do non-volatile read for performance as the ensureAccessible() is racy anyway and only provide
        // a best-effort guard.
        // 尝试执行non-volatile读取以提高性能，因为ensureAccessible()仍然很流行，并且仅提供尽力而为的保护措施。
        return updater.isLiveNonVolatile(this);
    }

    @Override
    public int refCnt() {
        return updater.refCnt(this);
    }

    /**
     * An unsafe operation intended for use by a subclass that sets the reference count of the buffer directly
     */
    protected final void setRefCnt(int refCnt) {
        updater.setRefCnt(this, refCnt);
    }

    /**
     * An unsafe operation intended for use by a subclass that resets the reference count of the buffer to 1
     * <p>
     *     打算由unsafe用的不安全操作，将缓冲区的引用计数重置为1
     * </p>
     */
    protected final void resetRefCnt() {
        updater.resetRefCnt(this);
    }

    @Override
    public ByteBuf retain() {
        return updater.retain(this);
    }

    @Override
    public ByteBuf retain(int increment) {
        return updater.retain(this, increment);
    }

    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return handleRelease(updater.release(this));
    }

    @Override
    public boolean release(int decrement) {
        return handleRelease(updater.release(this, decrement));
    }

    /**
     * 最终释放，解除分配
     * @param result 分配是否释放
     * @return
     */
    private boolean handleRelease(boolean result) {
        if (result) {
            deallocate();
        }
        return result;
    }

    /**
     * Called once {@link #refCnt()} is equals 0.
     * 仅调用一次，当refCnt()的值是0的时候
     */
    protected abstract void deallocate();
}
