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
 * A non-blocking CUDA stream.
 *
 * <p>Work submitted to one stream executes in submission order, which is the ordering
 * guarantee the backend relies on for dependencies within one compute context.
 * Dependencies on work in other streams are expressed with {@link #waitFor(CUEvent)}.</p>
 */
public class CUStream extends CUObject {
	/** Wraps a stream. Obtain instances with {@link CUContext#newStream()}. */
	CUStream(CUContext context, long nativePointer) {
		super(context, nativePointer);
	}

	/** Makes all work submitted to this stream after this call wait for the event. */
	public void waitFor(CUEvent event) {
		CU.streamWaitEvent(getContextPointer(), getNativePointer(), event.getNativePointer());
	}

	/** Records the event on this stream, capturing all work submitted so far. */
	public void record(CUEvent event) {
		CU.eventRecord(getContextPointer(), event.getNativePointer(), getNativePointer());
	}

	/**
	 * Creates a new event in this stream's context and records it here, capturing all work
	 * submitted so far. The caller owns the event and releases it once it is no longer needed.
	 * If recording fails, the event is released and the recording failure is thrown, with any
	 * failure to release the event attached to it as suppressed.
	 *
	 * @return the recorded event
	 */
	public CUEvent recordEvent() {
		CUEvent event = getContext().newEvent();

		try {
			record(event);
		} catch (RuntimeException | Error e) {
			try {
				event.release();
			} catch (RuntimeException | Error releaseFailure) {
				e.addSuppressed(releaseFailure);
			}

			throw e;
		}

		return event;
	}

	/** Blocks until all work submitted to this stream has completed. */
	public void synchronize() {
		CU.streamSynchronize(getContextPointer(), getNativePointer());
	}

	/**
	 * Enqueues a copy of {@code bytes} bytes between two device buffers. Offsets are in bytes.
	 * Both ranges are validated before the copy is enqueued, exactly as for the synchronous
	 * {@link CUDeviceBuffer#copyFrom}, because the driver performs raw pointer arithmetic on them.
	 *
	 * @throws IndexOutOfBoundsException if either range falls outside its allocation
	 */
	public void copy(CUDeviceBuffer source, long sourceOffset,
					 CUDeviceBuffer destination, long destinationOffset, long bytes) {
		source.checkRange(sourceOffset, bytes);
		destination.checkRange(destinationOffset, bytes);
		CU.memcpyDtoDAsync(getContextPointer(),
				destination.getNativePointer() + destinationOffset,
				source.getNativePointer() + sourceOffset,
				bytes, getNativePointer());
	}

	/** Destroys this stream. Work already submitted still completes. */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		CU.streamDestroy(getContextPointer(), getNativePointer());
		super.release();
	}
}
