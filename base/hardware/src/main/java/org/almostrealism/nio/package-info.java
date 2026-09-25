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
 *   <li>{@link org.almostrealism.nio.NativeBuffer} - direct-buffer RAM implementation, in either
 *       private mode (an allocated direct {@link java.nio.ByteBuffer}) or shared mode (a named
 *       region mapped by the provider). This is the type that lets a {@link java.nio.ByteBuffer}
 *       be the backing memory of a collection rather than the source of a copy into one.</li>
 *   <li>{@link org.almostrealism.nio.NativeMemory} - JNI-malloc RAM implementation</li>
 *   <li>{@link org.almostrealism.nio.NativeBufferRef} - phantom reference for GC-triggered cleanup</li>
 *   <li>{@link org.almostrealism.nio.NativeBufferAllocator} /
 *       {@link org.almostrealism.nio.NativeBufferWriter} - strategies registered with
 *       {@link org.almostrealism.nio.NativeMemoryProvider#registerAdapter} to adapt a foreign
 *       {@link io.almostrealism.code.Memory} into a buffer, as the OpenCL provider does</li>
 * </ul>
 *
 * <p>Two constraints govern what a {@link org.almostrealism.nio.NativeBuffer} can hold, and both
 * are easy to miss. Its typed view is chosen from the <em>provider's</em> precision with no
 * conversion, so a buffer whose element width differs from the provider's must be converted
 * through {@link org.almostrealism.hardware.mem.ByteBufferTransfer} rather than adopted as-is.
 * And only a direct buffer qualifies — a heap {@link java.nio.ByteBuffer#allocate} buffer is
 * rejected outright.</p>
 *
 * <p>For how these fit the wider memory layer, and for the alternative of serving values through a
 * read-only {@link io.almostrealism.code.Memory} implementation instead of copying them, see
 * {@link org.almostrealism.hardware.mem} and the hardware module README.</p>
 */
package org.almostrealism.nio;
