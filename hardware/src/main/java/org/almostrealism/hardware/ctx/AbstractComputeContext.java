/*
 * Copyright 2022 Michael Murray
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
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;

public abstract class AbstractComputeContext implements ComputeContext<MemoryData> {
	private final Hardware hardware;
	private final DefaultComputer computer;

	protected AbstractComputeContext(Hardware hardware) { this(hardware, true, false); }

	protected AbstractComputeContext(Hardware hardware, boolean isCl, boolean isNative) {
		this.hardware = hardware;
		this.computer = isNative ? new DefaultComputer(NativeCompiler.factory(hardware, isCl).construct()) : new DefaultComputer();
	}

	@Override
	public DefaultComputer getComputer() { return computer; }

	public String getName() { return hardware.getName(); }
}
