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
 * Memory management, allocation, and lifecycle tracking for hardware-accelerated operations.
 *
 * <p>This package is the core memory layer for the Almost Realism hardware backend.
 * It provides:</p>
 * <ul>
 *   <li>{@link org.almostrealism.hardware.mem.Bytes} - the primary memory container</li>
 *   <li>{@link org.almostrealism.hardware.mem.RAM} - base class for native memory allocations</li>
 *   <li>{@link org.almostrealism.hardware.mem.DirectMemory} - native allocation exposing a {@link java.nio.ByteBuffer} view for ingest</li>
 *   <li>{@link org.almostrealism.hardware.mem.FileMapping} - shared read-only mapping of one
 *       file, ref-counted across every reader; a store holding many ranges of a file maps
 *       the file once rather than once per range, and the mapping is released when the
 *       last reader drops it</li>
 *   <li>{@link org.almostrealism.hardware.mem.HardwareMemoryProvider} - GC-integrated provider;
 *       consults {@link org.almostrealism.hardware.mem.KernelMemoryGuard} before releasing
 *       a block and holds the release back while a kernel is still reading it</li>
 *   <li>{@link org.almostrealism.hardware.mem.MemoryDataAdapter} - abstract MemoryData base</li>
 *   <li>{@link org.almostrealism.hardware.mem.MemoryReplacementManager} - cross-provider transfer</li>
 *   <li>{@link org.almostrealism.hardware.mem.KernelMemoryGuard} - reference-counts
 *       addresses held by in-flight kernels and exposes a {@code Reservation} carrying
 *       what {@code acquire} counted, so {@code release} can give the addresses back
 *       even after their original arguments have been destroyed</li>
 *   <li>{@link org.almostrealism.hardware.mem.ByteBufferTransfer} - precision-aware buffer-to-buffer transfer used by system-boundary ingest</li>
 * </ul>
 *
 * <p>The host-accessible {@link org.almostrealism.hardware.mem.RAM} implementations are not here:
 * they live in {@link org.almostrealism.nio}, which holds {@code NativeBuffer} (a direct
 * {@link java.nio.ByteBuffer}, private or shared-memory backed), {@code NativeMemory} (a JNI
 * {@code malloc} pointer) and the provider that manages both. Look there before concluding that
 * a buffer cannot back a collection.</p>
 *
 * <p>Two routes bring values from outside the process into device memory, and they are not
 * interchangeable. <b>Staging</b> allocates from a provider and copies in, converting precision
 * through {@link org.almostrealism.hardware.mem.ByteBufferTransfer}. <b>Reference</b> implements
 * {@link io.almostrealism.code.Memory} over the source so nothing is materialized on the host and
 * nothing reaches a device until a kernel first requires it; migration keys off
 * {@link org.almostrealism.hardware.MemoryData#isReadOnly()} and is not specific to any one
 * provider. See the hardware module README for which to choose.</p>
 */
package org.almostrealism.hardware.mem;
