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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.external.ExternalComputeContext;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;

import java.util.concurrent.Callable;

public class NativeDataContext implements DataContext<MemoryData> {
	private final String name;
	private final boolean isExternal, isNativeMem;
	private final long memoryMax;

	private NativeCompiler compiler;
	private MemoryProvider<? extends Memory> ram;

	private Hardware hardware;
	private ComputeContext context;

	public NativeDataContext(Hardware hardware, String name, boolean isNativeMem, boolean external, long memoryMax) {
		this.hardware = hardware;
		this.name = name;
		this.isNativeMem = isNativeMem;
		this.isExternal = external;
		this.memoryMax = memoryMax;
	}

	public void init() {
		if (ram != null) return;
		compiler = NativeCompiler.factory(hardware, !isNativeMem).construct();
		ram = isNativeMem ? new NativeMemoryProvider(compiler, memoryMax) : new JVMMemoryProvider();
		context = isExternal ? new ExternalComputeContext(hardware, compiler) : new NativeComputeContext(hardware, compiler);
	}

	public String getName() { return name; }

	public NativeCompiler getNativeCompiler() { return compiler; }

	public MemoryProvider<? extends Memory> getMemoryProvider() { return ram; }

	@Override
	public MemoryProvider<? extends Memory> getMemoryProvider(int size) { return getMemoryProvider(); }

	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

	public ComputeContext getComputeContext() {
		if (context == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			context = new NativeComputeContext(hardware, getNativeCompiler());
		}

		return context;
	}

	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
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
