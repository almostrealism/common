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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Scope;
import org.almostrealism.c.CJNIPrintWriter;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.VerbatimCodePrintWriter;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.io.PrintWriter;
import org.jocl.cl_context;

import java.util.ArrayList;
import java.util.List;

public class NativeComputeContext implements ComputeContext {
	private NativeCompiler compiler;

	public NativeComputeContext(NativeCompiler compiler) {
		this.compiler = compiler;
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		scope.write(new CJNIPrintWriter(PrintWriter.of(buf::append)));
		return null; // TODO  Compile with NativeCompiler
	}

	@Override
	public void destroy() { }
}
