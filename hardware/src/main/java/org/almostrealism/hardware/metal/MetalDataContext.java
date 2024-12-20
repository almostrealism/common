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
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class MetalDataContext implements DataContext<MemoryData> {
	public static final boolean fp16 = SystemUtils.getProperty("AR_HARDWARE_PRECISION", "FP32").equals("FP16");

	private final String name;
	private final long maxReservation;
	private final int offHeapSize;

	private MTLDevice mainDevice;
	private MetalDeviceInfo mainDeviceInfo;

	private MemoryProvider<RAM> mainRam;
	private MemoryProvider<Memory> altRam;

	private ThreadLocal<ComputeContext<MemoryData>> computeContext;
	private ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	private Runnable start;

	public MetalDataContext(String name, long maxReservation, int offHeapSize) {
		this.name = name;
		this.maxReservation = maxReservation;
		this.offHeapSize = offHeapSize;
		this.computeContext = new ThreadLocal<>();
		this.memoryProvider = new ThreadLocal<>();
	}

	@Override
	public void init() {
		altRam = new JVMMemoryProvider();
		start = this::start;
	}

	protected void identifyDevices() {
		if (mainDevice != null) return;

		mainDevice = MTLDevice.createSystemDefaultDevice();
		mainDeviceInfo = deviceInfo(mainDevice);
	}

	private void start() {
		if (mainDeviceInfo != null) return;

		identifyDevices();
		mainRam = new MetalMemoryProvider(this, getPrecision().bytes(), maxReservation * getPrecision().bytes());
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
	public String getName() { return name; }

	@Override
	public Precision getPrecision() { return fp16 ? Precision.FP16 : Precision.FP32; }

	public MTLDevice getDevice() {
		if (start != null) start.run();
		return mainDevice;
	}

	public MetalDeviceInfo getDeviceInfo() { return mainDeviceInfo; }

	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(mainRam);
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
		IntFunction<MemoryProvider<?>> supply = memoryProvider.get();
		if (supply == null) {
			return size < offHeapSize ? getAltMemoryProvider() : getMemoryProvider();
		} else {
			return supply.apply(size);
		}
	}

	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (computeContext.get() == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
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

	protected MetalDeviceInfo deviceInfo(MTLDevice device) {
		MetalDeviceInfo info = new MetalDeviceInfo(device);

		double kb = 1024.0;
		double mb = kb * kb;
		double gb = mb * kb;

//		long cores = info.getCores();
//		String clock = info.getClockMhz() / 1000.0 + "GHz";
//		String work = info.getMaxWorkItemDimensions() + "D work support and " +
//				info.getWorkGroupSize() / kb + "kb work size";
//		String memory = info.getLocalMem() / kb + "kb local / " +
//				info.getGlobalMem() / gb + "gb global (" +
//				info.getMaxAlloc() / gb + "gb allocation limit)";
//
//		System.out.println("Hardware[" + getName() + "]: " + cores + " cores @ " + clock);
//		System.out.println("Hardware[" + getName() + "]: " + work);
//		System.out.println("Hardware[" + getName() + "]: " + memory);
//		if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Max args " + info.getMaxConstantArgs());

		return info;
	}
}
