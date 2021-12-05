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

package org.almostrealism.hardware.external;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.c.CJNIPrintWriter;
import org.almostrealism.hardware.jni.NativeCompiler;

public class ExternalComputeContext implements ComputeContext {
	private NativeCompiler compiler;

	public ExternalComputeContext(NativeCompiler compiler) {
		this.compiler = compiler;
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		buf.append(new ScopeEncoder(pw -> new CJNIPrintWriter(pw, "apply"), Accessibility.EXTERNAL).apply(scope));
		String executable = compiler.compile((String) null, buf.toString());
		return new ExternalInstructionSet(executable);
	}

	@Override
	public void destroy() { }
}
