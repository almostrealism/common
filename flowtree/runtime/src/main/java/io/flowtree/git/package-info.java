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
 * The git concepts the runtime job implementations build on:
 * {@link io.flowtree.git.MergeSides}, what each side of an in-progress merge
 * changed, and {@link io.flowtree.git.CommitTrailer}, a commit-message trailer
 * only the harness may write.
 *
 * <p>The dependency-free part of this package (the git subprocess wrapper,
 * repository URLs, transient git failures) lives in {@code flowtree/base}.
 * Job orchestration that happens to use git stays in
 * {@code io.flowtree.jobs}.</p>
 *
 * @author Michael Murray
 */
package io.flowtree.git;
