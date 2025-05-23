/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.HardwareDataContext;
import org.almostrealism.hardware.external.ExternalComputeContext;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.concurrent.Callable;

public class NativeDataContext extends HardwareDataContext {
	private static boolean external = SystemUtils.getProperty("AR_HARDWARE_NATIVE_EXECUTION", "").equalsIgnoreCase("external");

	private final boolean isExternal, isClMemory;

	private NativeCompiler compiler;
	private DataContext<MemoryData> delegate;
	private MemoryProvider<? extends Memory> ram;
	private boolean providedRam = false;

	private Precision precision;
	private ComputeContext<MemoryData> context;

	public NativeDataContext(String name, Precision precision, long maxReservation) {
		this(name, precision, maxReservation, false);
	}

	public NativeDataContext(String name, Precision precision, long maxReservation, boolean clMemory) {
		super(name, maxReservation);
		this.precision = precision;
		this.isExternal = external;
		this.isClMemory = clMemory;
	}

	@Override
	public void init() {
		if (context != null) return;
		compiler = NativeCompiler.factory(getPrecision(), isClMemory).construct();

		if (ram == null) {
			ram = new NativeMemoryProvider(compiler, getMaxReservation() * getPrecision().bytes());
		}

		context = isExternal ? new ExternalComputeContext(this, compiler) : new NativeComputeContext(this, compiler);
	}

	@Override
	public Precision getPrecision() { return precision; }

	public NativeCompiler getNativeCompiler() { return compiler; }

	public void setDelegate(DataContext<MemoryData> ctx) {
		this.delegate = ctx;
	}

	public void setMemoryProvider(MemoryProvider<? extends Memory> ram) {
		if (getPrecision().bytes() != ram.getNumberSize()) {
			throw new UnsupportedOperationException();
		}

		this.ram = ram;
		providedRam = true;
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(ram);
	}

	public MemoryProvider<? extends Memory> getMemoryProvider() { return ram; }

	@Override
	public MemoryProvider<? extends Memory> getMemoryProvider(int size) {
		return delegate == null ? getMemoryProvider() : delegate.getMemoryProvider(size);
	}

	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (context == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			context = new NativeComputeContext(this, getNativeCompiler());
		}

		return List.of(context);
	}

	@Override
	public <T> T deviceMemory(Callable<T> exec) {
		try {
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
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
		if (!providedRam) ram.destroy();
	}
}
