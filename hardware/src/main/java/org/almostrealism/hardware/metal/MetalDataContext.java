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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.Precision;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class MetalDataContext implements DataContext<MemoryData> {
	private final Hardware hardware;
	private final String name;
	private final long memoryMax;
	private final int offHeapSize;

	private MTLDevice mainDevice, kernelDevice;
	private MetalDeviceInfo mainDeviceInfo, kernelDeviceInfo;

	private MemoryProvider<RAM> mainRam;
	private MemoryProvider<Memory> altRam;

	private ThreadLocal<ComputeContext<MemoryData>> computeContext;
	private ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	private Runnable start;

	public MetalDataContext(Hardware hardware, String name, long memoryMax, int offHeapSize) {
		this.hardware = hardware;
		this.name = name;
		this.memoryMax = memoryMax;
		this.offHeapSize = offHeapSize;
		this.computeContext = new ThreadLocal<>();
		this.memoryProvider = new ThreadLocal<>();
	}

	public void init(boolean gpu, boolean kernelQueue) {
		altRam = new JVMMemoryProvider();
		start = () -> start(gpu, kernelQueue);
	}

	protected void identifyDevices(boolean gpu, boolean kernelQueue) {
		if (mainDevice != null) return;

		mainDevice = MTLDevice.createSystemDefaultDevice();
		mainDeviceInfo = mainDevice == null ? null : deviceInfo(mainDevice);
	}

	private void start(boolean gpu, boolean kernelQueue) {
		if (mainDeviceInfo != null) return;

		identifyDevices(gpu, kernelQueue);
		mainRam = new MetalMemoryProvider(this, hardware.getNumberSize(), memoryMax);
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
			cc = new MetalComputeContext(hardware);
			((MetalComputeContext) cc).init(mainDevice, kernelDevice);
		}

		return cc;
	}

	public String getName() { return name; }

	public Precision getPrecision() { return hardware.getPrecision(); }

	public MTLDevice getDevice() {
		if (start != null) start.run();
		return mainDevice;
	}

	public MetalDeviceInfo getMainDeviceInfo() { return mainDeviceInfo; }
	public MetalDeviceInfo getKernelDeviceInfo() { return kernelDeviceInfo; }

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

	public ComputeContext getComputeContext() {
		if (computeContext.get() == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContext.set(createContext());
		}

		return computeContext.get();
	}

	public MetalComputeContext getMetalComputeContext() {
		return (MetalComputeContext) getComputeContext();
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
		if (kernelDevice != null) kernelDevice.release();
		mainDevice = null;
		kernelDevice = null;
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
