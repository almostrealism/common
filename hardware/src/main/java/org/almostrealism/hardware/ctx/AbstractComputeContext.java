/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Computer;
import io.almostrealism.code.DataContext;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;

public abstract class AbstractComputeContext implements ComputeContext<MemoryData> {
	private final Hardware hardware;
	private final DataContext<MemoryData> dc;

	protected AbstractComputeContext(Hardware hardware, DataContext<MemoryData> dc) {
		this.hardware = hardware;
		this.dc = dc;
	}

	public String getName() { return hardware.getName(); }

	public Hardware getHardware() { return hardware; }

	public DataContext<MemoryData> getDataContext() { return dc; }

	@Override
	public String getKernelIndex(int dimension) {
		throw new UnsupportedOperationException();
	}
}
