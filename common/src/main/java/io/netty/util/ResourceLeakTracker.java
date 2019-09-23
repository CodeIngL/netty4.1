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
package io.netty.util;

/**
 * 资源泄漏追踪器
 * @param <T>
 */
public interface ResourceLeakTracker<T>  {

    /**
     * Records the caller's current stack trace so that the {@link ResourceLeakDetector} can tell where the leaked
     * resource was accessed lastly. This method is a shortcut to {@link #record(Object) record(null)}.
     * <p>
     *     记录调用者的当前堆栈跟踪，以便R{@link ResourceLeakDetector} 可以告知最后访问泄漏资源的位置。 此方法是记录{@link #record(Object) record(null)}的快捷方式。
     * </p>
     */
    void record();

    /**
     * Records the caller's current stack trace and the specified additional arbitrary information
     * so that the {@link ResourceLeakDetector} can tell where the leaked resource was accessed lastly.
     * <p>
     *     记录调用者的当前堆栈跟踪和指定的附加任意信息，以便{@link ResourceLeakDetector} 可以告诉最后访问泄漏资源的位置
     * </p>
     */
    void record(Object hint);

    /**
     * Close the leak so that {@link ResourceLeakTracker} does not warn about leaked resources.
     * After this method is called a leak associated with this ResourceLeakTracker should not be reported.
     * <p>
     *     关闭泄漏，以便{@link ResourceLeakTracker}不会警告泄漏的资源。 调用此方法后，不应报告与此{@link ResourceLeakTracker}关联的泄漏
     * </p>
     *
     * @return {@code true} if called first time, {@code false} if called already
     */
    boolean close(T trackedObject);
}
