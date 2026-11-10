/*
 * Copyright 2026 Michael Murray
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
 * FlowTree's use of git: running git, reading a repository's state, and the
 * concepts that describe it, independent of the jobs that drive it.
 *
 * <p>This portion of {@code io.flowtree.git} holds what has no flowtree
 * runtime dependencies, so the agent runners in {@code flowtree/agents} can
 * use it too: the {@link io.flowtree.git.GitOperations} subprocess wrapper,
 * {@link io.flowtree.git.RepositoryUrl}, and
 * {@link io.flowtree.git.TransientGitException}. The git concepts the runtime
 * job implementations build on, such as merge analysis and harness-owned
 * commit trailers, live in the same package in {@code flowtree/runtime}.</p>
 *
 * @author Michael Murray
 */
package io.flowtree.git;
