/*
 * Copyright 2016 Michael Murray
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
 * Core job API for the FlowTree distributed workflow orchestration system.
 *
 * <p>This package defines the fundamental abstractions for describing, encoding,
 * transmitting, and executing units of work ({@link io.flowtree.job.Job}s) across
 * a network of FlowTree nodes.</p>
 *
 * <h2>Key Abstractions</h2>
 * <ul>
 *   <li>{@link io.flowtree.job.Job} — A single executable unit of work. Jobs are
 *       {@link java.lang.Runnable} and encode their own state as key-value pairs so
 *       they can be transmitted to remote nodes and reconstructed there.</li>
 *   <li>{@link io.flowtree.job.JobFactory} — Produces a queue of {@link io.flowtree.job.Job}s
 *       for a named task. Factories also encode themselves for transmission, allowing a
 *       remote node to reconstruct the factory and continue generating jobs from the same
 *       task.</li>
 *   <li>{@link io.flowtree.job.AbstractJobFactory} — Base implementation of
 *       {@link io.flowtree.job.JobFactory} that handles property storage, label-based
 *       node targeting, priority, and encode/decode plumbing.</li>
 *   <li>{@link io.flowtree.job.HybridJobFactory} — A composite
 *       {@link io.flowtree.job.JobFactory} that aggregates multiple factories into a
 *       single task, completing only when all constituent factories are done.</li>
 *   <li>{@link io.flowtree.job.Output} — Sends {@link org.almostrealism.io.JobOutput}
 *       results to a remote output server over a socket connection.</li>
 * </ul>
 *
 * <h2>Encoding Protocol</h2>
 * <p>Both {@link io.flowtree.job.Job}s and {@link io.flowtree.job.JobFactory} instances
 * are serialized using the {@link org.almostrealism.util.KeyValueStore} encoding format:
 * {@code classname::key0:=value0::key1:=value1...}. This allows any node in the cluster
 * to reconstruct a job or factory from a plain string, without a shared class registry.</p>
 *
 * @author Michael Murray
 */
package io.flowtree.job;