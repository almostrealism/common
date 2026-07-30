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
	 * @throws IllegalStateException if the event has been released
	 */
	public void setSignaledValue(long value) {
		MTL.setSignaledValue(getNativePointer(), value);
	}

	/**
	 * Signals this event to the given value if it has not been released, mutually
	 * excluded with {@link #release()} so the native object cannot be freed while
	 * the signal call is in flight.
	 *
	 * <p>This is the safe form for a host signal that is delivered asynchronously
	 * (a completion callback): the event may legitimately be gone by the time the
	 * callback runs — its sole waiting command buffer already finished by another
	 * path (an error or teardown completion) and released it — in which case the
	 * signal has no observer and is skipped rather than throwing or touching freed
	 * native memory.</p>
	 *
	 * @param value Value to signal
	 * @return true if the event was signaled, false if it was already released
	 */
	public synchronized boolean signal(long value) {
		if (isReleased()) return false;
		MTL.setSignaledValue(getNativePointer(), value);
		return true;
	}

	/**
	 * Releases the native shared event. Mutually excluded with {@link #signal(long)},
	 * so a late asynchronous signal observes either a live event or the released
	 * state — never a freed native pointer mid-call.
	 */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		MTL.releaseSharedEvent(getNativePointer());
		super.release();
	}
}
