/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * Concurrency utilities for hardware-accelerated task execution.
 *
 * <p>This package provides specialized thread pool and task management
 * infrastructure for hardware workloads. The key class is
 * {@link org.almostrealism.concurrent.SuspendableThreadPoolExecutor}, which
 * extends {@link java.util.concurrent.ThreadPoolExecutor} with priority-based
 * suspension and resumption of tasks to coordinate with kernel execution.</p>
 */
package org.almostrealism.concurrent;
