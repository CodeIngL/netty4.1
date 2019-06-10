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
package io.netty.util;

/**
 * A reference-counted object that requires explicit deallocation.
 * <p>
 * When a new {@link ReferenceCounted} is instantiated, it starts with the reference count of {@code 1}.
 * {@link #retain()} increases the reference count, and {@link #release()} decreases the reference count.
 * If the reference count is decreased to {@code 0}, the object will be deallocated explicitly, and accessing
 * the deallocated object will usually result in an access violation.
 * </p>
 * <p>
 * If an object that implements {@link ReferenceCounted} is a container of other objects that implement
 * {@link ReferenceCounted}, the contained objects will also be released via {@link #release()} when the container's
 * reference count becomes 0.
 * </p>
 * <p>
 * 需要显式deallocation的引用计数对象。
 * </p>
 * <p>
 * 实例化新的{@link ReferenceCounted}时，它以引用计数{@code 1}开始.{@link #retain()} 增加引用计数，{@link #release()}减少引用计数。
 * 如果引用计数减少到{@code 0}，则将显式deallocation对象，并且访问解除分配的对象通常会导致访问冲突。
 * </p>
 * <p>
 * 如果实现{@link ReferenceCounted}的对象是实现{@link ReferenceCounted}的其他对象的容器，则当容器的引用计数变为0时，也将通过{@link #release()}释放包含的对象。
 * </p>
 */
public interface ReferenceCounted {
    /**
     * Returns the reference count of this object.  If {@code 0}, it means this object has been deallocated.
     */
    int refCnt();

    /**
     * Increases the reference count by {@code 1}.
     * <p>
     * 通过{@code 1}增加引用计数。
     * </p>
     */
    ReferenceCounted retain();

    /**
     * Increases the reference count by the specified {@code increment}.
     * <p>
     * 通过指定的{@code increment}增加引用计数。
     * </p>
     */
    ReferenceCounted retain(int increment);

    /**
     * Records the current access location of this object for debugging purposes.
     * If this object is determined to be leaked, the information recorded by this operation will be provided to you
     * via {@link ResourceLeakDetector}.  This method is a shortcut to {@link #touch(Object) touch(null)}.
     * <p>
     * 记录此对象的当前访问位置以进行调试。 如果确定此对象已泄露，则此操作记录的信息将通过{@link ResourceLeakDetector}提供给您。 此方法是{@link #touch(Object) touch(null)}快捷方式
     */
    ReferenceCounted touch();

    /**
     * Records the current access location of this object with an additional arbitrary information for debugging
     * purposes.  If this object is determined to be leaked, the information recorded by this operation will be
     * provided to you via {@link ResourceLeakDetector}.
     * <p>
     * 记录此对象的当前访问位置以及用于调试目的的其他任意信息。 如果确定此对象已泄露，则此操作记录的信息将通过{@link ResourceLeakDetector}提供给您。
     */
    ReferenceCounted touch(Object hint);

    /**
     * Decreases the reference count by {@code 1} and deallocates this object if the reference count reaches at
     * {@code 0}.
     * <p>
     * 如果引用计数达到{@code 0}，则将引用计数减{@code 1}并释放此对象。
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release();

    /**
     * Decreases the reference count by the specified {@code decrement} and deallocates this object if the reference
     * count reaches at {@code 0}.
     * <p>
     * 按指定的{@code decrement}减少引用计数，如果引用计数达到{@code 0}，则释放该对象。
     *
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release(int decrement);
}
