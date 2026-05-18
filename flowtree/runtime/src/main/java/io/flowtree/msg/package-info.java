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
 * Peer-to-peer messaging layer for the FlowTree distributed computing framework.
 *
 * <p>This package contains the three core abstractions that together implement
 * all inter-node communication in a FlowTree cluster:</p>
 *
 * <ul>
 *   <li>{@link io.flowtree.msg.NodeProxy} — the socket-level transport. A single
 *       {@code NodeProxy} manages one TCP {@link java.net.Socket} to a remote server,
 *       handles optional PBE encryption, and dispatches received objects to registered
 *       {@link io.flowtree.msg.NodeProxy.EventListener}s on a dedicated daemon thread.</li>
 *   <li>{@link io.flowtree.msg.Message} — the wire-format envelope. Every exchange
 *       between nodes is encoded as a {@code Message} carrying a typed payload (job
 *       data, connection negotiation, status queries, resource URIs, kill signals,
 *       or ping probes) together with sender and receiver node identifiers.</li>
 *   <li>{@link io.flowtree.msg.Connection} — a logical link between a local
 *       {@link io.flowtree.node.Node} and a specific remote Node. A
 *       {@code Connection} wraps a {@code NodeProxy} and narrows it to a single
 *       remote node ID, providing the higher-level send-job and connection-confirm
 *       operations used by the relay loop.</li>
 * </ul>
 *
 * <p>Typical message flow for job relay:</p>
 * <ol>
 *   <li>A local {@code Node} activity thread calls
 *       {@link io.flowtree.msg.Connection#sendJob(io.flowtree.job.Job)}.</li>
 *   <li>{@code Connection} constructs a {@link io.flowtree.msg.Message} of type
 *       {@link io.flowtree.msg.Message#Job} and delegates to
 *       {@link io.flowtree.msg.NodeProxy#writeObject(Object, int)}.</li>
 *   <li>{@code NodeProxy} serialises the message onto the socket, optionally
 *       encrypting with PBE before writing.</li>
 *   <li>The remote {@code NodeProxy}'s reader thread deserialises the message,
 *       calls {@link io.flowtree.msg.NodeProxy.EventListener#recievedMessage(Message, int)}
 *       on each registered listener, and the remote {@code Connection} adds the job
 *       to its local node's queue.</li>
 * </ol>
 *
 * @author michaelmurray
 */
package io.flowtree.msg;