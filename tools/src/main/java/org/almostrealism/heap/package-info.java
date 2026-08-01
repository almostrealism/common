/*
 * Copyright 2025 Michael Murray
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
 * Heap dump analysis tools for the AR development toolkit.
 *
 * <p>This package provides a command-line analyzer for JVM heap dumps ({@code .hprof} files),
 * producing JSON-formatted reports useful for diagnosing memory issues such as
 * {@code OutOfMemoryError} in AR workloads:</p>
 *
 * <ul>
 *   <li>{@link org.almostrealism.heap.HeapAnalyzer} — parses HPROF binary files and outputs
 *       class histogram, dominator-tree summary, or combined summary views. Designed to be
 *       invoked from the {@code ar-jmx} MCP tool's heap analysis workflow.</li>
 * </ul>
 */
package org.almostrealism.heap;
