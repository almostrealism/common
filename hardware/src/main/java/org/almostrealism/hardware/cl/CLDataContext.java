/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.jocl.CL;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class CLDataContext implements DataContext {
	private final Hardware hardware;
	private final String name;
	private final long memoryMax;
	private final int offHeapSize;
	private final CLMemoryProvider.Location location;

	private cl_platform_id platform;

	private cl_device_id mainDevice;
	private DeviceInfo mainDeviceInfo;

	private cl_device_id kernelDevice;
	private DeviceInfo kernelDeviceInfo;

	private cl_context ctx;

	private MemoryProvider<RAM> mainRam;
	private MemoryProvider<Memory> altRam;

	private ThreadLocal<ComputeContext> computeContext;
	private ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	private Runnable start;

	public CLDataContext(Hardware hardware, String name, long memoryMax, int offHeapSize, CLMemoryProvider.Location location) {
		this.hardware = hardware;
		this.name = name;
		this.memoryMax = memoryMax;
		this.offHeapSize = offHeapSize;
		this.location = location;
		this.computeContext = new ThreadLocal<>();
		this.memoryProvider = new ThreadLocal<>();
	}

	public void init(boolean gpu, boolean kernelQueue) {
		altRam = new JVMMemoryProvider();
		start = () -> start(gpu, kernelQueue);
	}

	protected void identifyDevices(boolean gpu, boolean kernelQueue) {
		if (platform != null && mainDevice != null) return;

		final int platformIndex = 0;
		final int deviceIndex = 0;
		final long deviceType = gpu ? CL.CL_DEVICE_TYPE_GPU : CL.CL_DEVICE_TYPE_CPU;

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
		CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
		int numDevices = numDevicesArray[0];

		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(deviceType) + "(s) available");

		cl_device_id devices[] = new cl_device_id[numDevices];
		CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
		mainDevice = devices[deviceIndex];

		System.out.println("Hardware[" + name + "]: Using " + deviceName(deviceType) + " " + deviceIndex);

		/* Kernel Device Selection */

		if (kernelQueue) {
			CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
			numDevices = numDevicesArray[0];

			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(CL.CL_DEVICE_TYPE_GPU) + "(s) available for kernels");

			if (numDevices > 0) {
				CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, numDevices, devices, null);
				kernelDevice = devices[deviceIndex];

				System.out.println("Hardware[" + name + "]: Using " + deviceName(CL.CL_DEVICE_TYPE_GPU) + " " + deviceIndex + " for kernels");
			}
		}
	}

	private void start(boolean gpu, boolean kernelQueue) {
		if (ctx != null) return;

		CL.setExceptionsEnabled(true);

		identifyDevices(gpu, kernelQueue);

		this.mainDeviceInfo = mainDevice == null ? null : deviceInfo(mainDevice);
		this.kernelDeviceInfo = kernelDevice == null ? null : deviceInfo(kernelDevice);

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		if (kernelDevice == null) {
			ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{mainDevice},
					null, null, null);
		} else {
			ctx = CL.clCreateContext(contextProperties, 2, new cl_device_id[]{mainDevice, kernelDevice},
					null, null, null);
		}

		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		cl_command_queue queue = CL.clCreateCommandQueue(ctx, mainDevice, 0, null);
		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + getName() + "]: OpenCL read/write command queue initialized");

		mainRam = new CLMemoryProvider(this, queue, hardware.getNumberSize(), memoryMax, location);

		start = null;
	}

	private ComputeContext createContext(ComputeRequirement... expectations) {
		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent()) {
			cc = new CLNativeComputeContext(hardware);
		} else {
			if (start != null) start.run();
			cc = new CLComputeContext(hardware, ctx);
			((CLComputeContext) cc).init(mainDevice, kernelDevice, pReq.isPresent());
		}

		return cc;
	}

	public String getName() { return name; }

	public cl_context getClContext() {
		if (start != null) start.run();
		return ctx;
	}

	public DeviceInfo getMainDeviceInfo() { return mainDeviceInfo; }
	public DeviceInfo getKernelDeviceInfo() { return kernelDeviceInfo; }

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

	public ComputeContext getComputeContext() {
		if (computeContext.get() == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContext.set(createContext());
		}

		return computeContext.get();
	}

	public CLComputeContext getClComputeContext() {
		return (CLComputeContext) getComputeContext();
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
