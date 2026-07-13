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
 * Native, host-accessible memory management for CPU-side hardware operations.
 *
 * <p>This package provides the memory providers, RAM types, and runtime-compiled JNI operations
 * for CPU-side native memory. Key components:</p>
 * <ul>
 *   <li>{@link org.almostrealism.nio.NativeMemoryProvider} - provider managing native memory in either
 *       direct-buffer or JNI-malloc mode, and owner of the runtime-compiled JNI operations below</li>
 *   <li>{@link org.almostrealism.nio.NativeBuffer} - direct-buffer RAM implementation</li>
 *   <li>{@link org.almostrealism.nio.NativeMemory} - JNI-malloc RAM implementation</li>
 *   <li>{@link org.almostrealism.nio.NativeBufferRef} - phantom reference for GC-triggered cleanup</li>
 * </ul>
 */
package org.almostrealism.nio;
