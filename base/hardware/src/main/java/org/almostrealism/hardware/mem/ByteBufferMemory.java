/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.mem;

import java.nio.ByteBuffer;

/**
 * A {@link RAM} whose contents are directly accessible to the host as a {@link ByteBuffer}.
 *
 * <p>This type exists so that a consumer can discover the capability through the type
 * hierarchy — {@code mem instanceof ByteBufferMemory} — rather than by calling a method
 * that returns {@code null} on the {@link RAM} instances that do not support it. Backends
 * that can move data directly against host memory (for example an OpenCL provider issuing a
 * bulk {@code clEnqueueReadBuffer}/{@code clEnqueueWriteBuffer}) key their fast path off this
 * type, and fall back to array-mediated transfers for any other {@link RAM}.</p>
 *
 * <p>Declaring the capability here — in the shared {@code org.almostrealism.hardware.mem}
 * package alongside {@link RAM} — rather than on a concrete implementation keeps consumers
 * decoupled from the module that provides the backing memory, so an implementation can later
 * move to its own module without every consumer depending on it.</p>
 *
 * @see RAM
 */
public abstract class ByteBufferMemory extends RAM {
	/**
	 * Returns a direct {@link ByteBuffer} over this memory's contents.
	 *
	 * <p>The buffer shares storage with the memory — it is a view, not a copy — and holds the
	 * raw bytes in the backing element format, with no conversion. It remains valid only while
	 * this memory is allocated, so a caller must keep this instance strongly reachable for as
	 * long as the buffer is in use.</p>
	 *
	 * @return a direct buffer over this memory's contents
	 */
	public abstract ByteBuffer getByteBuffer();
}
