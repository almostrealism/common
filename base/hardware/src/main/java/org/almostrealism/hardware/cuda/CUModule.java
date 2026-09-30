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
 * A loaded CUDA module: the compiled form of one program, from which kernels are obtained.
 *
 * @see CUContext#loadModule(byte[])
 */
public class CUModule extends CUObject {
	/** Wraps a module. Obtain instances with {@link CUContext#loadModule(byte[])}. */
	CUModule(CUContext context, long nativePointer) {
		super(context, nativePointer);
	}

	/**
	 * Returns the kernel with the given name. Kernels are declared {@code extern "C"},
	 * so the name is the unmangled function name from the source.
	 */
	public CUFunction getFunction(String name) {
		return new CUFunction(this, CU.moduleGetFunction(getContextPointer(), getNativePointer(), name));
	}

	/** Unloads this module. Kernels obtained from it may no longer be launched. */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		CU.moduleUnload(getContextPointer(), getNativePointer());
		super.release();
	}
}
