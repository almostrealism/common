/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.nio;

import io.almostrealism.code.Memory;

/**
 * Strategy interface for creating a {@link NativeBuffer} from a foreign {@link io.almostrealism.code.Memory} type.
 *
 * <p>Implementations are registered with {@link NativeBufferMemoryProvider} to enable zero-copy or
 * adapted allocation from cross-provider memory sources.</p>
 *
 * @param <T> Foreign memory type to adapt from
 * @see NativeBufferMemoryProvider#registerAdapter(Class, NativeBufferAllocator)
 */
public interface NativeBufferAllocator<T extends Memory> {
	/**
	 * Creates a {@link NativeBuffer} backed by or copied from the given source memory.
	 *
	 * @param src    Source memory to adapt
	 * @param offset Element offset within the source
	 * @param length Number of elements to include
	 * @return A new {@link NativeBuffer}, or null if this allocator cannot handle the source
	 */
	NativeBuffer allocate(T src, int offset, int length);
}
