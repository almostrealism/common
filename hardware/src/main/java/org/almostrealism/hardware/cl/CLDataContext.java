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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.SystemUtils;
import org.jocl.CL;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class CLDataContext implements DataContext<MemoryData> {
	public static boolean enableClNative = SystemUtils.isEnabled("AR_HARDWARE_CL_NATIVE").orElse(false);

	private final String name;
	private final long maxReservation;
	private final int offHeapSize;
	private final CLMemoryProvider.Location location;

	private Precision precision;

	private cl_platform_id platform;

	private long deviceType;
	private cl_device_id mainDevice;
	private DeviceInfo mainDeviceInfo;

	private cl_device_id kernelDevice;
	private DeviceInfo kernelDeviceInfo;

	private cl_context ctx;

	private MemoryProvider<RAM> mainRam;
	private MemoryProvider<Memory> altRam;
	private MemoryProvider<? extends RAM> delegateMemory;

	private ThreadLocal<List<ComputeContext<MemoryData>>> computeContexts;
	private ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	private Runnable start;

	public CLDataContext(String name, long maxReservation, int offHeapSize, CLMemoryProvider.Location location) {
		this.name = name;
		this.maxReservation = maxReservation;
		this.offHeapSize = offHeapSize;
		this.location = location;
		this.computeContexts = ThreadLocal.withInitial(ArrayList::new);
		this.memoryProvider = new ThreadLocal<>();
	}

	@Override
	public void init() {
		altRam = new JVMMemoryProvider();
		start = () -> start(!SystemUtils.isMacOS() || SystemUtils.isAarch64());
	}

	protected void identifyDevices(boolean kernelQueue) {
		if (platform != null && mainDevice != null) return;

		final int platformIndex = 0;
		final int deviceIndex = 0;
		deviceType = CL.CL_DEVICE_TYPE_CPU;

		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: " + numPlatforms + " platforms available");

		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		platform = platforms[platformIndex];

		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + name + "]: Using platform " + platformIndex + " -- " + platform);

		/* Main Device Selection */

		int numDevicesArray[] = new int[1];
		int numDevices = 0;

		try {
			CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
			numDevices = numDevicesArray[0];
		} catch (Exception e) { }

		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(deviceType) + "(s) available");

		if (numDevices > 0) {
			cl_device_id devices[] = new cl_device_id[numDevices];
			CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
			mainDevice = devices[deviceIndex];

			System.out.println("Hardware[" + name + "]: Using " + deviceName(deviceType) + " " + deviceIndex);
		}

		/* Kernel Device Selection */

		if (kernelQueue) {
			CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
			numDevices = numDevicesArray[0];
			cl_device_id devices[] = new cl_device_id[numDevices];

			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(CL.CL_DEVICE_TYPE_GPU) + "(s) available for kernels");

			if (numDevices > 0) {
				CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, numDevices, devices, null);
				kernelDevice = devices[deviceIndex];

				System.out.println("Hardware[" + name + "]: Using " + deviceName(CL.CL_DEVICE_TYPE_GPU) + " " + deviceIndex + " for kernels");
			}
		}

		precision = kernelDevice == null ? Precision.FP64 : Precision.FP32;
	}

	private void start(boolean kernelQueue) {
		if (ctx != null) return;

		CL.setExceptionsEnabled(true);

		identifyDevices(kernelQueue);

		this.mainDeviceInfo = mainDevice == null ? null : deviceInfo(mainDevice);
		this.kernelDeviceInfo = kernelDevice == null ? null : deviceInfo(kernelDevice);

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		if (kernelDevice == null) {
			ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{mainDevice},
					null, null, null);
		} else if (mainDevice == null) {
			ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{kernelDevice},
					null, null, null);
			mainDevice = kernelDevice;
		} else {
			ctx = CL.clCreateContext(contextProperties, 2, new cl_device_id[]{mainDevice, kernelDevice},
					null, null, null);
		}

		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		cl_command_queue queue = CL.clCreateCommandQueue(ctx, mainDevice, 0, null);
		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + getName() + "]: OpenCL read/write command queue initialized");

		mainRam = new CLMemoryProvider(this, queue, getPrecision().bytes(),
						maxReservation * getPrecision().bytes(), location);

		start = null;
	}

	private ComputeContext createContext(ComputeRequirement... expectations) {
		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent()) {
			cc = new CLNativeComputeContext(this, NativeCompiler.factory(getPrecision(), true).construct());
		} else {
			if (start != null) start.run();
			cc = new CLComputeContext(this, ctx);
			((CLComputeContext) cc).init(mainDevice, kernelDevice, pReq.isPresent());
		}

		return cc;
	}

	public String getName() { return name; }

	@Override
	public Precision getPrecision() {
		if (start != null) start.run();
		return precision;
	}

	public cl_context getClContext() {
		if (start != null) start.run();
		return ctx;
	}

	protected boolean isCPU() { return kernelDevice == null; }

	public DeviceInfo getMainDeviceInfo() { return mainDeviceInfo; }
	public DeviceInfo getKernelDeviceInfo() { return kernelDeviceInfo; }

	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(mainRam);
	}

	public MemoryProvider<RAM> getMemoryProvider() {
		if (start != null) start.run();
		return mainRam;
	}

	public MemoryProvider<Memory> getAltMemoryProvider() { return altRam; }

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

	public void setDelegateMemoryProvider(MemoryProvider<? extends RAM> delegate) {
		this.delegateMemory = delegate;
	}

	public MemoryProvider<? extends RAM> getDelegateMemoryProvider() {
		return delegateMemory;
	}

	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (computeContexts.get().isEmpty()) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContexts.get().add(createContext());

			if (enableClNative) {
				computeContexts.get().add(createContext(ComputeRequirement.C));
			}
		}

		return computeContexts.get();
	}

	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		List<ComputeContext<MemoryData>> current = computeContexts.get();
		List<ComputeContext<MemoryData>> next = List.of(createContext(expectations));

		String ccName = next.toString();
		if (ccName.contains(".")) {
			ccName = ccName.substring(ccName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + ccName);
			computeContexts.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + ccName);
			next.get(0).destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + ccName);
			computeContexts.set(current);
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
		if (computeContexts.get() != null) {
			computeContexts.get().forEach(cc -> cc.destroy());
			computeContexts.remove();
		}

		if (mainRam != null) mainRam.destroy();
		if (altRam != null) altRam.destroy();
		if (ctx != null) CL.clReleaseContext(ctx);
		ctx = null;
	}

	protected static String deviceName(long type) {
		if (type == CL.CL_DEVICE_TYPE_CPU) {
			return "CPU";
		} else if (type == CL.CL_DEVICE_TYPE_GPU) {
			return "GPU";
		} else {
			throw new IllegalArgumentException("Unknown device type " + type);
		}
	}

	protected DeviceInfo deviceInfo(cl_device_id device) {
		DeviceInfo info = new DeviceInfo(device);

		double kb = 1024.0;
		double mb = kb * kb;
		double gb = mb * kb;

		long cores = info.getCores();
		String clock = info.getClockMhz() / 1000.0 + "GHz";
		String work = info.getMaxWorkItemDimensions() + "D work support and " +
				info.getWorkGroupSize() / kb + "kb work size";
		String memory = info.getLocalMem() / kb + "kb local / " +
				info.getGlobalMem() / gb + "gb global (" +
				info.getMaxAlloc() / gb + "gb allocation limit)";

		System.out.println("Hardware[" + getName() + "]: " + cores + " cores @ " + clock);
		System.out.println("Hardware[" + getName() + "]: " + work);
		System.out.println("Hardware[" + getName() + "]: " + memory);
		if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Max args " + info.getMaxConstantArgs());

		return info;
	}
}
