/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.ctx.HardwareDataContext;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class MetalDataContext extends HardwareDataContext {
	public static final boolean fp16 = SystemUtils.getProperty("AR_HARDWARE_PRECISION", "FP32").equals("FP16");

	private final int offHeapSize;

	private MTLDevice mainDevice;

	private MemoryProvider<RAM> mainRam;
	private MemoryProvider<Memory> altRam;

	private ThreadLocal<ComputeContext<MemoryData>> computeContext;

	private Runnable start;

	public MetalDataContext(String name, long maxReservation, int offHeapSize) {
		super(name, maxReservation);
		this.offHeapSize = offHeapSize;
		this.computeContext = new ThreadLocal<>();
	}

	@Override
	public void init() {
		altRam = new JVMMemoryProvider();
		start = this::start;
	}

	protected void identifyDevices() {
		if (mainDevice != null) return;

		mainDevice = MTLDevice.createSystemDefaultDevice();
	}

	private void start() {
		if (mainDevice != null) return;

		identifyDevices();
		mainRam = new MetalMemoryProvider(this, getPrecision().bytes(),
				getMaxReservation() * getPrecision().bytes());
		start = null;
	}

	private ComputeContext createContext(ComputeRequirement... expectations) {
		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent() || pReq.isPresent()) {
			throw new UnsupportedOperationException();
		} else {
			if (start != null) start.run();
			cc = new MetalComputeContext(this);
			((MetalComputeContext) cc).init(mainDevice);
		}

		return cc;
	}

	@Override
	public Precision getPrecision() { return fp16 ? Precision.FP16 : Precision.FP32; }

	public MTLDevice getDevice() {
		if (start != null) start.run();
		return mainDevice;
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(mainRam);
	}

	@Override
	protected MemoryProvider<RAM> getSharedMemoryProvider() {
		return Optional.ofNullable(super.getSharedMemoryProvider())
				.orElseGet(() -> new MetalMemoryProvider(this, getPrecision().bytes(),
						getMaxReservation() * getPrecision().bytes(), true));
	}

	public MemoryProvider<RAM> getMemoryProvider() {
		if (start != null) start.run();
		return mainRam;
	}

	public MemoryProvider<Memory> getAltMemoryProvider() {
		return altRam;
	}

	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

	@Override
	public MemoryProvider<?> getMemoryProvider(int size) {
		IntFunction<MemoryProvider<?>> supply = getMemoryProviderSupply();
		if (supply == null) {
			return size < offHeapSize ? getAltMemoryProvider() : getMemoryProvider();
		} else {
			return supply.apply(size);
		}
	}

	public IntFunction<String> getMemoryName() {
		return memoryName.get();
	}

	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (computeContext.get() == null) {
			if (Hardware.enableVerbose) log("No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContext.set(createContext());
		}

		return List.of(computeContext.get());
	}

	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		ComputeContext current = computeContext.get();
		ComputeContext next = createContext(expectations);

		String ccName = next.toString();
		if (ccName.contains(".")) {
			ccName = ccName.substring(ccName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + ccName);
			computeContext.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + ccName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + ccName);
			computeContext.set(current);
		}
	}

	@Override
	public <T> T deviceMemory(Callable<T> exec) {
		IntFunction<MemoryProvider<?>> current = memoryProvider.get();
		IntFunction<MemoryProvider<?>> next = s -> mainRam;

		try {
			memoryProvider.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			memoryProvider.set(current);
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy any other compute contexts
		if (computeContext.get() != null) {
			computeContext.get().destroy();
			computeContext.remove();
		}

		if (mainRam != null) mainRam.destroy();
		if (altRam != null) altRam.destroy();
		if (mainDevice != null) mainDevice.release();
		mainDevice = null;
	}
}
