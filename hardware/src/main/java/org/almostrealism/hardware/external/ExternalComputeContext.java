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
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jni.NativeInstructionSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ExternalComputeContext extends AbstractComputeContext {
	private static final String externalWrapper;

	static {
		StringBuffer buf = new StringBuffer();

		try (BufferedReader in =
					 new BufferedReader(new InputStreamReader(
							 ExternalComputeContext.class.getClassLoader().getResourceAsStream("external-wrapper.c")))) {
			String line;
			while ((line = in.readLine()) != null) {
				buf.append(line);
				buf.append("\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		externalWrapper = buf.toString();
	}

	public ExternalComputeContext(Hardware hardware) {
		super(hardware, false, true);
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuffer buf = new StringBuffer();
		NativeInstructionSet inst = getComputer().getNativeCompiler().reserveLibraryTarget();
		buf.append(new ScopeEncoder(pw -> new CPrintWriter(pw, "apply"), Accessibility.EXTERNAL).apply(scope));
		buf.append("\n");
		buf.append(externalWrapper);
		String executable = getComputer().getNativeCompiler().getLibraryDirectory() + "/" + getComputer().getNativeCompiler().compile(inst.getClass().getName(), buf.toString(), false);
		return new ExternalInstructionSet(executable, getComputer().getNativeCompiler()::reserveDataDirectory);
	}

	@Override
	public boolean isKernelSupported() { return false; }

	@Override
	public void destroy() { }
}
