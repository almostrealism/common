/*
 * Copyright 2026 Michael Murray
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

/**
 * Wrapper for an {@code MTLSharedEvent}. A shared event carries a monotonically increasing
 * value; encoding a signal of value <em>n</em> into one command buffer and a wait for value
 * <em>n</em> into another orders the second after the first on the GPU. It is the Metal analog
 * of an OpenCL {@code cl_event} and is created via {@link MTLDevice#newSharedEvent()}.
 *
 * @see MTLCommandBuffer#encodeSignalEvent(MTLEvent, long)
 * @see MTLCommandBuffer#encodeWaitForEvent(MTLEvent, long)
 */
public class MTLEvent extends MTLObject {
	/**
	 * Creates an event wrapper for a native shared-event pointer.
	 *
	 * @param nativePointer Native {@code MTLSharedEvent} pointer
	 */
	public MTLEvent(long nativePointer) {
		super(nativePointer);
	}

	/**
	 * Signals this event to the given value from the host, releasing any encoded waits
	 * for values up to and including it. The signaled value must never decrease.
	 *
	 * @param value Value to signal
	 */
	public void setSignaledValue(long value) {
		MTL.setSignaledValue(getNativePointer(), value);
	}

	/**
	 * Releases the native shared event.
	 */
	@Override
	public void release() {
		MTL.releaseSharedEvent(getNativePointer());
		super.release();
	}
}
