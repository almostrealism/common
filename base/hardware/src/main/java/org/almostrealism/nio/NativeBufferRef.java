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

package org.almostrealism.nio;

import org.almostrealism.hardware.mem.NativeRef;

import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link NativeRef} for tracking {@link NativeBuffer} instances with garbage collection.
 *
 * <p>Because {@link org.almostrealism.hardware.mem.MemoryReference} extends
 * {@link java.lang.ref.PhantomReference}, {@code get()} always returns {@code null}.
 * This subclass caches the fields needed for post-GC deallocation: the root
 * {@link ByteBuffer} and shared location (for unmapping shared memory), and any
 * deallocation listeners registered before the referent was collected.</p>
 *
 * @see NativeBufferMemoryProvider
 * @see NativeBuffer
 */
public class NativeBufferRef extends NativeRef<NativeBuffer> {
	private final ByteBuffer rootBuffer;
	private final String sharedLocation;
	private final List<Consumer<NativeBuffer>> deallocationListeners;

	/**
	 * Creates a reference for tracking NativeBuffer memory lifecycle.
	 *
	 * <p>Caches the root {@link ByteBuffer}, shared location, and deallocation
	 * listeners from the {@link NativeBuffer} to enable cleanup after
	 * garbage collection, when the referent is no longer accessible.</p>
	 *
	 * @param buffer The {@link NativeBuffer} to track
	 * @param referenceQueue Queue for receiving GC notifications
	 */
	public NativeBufferRef(NativeBuffer buffer, ReferenceQueue<? super NativeBuffer> referenceQueue) {
		super(buffer, referenceQueue);
		this.rootBuffer = buffer.getRootBuffer();
		this.sharedLocation = buffer.getSharedLocation();
		this.deallocationListeners = new ArrayList<>(buffer.getDeallocationListeners());
	}

	/**
	 * Returns the root {@link ByteBuffer} for shared memory unmapping.
	 *
	 * @return The root byte buffer, or {@code null} if not shared
	 */
	public ByteBuffer getRootBuffer() {
		return rootBuffer;
	}

	/**
	 * Returns the shared memory location path.
	 *
	 * @return The shared location path, or {@code null} if not shared
	 */
	public String getSharedLocation() {
		return sharedLocation;
	}

	/**
	 * Returns the deallocation listeners captured at construction time.
	 *
	 * @return List of deallocation listeners
	 */
	public List<Consumer<NativeBuffer>> getDeallocationListeners() {
		return deallocationListeners;
	}
}
