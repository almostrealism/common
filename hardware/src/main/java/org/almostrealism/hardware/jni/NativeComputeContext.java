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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.lang.ScopeEncoder;
import org.almostrealism.c.CJNILanguageOperations;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.metal.MetalJNIMemoryAccessor;
import org.almostrealism.hardware.metal.MetalMemoryProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class NativeComputeContext extends AbstractComputeContext<NativeDataContext> {
	public static boolean enableVerbose = false;
	public static boolean enableLargeScopeMonitoring = false;
	protected static long totalInvocations = 0;

	private NativeCompiler compiler;

	public NativeComputeContext(NativeDataContext dc, NativeCompiler compiler) {
		super(dc);
		this.compiler = compiler;
	}

	@Override
	public LanguageOperations getLanguage() {
		return new CJNILanguageOperations(getDataContext().getPrecision());
	}

	public NativeCompiler getNativeCompiler() { return compiler; }

	@Override
	public InstructionSet deliver(Scope scope) {
		NativeInstructionSet target = getNativeCompiler().reserveLibraryTarget();
		target.setComputeContext(this);
		target.setMetadata(scope.getMetadata().withContextName(getDataContext().getName()));
		target.setParallelism(NativeExecution.PARALLELISM);

		JNIMemoryAccessor accessor;

		if (getDataContext().getMemoryProvider() instanceof MetalMemoryProvider) {
			accessor = new MetalJNIMemoryAccessor();
		} else {
			accessor = new DefaultJNIMemoryAccessor();
		}

		StringBuffer buf = new StringBuffer();
		buf.append(new ScopeEncoder(pw ->
				new CJNIPrintWriter(pw, target.getFunctionName(), target.getParallelism(),
						getLanguage(), accessor), Accessibility.EXTERNAL).apply(scope));

		if (enableLargeScopeMonitoring) {
			if (buf.length() > 240000) {
				try {
					Files.writeString(Path.of("large_scope.txt"), buf.toString());
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}

				System.out.println("Wrote large Scope to large_scope.txt");
			}
		}

		getNativeCompiler().compile(target, buf.toString());
		return target;
	}

	@Override
	public boolean isCPU() { return true; }

	@Override
	public void destroy() { }
}
