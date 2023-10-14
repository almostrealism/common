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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.DefaultLanguageOperations;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.LanguageOperations;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.c.CJNIPrintWriter;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;

public class NativeComputeContext extends AbstractComputeContext {
	public static boolean enableVerbose = false;
	protected static long totalInvocations = 0;

	private NativeCompiler compiler;

	public NativeComputeContext(Hardware hardware, NativeDataContext dc, NativeCompiler compiler) {
		super(hardware, dc);
		this.compiler = compiler;
	}

	@Override
	public LanguageOperations getLanguage() {
		return new CLanguageOperations(true, true);
	}

	public NativeCompiler getNativeCompiler() { return compiler; }

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		NativeInstructionSet target = getNativeCompiler().reserveLibraryTarget();
		buf.append(new ScopeEncoder(pw -> new CJNIPrintWriter(pw, target.getFunctionName()), Accessibility.EXTERNAL).apply(scope));
		getNativeCompiler().compile(target, buf.toString());
		return target;
	}

	@Override
	public boolean isCPU() { return true; }

	@Override
	public boolean isKernelSupported() { return false; }

	@Override
	public void destroy() { }
}
