/*
 * Copyright 2018 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Top-level FlowTree package containing the core server infrastructure and
 * high-level entry points for the workflow orchestration system.
 *
 * <p>The central class in this package is {@link io.flowtree.Server}, which
 * accepts incoming peer connections over TCP, owns a
 * {@link io.flowtree.node.NodeGroup} of local compute nodes, and provides
 * facilities for distributing {@link io.flowtree.job.JobFactory} tasks across
 * the network.  Supporting classes include:
 * <ul>
 *   <li>{@link io.flowtree.Manager} — lifecycle manager that starts and
 *       coordinates server instances.</li>
 *   <li>{@link io.flowtree.ClaudeCodeClient} — specialised client for
 *       submitting Claude Code agent jobs.</li>
 *   <li>{@link io.flowtree.JsonFieldExtractor} — utility for extracting
 *       typed fields from JSON payloads received over the API.</li>
 * </ul>
 *
 * <p>Sub-packages organise additional concerns:
 * <ul>
 *   <li>{@code io.flowtree.node} — compute node and peer-proxy abstractions.</li>
 *   <li>{@code io.flowtree.fs} — distributed filesystem and resource distribution.</li>
 *   <li>{@code io.flowtree.job} — job and task factory interfaces.</li>
 *   <li>{@code io.flowtree.msg} — wire protocol messages.</li>
 *   <li>{@code io.flowtree.jobs} — built-in job implementations (e.g. Claude Code jobs).</li>
 * </ul>
 */
package io.flowtree;
