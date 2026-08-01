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
 *   <li>{@link org.almostrealism.hardware.mem.HardwareMemoryProvider} - GC-integrated provider</li>
 *   <li>{@link org.almostrealism.hardware.mem.MemoryDataAdapter} - abstract MemoryData base</li>
 *   <li>{@link org.almostrealism.hardware.mem.MemoryReplacementManager} - cross-provider transfer</li>
 *   <li>{@link org.almostrealism.hardware.mem.KernelMemoryGuard} - kernel in-use reference counting</li>
 * </ul>
 */
package org.almostrealism.hardware.mem;
