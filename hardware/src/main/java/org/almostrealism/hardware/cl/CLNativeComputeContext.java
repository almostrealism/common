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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jni.NativeInstructionSet;

public class CLNativeComputeContext extends AbstractComputeContext {
	public static boolean enableVerbose = false;
	protected static long totalInvocations = 0;

	public CLNativeComputeContext(Hardware hardware) {
		super(hardware, true, true);
	}

	@Override
	public LanguageOperations getLanguage() {
		return new CLJNILanguageOperations();
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		NativeInstructionSet target = getComputer().getNativeCompiler().reserveLibraryTarget();
		buf.append(new ScopeEncoder(pw -> new CLJNIPrintWriter(pw, target.getFunctionName()), Accessibility.EXTERNAL).apply(scope));
		getComputer().getNativeCompiler().compile(target, buf.toString());
		return target;
	}

	@Override
	public boolean isKernelSupported() { return false; }

	@Override
	public void destroy() { }
}
