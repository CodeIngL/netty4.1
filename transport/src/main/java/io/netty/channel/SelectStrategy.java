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

import io.netty.util.IntSupplier;

/**
 * Select strategy interface.
 *
 * Provides the ability to control the behavior of the select loop. For example a blocking select
 * operation can be delayed or skipped entirely if there are events to process immediately.
 */
public interface SelectStrategy {

    /**
     * Indicates a blocking select should follow.
     * 表示应该遵循阻塞选择。
     */
    int SELECT = -1;
    /**
     * Indicates the IO loop should be retried, no blocking select to follow directly.
     * 表示应重试IO循环，紧紧跟着的是非阻塞的select。
     */
    int CONTINUE = -2;
    /**
     * Indicates the IO loop to poll for new events without blocking.
     * 表示要在不阻塞的情况下轮询新事件的IO循环
     */
    int BUSY_WAIT = -3;

    /**
     * The {@link SelectStrategy} can be used to steer the outcome of a potential select
     * call.
     *
     * @param selectSupplier The supplier with the result of a select result.
     * @param hasTasks true if tasks are waiting to be processed.
     * @return {@link #SELECT} if the next step should be blocking select {@link #CONTINUE} if
     *         the next step should be to not select but rather jump back to the IO loop and try
     *         again. Any value >= 0 is treated as an indicator that work needs to be done.
     *         {@link #SELECT}如果下一步应该阻止SELECT {@link #CONTINUE}，如果下一步应该是不选择，而是跳回IO循环再试一次。 任何值> = 0都被视为需要完成工作的指标。
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;
}
