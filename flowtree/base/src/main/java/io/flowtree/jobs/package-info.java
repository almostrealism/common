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
 * Shared job-adjacent utilities that have no flowtree runtime dependencies.
 *
 * <p>This portion of {@code io.flowtree.jobs} contains the low-level git
 * subprocess wrapper used by both runners (in {@code flowtree/agents}) and
 * the runtime job implementations (in {@code flowtree/runtime}). The bulk
 * of {@code io.flowtree.jobs} lives in those higher modules; only the
 * dependency-free helpers live here.</p>
 *
 * @author Michael Murray
 */
package io.flowtree.jobs;
