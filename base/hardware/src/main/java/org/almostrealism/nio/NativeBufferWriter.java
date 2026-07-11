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
import org.almostrealism.hardware.mem.ByteBufferMemory;

/**
 * Strategy interface for copying data from a foreign {@link io.almostrealism.code.Memory} type into a
 * host-accessible {@link ByteBufferMemory}.
 *
 * <p>Implementations are registered with {@link NativeMemoryProvider} to handle cross-type
 * memory writes without requiring the caller to know the source memory's concrete type. The
 * destination is addressed through its {@link ByteBufferMemory#getByteBuffer() direct buffer}, so a
 * single implementation serves both the calloc ({@link org.almostrealism.c.NativeMemory}) and NIO
 * ({@link NativeBuffer}) backings.</p>
 *
 * @param <T> Foreign memory type to read from
 * @see NativeMemoryProvider#registerAdapter(Class, NativeBufferWriter)
 */
public interface NativeBufferWriter<T extends Memory> {
	/**
	 * Copies {@code length} elements from {@code source} starting at {@code srcOffset}
	 * into {@code mem} starting at {@code offset}.
	 *
	 * @param mem       Destination host-accessible memory
	 * @param offset    Destination element offset
	 * @param source    Source memory
	 * @param srcOffset Source element offset
	 * @param length    Number of elements to copy
	 */
	void setMem(ByteBufferMemory mem, int offset, T source, int srcOffset, int length);
}
