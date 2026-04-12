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
 * Compute-node and peer-proxy abstractions for the FlowTree workflow
 * orchestration system.
 *
 * <p>This package contains the classes responsible for executing jobs locally
 * and coordinating communication between FlowTree peers:
 * <ul>
 *   <li>{@link io.flowtree.node.Node} — a single compute node that dequeues
 *       {@link io.flowtree.job.Job} instances from a
 *       {@link io.flowtree.node.NodeGroup} and executes them on a dedicated
 *       thread.</li>
 *   <li>{@link io.flowtree.node.NodeGroup} — manages a collection of
 *       {@link io.flowtree.node.Node} instances and the set of
 *       {@link io.almostrealism.db.Query}-capable peer proxies, routing incoming
 *       tasks to the least-active node.</li>
 *   <li>{@link io.flowtree.node.Client} — wraps a {@link io.flowtree.Server}
 *       together with user credentials and output-server connection details,
 *       providing a convenient client-side entry point.</li>
 *   <li>{@link io.flowtree.node.Proxy} — interface for multiplexed object
 *       streams that allow several logical channels to share a single socket
 *       connection.</li>
 * </ul>
 */
package io.flowtree.node;
