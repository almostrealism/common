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
 * A retained CUDA primary context.
 *
 * <p>Every native object the backend creates — streams, events, modules and memory —
 * belongs to a context and is created through it. The bridge makes the context current
 * on whichever thread calls in, so a {@link CUContext} may be used from any thread.</p>
 */
public class CUContext extends CUObject {
	/** The device whose primary context this is. */
	private final CUDevice device;

	/**
	 * Wraps a retained primary context. Obtain instances with
	 * {@link CUDevice#retainPrimaryContext()}.
	 */
	CUContext(CUDevice device, long nativePointer) {
		super(null, nativePointer);
		this.device = device;
	}

	@Override
	public CUContext getContext() { return this; }

	@Override
	long getContextPointer() { return getNativePointer(); }

	/** Returns the device this context belongs to. */
	public CUDevice getDevice() { return device; }

	/** Blocks until all work submitted in this context has completed. */
	public void synchronize() { CU.synchronize(getNativePointer()); }

	/** Creates a new non-blocking stream in this context. */
	public CUStream newStream() {
		return new CUStream(this, CU.streamCreate(getNativePointer()));
	}

	/** Creates a new event (with timing disabled) in this context. */
	public CUEvent newEvent() {
		return new CUEvent(this, CU.eventCreate(getNativePointer()));
	}

	/**
	 * Loads a compiled image, as produced by {@link CUDevice#compile(String, String, String...)},
	 * as a module in this context.
	 */
	public CUModule loadModule(byte[] image) {
		return new CUModule(this, CU.moduleLoadData(getNativePointer(), image));
	}

	/** Allocates zero-filled device memory, which is not addressable from the host. */
	public CUDeviceBuffer allocate(long bytes) {
		return new CUDeviceBuffer(this, CU.memAlloc(getNativePointer(), bytes), bytes, false);
	}

	/** Allocates zero-filled managed memory, addressable from both the host and the device. */
	public CUDeviceBuffer allocateManaged(long bytes) {
		return new CUDeviceBuffer(this, CU.memAllocManaged(getNativePointer(), bytes), bytes, true);
	}

	/** Releases this retain of the device's primary context. */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		CU.primaryContextRelease(device.getHandle());
		super.release();
	}

	@Override
	public String toString() { return "CUContext[" + device + "]"; }
}
