/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.c.OpenCLPrintWriter;
import org.almostrealism.hardware.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;
import org.jocl.cl_context;

import java.util.ArrayList;
import java.util.List;

public class CLComputeContext extends AbstractComputeContext {
	private static final String fp64 = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n";

	private boolean enableFp64;
	private cl_context ctx;

	private List<HardwareOperatorMap> instructionSets;

	public CLComputeContext(Hardware hardware, cl_context ctx) {
		super(hardware, true, false);
		this.enableFp64 = hardware.isDoublePrecision();
		this.ctx = ctx;
		this.instructionSets = new ArrayList<>();
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		if (enableFp64) buf.append(fp64);

		ScopeEncoder enc = new ScopeEncoder(OpenCLPrintWriter::new, Accessibility.EXTERNAL);
		buf.append(enc.apply(scope));

		HardwareOperatorMap instSet = new HardwareOperatorMap(this, buf.toString());
		instructionSets.add(instSet);
		return instSet;
	}

	@Override
	public boolean isKernelSupported() { return true; }

	protected cl_context getCLContext() {
		return ctx;
	}

	@Override
	public void destroy() {
		this.instructionSets.forEach(InstructionSet::destroy);
	}
}
