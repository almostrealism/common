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
 * Pluggable strategy implementations for server-level behaviors in FlowTree.
 *
 * <p>The {@link io.flowtree.behavior.ServerBehavior} interface defines a single
 * {@code behave(Server, PrintStream)} method that encapsulates an action to be
 * performed against a running {@link io.flowtree.Server}.  Implementations can
 * be instantiated by class name and executed from the FlowTree CLI using the
 * {@code ::behave} command.
 *
 * <p>The package currently ships one implementation:
 * {@link io.flowtree.behavior.RandomPeerJoin}, which performs a random walk
 * across the peer graph to join previously unknown nodes and maintain a stable
 * fan-out from the local server.
 *
 * @author  Michael Murray
 */
package io.flowtree.behavior;
