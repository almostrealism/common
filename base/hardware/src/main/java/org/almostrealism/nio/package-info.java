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
 * Native I/O and direct-buffer memory management for CPU-side hardware operations.
 *
 * <p>This package provides the JNI bridge and memory management infrastructure
 * for CPU-side accelerated computing using native direct buffers. Key components:</p>
 * <ul>
 *   <li>{@link org.almostrealism.nio.NIO} - JNI bridge for shared memory and pointer operations</li>
 *   <li>{@link org.almostrealism.nio.NativeBuffer} - direct buffer RAM implementation</li>
 *   <li>{@link org.almostrealism.nio.NativeBufferMemoryProvider} - provider managing NIO buffers</li>
 *   <li>{@link org.almostrealism.nio.NativeBufferRef} - phantom reference for GC-triggered cleanup</li>
 * </ul>
 */
package org.almostrealism.nio;
