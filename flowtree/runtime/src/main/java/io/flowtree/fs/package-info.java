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
 * Distributed file-system abstractions for FlowTree.
 *
 * <p>This package implements a peer-to-peer content repository in which
 * byte content is broken into fixed-size chunks and replicated across
 * connected FlowTree nodes. Key participants are:
 * <ul>
 *   <li>{@link io.flowtree.fs.DistributedResource} — a handle to a
 *       distributed file, backed by an in-memory chunk cache, a local
 *       relational database, and peer-node resource servers.</li>
 *   <li>{@link io.flowtree.fs.ResourceDistributionTask} — a
 *       {@link io.flowtree.job.JobFactory} that continuously moves the
 *       least-recently-replicated chunks to peer nodes to balance
 *       availability.</li>
 *   <li>{@link io.flowtree.fs.OutputServer} — the local database server
 *       that stores chunk data and serves queries over a socket.</li>
 *   <li>{@link io.flowtree.fs.ConcatenatedResource} — a virtual resource
 *       whose content is formed by concatenating the contents of a
 *       directory of distributed resources.</li>
 *   <li>{@link io.flowtree.fs.ImageResource} — a specialised resource for
 *       transferring pixel data across a network connection.</li>
 * </ul>
 *
 * @author  Michael Murray
 */
package io.flowtree.fs;