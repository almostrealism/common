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

package org.almostrealism.hardware.cuda;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

/**
 * Base class for typed wrappers of CUDA native objects that live within a {@link CUContext}.
 *
 * <p>The native handle is visible only to the {@code cuda} package, for passing to
 * {@link CU}. Once {@link #release()} has run, the handle may no longer be used and
 * {@link #getNativePointer()} throws.</p>
 */
public abstract class CUObject implements ConsoleFeatures {
	/** The owning context, or null when this object is the context. */
	private final CUContext context;

	/** The native handle. */
	private final long nativePointer;

	/** Whether {@link #release()} has run. */
	private boolean released;

	/**
	 * Wraps a native handle owned by the given context.
	 *
	 * @param context       the owning context, or null for the context itself
	 * @param nativePointer the native handle
	 */
	protected CUObject(CUContext context, long nativePointer) {
		this.context = context;
		this.nativePointer = nativePointer;
	}

	/** Returns the context this object belongs to. */
	public CUContext getContext() { return context; }

	/** Returns the native handle, for use with {@link CU} only. */
	long getNativePointer() {
		if (isReleased()) {
			throw new IllegalStateException(getClass().getSimpleName() + " has been released");
		}

		return nativePointer;
	}

	/** Returns the native handle of the owning context, for use with {@link CU} only. */
	long getContextPointer() { return context.getNativePointer(); }

	/** Returns true once {@link #release()} has run. */
	public boolean isReleased() { return released; }

	/**
	 * Releases the native object. Subclasses free the native resource and then call
	 * this implementation. Releasing twice has no effect.
	 */
	public synchronized void release() { released = true; }

	@Override
	public Console console() { return Hardware.console; }
}
