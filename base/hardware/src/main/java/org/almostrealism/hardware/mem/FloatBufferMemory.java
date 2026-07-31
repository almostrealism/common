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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;

import java.nio.FloatBuffer;

/**
 * Read-only {@link Memory} backed by a java.nio {@link FloatBuffer} — the
 * shape in which external runtimes (ONNX, decoders) hand over their results.
 * Values are served directly from the buffer on each read; nothing reaches a
 * device until the framework migrates this memory at first kernel use.
 *
 * @see FloatBufferMemoryProvider
 */
public class FloatBufferMemory implements Memory {
	/** The provider that manages this memory. */
	private final FloatBufferMemoryProvider provider;

	/** The buffer serving as the backing store; {@code null} once destroyed. */
	private FloatBuffer data;

	/** The number of elements the buffer contains. */
	private final int length;

	/**
	 * Creates memory backed by the given buffer.
	 *
	 * @param provider the managing provider
	 * @param data     the buffer serving as the backing store
	 */
	protected FloatBufferMemory(FloatBufferMemoryProvider provider, FloatBuffer data) {
		this.provider = provider;
		this.data = data;
		this.length = data.capacity();
	}

	/**
	 * Returns the provider that manages this memory.
	 */
	@Override
	public MemoryProvider getProvider() { return provider; }

	/**
	 * Returns the number of elements available from the backing buffer.
	 */
	public int getLength() { return length; }

	/**
	 * Reads the element at the given position from the backing buffer.
	 *
	 * @param index the element position
	 * @return the value at that position
	 * @throws IllegalStateException if the memory has been destroyed
	 */
	protected double valueAt(int index) {
		if (data == null) {
			throw new IllegalStateException("Memory has been destroyed");
		}

		return data.get(index);
	}

	/**
	 * Releases the reference to the backing buffer.
	 */
	protected void destroy() { this.data = null; }
}
