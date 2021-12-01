/*
 * Copyright 2021 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.jni;

import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.RAM;

import java.util.concurrent.Callable;

public class NativeDataContext implements DataContext {
	private final NativeCompiler compiler;
	private final String name;
	private final boolean isDoublePrecision;
	private final long memoryMax;

	private MemoryProvider<RAM> ram;

	private NativeComputeContext context;

	public NativeDataContext(NativeCompiler compiler, String name, boolean isDoublePrecision, long memoryMax) {
		this.compiler = compiler;
		this.name = name;
		this.isDoublePrecision = isDoublePrecision;
		this.memoryMax = memoryMax;
		this.context = new NativeComputeContext(compiler);
	}

	public void init() {
		if (ram != null) return;
		ram = new NativeMemoryProvider(memoryMax);
	}

	public String getName() { return name; }

	public MemoryProvider<RAM> getMemoryProvider() { return ram; }

	public NativeComputeContext getComputeContext() {
		if (context == null) {
			System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			context = new NativeComputeContext(compiler);
		}

		return context;
	}

	protected void setComputeContext(NativeComputeContext ctx) {
		this.context = ctx;
	}

	public <T> T computeContext(Callable<T> exec) {
		try {
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy all compute contexts
		ram.destroy();
	}
}