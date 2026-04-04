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
 * Command-line and HTTP interface layer for FlowTree server administration.
 *
 * <p>This package provides two complementary access paths to a running FlowTree
 * server:
 * <ul>
 *   <li><strong>Raw TCP terminal</strong> — {@link io.flowtree.cli.FlowTreeCliServer}
 *       accepts plain-text TCP connections and dispatches a rich set of
 *       {@code ::}-prefixed commands for managing nodes, peers, resources,
 *       job factories, and database connections.  Unrecognised input is
 *       forwarded to an embedded Jython interpreter.</li>
 *   <li><strong>HTTP gateway</strong> — {@link io.flowtree.cli.HttpCommandServer}
 *       accepts HTTP/1.0 GET requests and routes URL-encoded commands to the
 *       same {@code FlowTreeCliServer.runCommand} dispatcher.</li>
 * </ul>
 *
 * <p>The Æsh-based interactive shell ({@link io.flowtree.cli.FlowTreeCli}) is a
 * standalone launcher that registers the {@link io.flowtree.cli.OpenConnection}
 * and {@link io.flowtree.cli.SourcesList} commands for local JDBC pool
 * management. {@link io.flowtree.cli.InstallRepository} clones and builds
 * Git/Mercurial repositories on the local machine.
 *
 * @author  Michael Murray
 */
package io.flowtree.cli;
