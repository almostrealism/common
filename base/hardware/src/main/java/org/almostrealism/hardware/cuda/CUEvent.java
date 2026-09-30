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

/**
 * A CUDA event (timing disabled), used to observe and order completion of stream work.
 *
 * @see CUStream#record(CUEvent)
 * @see CUStream#waitFor(CUEvent)
 */
public class CUEvent extends CUObject {
	/** Wraps an event. Obtain instances with {@link CUContext#newEvent()}. */
	CUEvent(CUContext context, long nativePointer) {
		super(context, nativePointer);
	}

	/** Returns true if all work captured by the most recent record has completed. */
	public boolean isComplete() {
		return CU.eventQuery(getContextPointer(), getNativePointer());
	}

	/** Blocks until all work captured by the most recent record has completed. */
	public void synchronize() {
		CU.eventSynchronize(getContextPointer(), getNativePointer());
	}

	/** Destroys this event. */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		CU.eventDestroy(getContextPointer(), getNativePointer());
		super.release();
	}
}
