/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.metal;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

/**
 * Base class for Metal object wrappers that track native pointer and release state.
 *
 * <p>Provides common lifecycle management for {@link MTLDevice}, {@link MTLBuffer},
 * {@link MTLCommandQueue}, and other Metal resources.</p>
 *
 * <h2>Lifecycle Pattern</h2>
 *
 * <pre>{@code
 * MTLObject obj = ...;
 * long ptr = obj.getNativePointer();  // Get native pointer
 *
 * // Use object...
 *
 * obj.release();  // Mark as released
 * // Further operations throw IllegalStateException
 * }</pre>
 *
 * @see MTLDevice
 * @see MTLBuffer
 * @see MTLCommandQueue
 */
public abstract class MTLObject implements ConsoleFeatures {
	private long nativePointer;
	private boolean released;

	/**
	 * Creates a Metal object wrapper for a native pointer.
	 *
	 * @param nativePointer Native Metal object pointer (e.g., {@code id<MTLDevice>})
	 */
	public MTLObject(long nativePointer) {
		this.nativePointer = nativePointer;
	}

	/**
	 * Returns the native Metal object pointer.
	 *
	 * <p>Most subclasses use this to pass the pointer to native {@link MTL} methods.</p>
	 *
	 * @return Native pointer value
	 * @throws IllegalStateException if object has been released
	 */
	public long getNativePointer() {
		if (isReleased()) throw new IllegalStateException();
		return nativePointer;
	}

	/**
	 * Checks if this object has been released.
	 *
	 * @return True if {@link #release()} has been called
	 */
	public boolean isReleased() { return released; }

	/**
	 * Marks this object as released.
	 *
	 * <p>Subclasses override this to free native resources via {@link MTL} methods
	 * before calling {@code super.release()}. After release, {@link #getNativePointer()}
	 * will throw {@link IllegalStateException}.</p>
	 */
	public void release() { released = true; }

	/**
	 * Returns the console for logging output.
	 *
	 * @return The {@link Console} instance for hardware logging
	 */
	@Override
	public Console console() { return Hardware.console; }
}
