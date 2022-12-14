/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.cl.DeviceInfo;
import org.almostrealism.hardware.ctx.ContextListener;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.io.SystemUtils;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class Hardware {
	public static boolean enableVerbose = false;
	public static final boolean enableMultiThreading = true;
	public static boolean enableKernelOps = true;

	protected static final int MEMORY_SCALE;
	protected static final boolean ENABLE_POOLING;

	protected static final long timeSeriesSize;
	protected static final int timeSeriesCount;

	private static final Hardware local;

	static {
		boolean gpu = "gpu".equalsIgnoreCase(System.getenv("AR_HARDWARE_PLATFORM")) ||
				"gpu".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PLATFORM"));

		boolean enableKernels = SystemUtils.isEnabled("AR_ENABLE_KERNELS").orElse(true);
		enableKernelOps = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_OPS").orElse(true);

		boolean enableDestinationConsolidation =
				"enabled".equalsIgnoreCase(System.getenv("AR_HARDWARE_DESTINATION_CONSOLIDATION")) ||
						"enabled".equalsIgnoreCase(System.getProperty("AR_HARDWARE_DESTINATION_CONSOLIDATION"));

		boolean sp = "32".equalsIgnoreCase(System.getenv("AR_HARDWARE_PRECISION")) ||
				"32".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PRECISION"));

		String memScale = System.getProperty("AR_HARDWARE_MEMORY_SCALE");
		if (memScale == null) memScale = System.getenv("AR_HARDWARE_MEMORY_SCALE");
		MEMORY_SCALE = Optional.ofNullable(memScale).map(Integer::parseInt).orElse(4);

		String pooling = System.getProperty("AR_HARDWARE_MEMORY_MODE");
		if (pooling == null) pooling = System.getenv("AR_HARDWARE_MEMORY_MODE");
		ENABLE_POOLING = "pool".equalsIgnoreCase(pooling);

		String memLocation = System.getProperty("AR_HARDWARE_MEMORY_LOCATION");
		if (memLocation == null) memLocation = System.getenv("AR_HARDWARE_MEMORY_LOCATION");
		Location location = Location.DEVICE;
		if ("heap".equalsIgnoreCase(memLocation)) {
			location = Location.HEAP;
		} else if ("host".equalsIgnoreCase(memLocation)) {
			location = Location.HOST;
		}

		String opDepth = System.getProperty("AR_HARDWARE_MAX_DEPTH");
		if (opDepth == null) opDepth = System.getenv("AR_HARDWARE_MAX_DEPTH");
		if (opDepth != null) OperationList.setMaxDepth(Integer.parseInt(opDepth));

		String tsSize = System.getProperty("AR_HARDWARE_TIMESERIES_SIZE");
		if (tsSize == null) tsSize = System.getenv("AR_HARDWARE_TIMESERIES_SIZE");
		if (tsSize == null) tsSize = "100";

		String tsCount = System.getProperty("AR_HARDWARE_TIMESERIES_COUNT");
		if (tsCount == null) tsCount = System.getenv("AR_HARDWARE_TIMESERIES_COUNT");
		if (tsCount == null) tsCount = "24";

		String memProvider = System.getProperty("AR_HARDWARE_MEMORY_PROVIDER");
		if (memProvider == null) memProvider = System.getenv("AR_HARDWARE_MEMORY_PROVIDER");
		if (memProvider == null) memProvider = "cl";
		if (memProvider.equalsIgnoreCase("native") || memProvider.equalsIgnoreCase("jvm")) {
			gpu = false;
			sp = false;
		}

		timeSeriesSize = Optional.ofNullable(tsSize).map(size -> (int) (200000 * Double.parseDouble(size))).orElse(-1);
		timeSeriesCount = Optional.ofNullable(tsCount).map(Integer::parseInt).orElse(30);

		// TODO  This should not have to be here; only the NativeCompiler needs to know this
		String exec = System.getProperty("AR_HARDWARE_NATIVE_EXECUTION");
		if (exec == null) exec = System.getenv("AR_HARDWARE_NATIVE_EXECUTION");

		if (sp) {
			local = new Hardware(memProvider, "cl".equalsIgnoreCase(memProvider),
						gpu, enableKernels, enableDestinationConsolidation, false,
						"external".equalsIgnoreCase(exec), location);
		} else {
			local = new Hardware(memProvider, "cl".equalsIgnoreCase(memProvider),
						gpu, enableKernels, enableDestinationConsolidation,
						"external".equalsIgnoreCase(exec), location);
		}
	}

	private final String name;

	private final boolean enableGpu, enableKernelQueue = false;
	private final boolean enableDoublePrecision;
	private final boolean enableKernel, enableDestinationConsolidation;
	private final boolean externalNative, nativeMemory;
	private final boolean memVolatile;
	private long memoryMax;
	private Location location;
	private cl_platform_id platform;
	private cl_device_id device, kernelDevice;

	private DataContext context;
	private List<ContextListener> contextListeners;

	private AcceleratedFunctions functions;
	
	private Hardware(String memProvider, boolean enableCl, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation, boolean externalNative,
					 Location location) {
		this(memProvider, enableCl, enableGpu, enableKernels, enableDestinationConsolidation, !enableGpu, externalNative, location);
	}

	private Hardware(String memProvider, boolean enableCl, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation,
					 boolean enableDoublePrecision, boolean externalNative, Location location) {
		this(enableDoublePrecision ? "local64" : "local32", memProvider, enableCl, enableGpu,
				enableKernels, enableDestinationConsolidation, enableDoublePrecision, externalNative, location);
	}

	private Hardware(String name, String memProvider, boolean enableCl, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation, boolean externalNative,
					 Location location) {
		this(name, memProvider, enableCl, enableGpu, enableKernels, enableDestinationConsolidation, !enableGpu, externalNative, location);
	}

	private Hardware(String name, String memProvider, boolean enableCl, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation,
					 boolean enableDoublePrecision, boolean externalNative, Location location) {
		this.name = name;

		this.memoryMax = (long) Math.pow(2, getMemoryScale()) * 256L * 1000L * 1000L;
		if (enableDoublePrecision) this.memoryMax = memoryMax * 2;

		this.location = location;

		this.enableGpu = enableGpu;
		this.enableDoublePrecision = enableDoublePrecision;
		this.enableKernel = enableKernels;
		this.enableDestinationConsolidation = enableDestinationConsolidation;
		this.externalNative = externalNative;
		this.nativeMemory = "native".equalsIgnoreCase(memProvider);
		this.memVolatile = location == Location.HEAP;
		this.contextListeners = new ArrayList<>();

		if (enableCl) {
			this.context = new CLDataContext(this, name, this.memoryMax, this.location);

			CL.setExceptionsEnabled(true);

			if (enableVerbose) {
				if (enableGpu) {
					System.out.println("Initializing Hardware (GPU Enabled)...");
				} else {
					System.out.println("Initializing Hardware...");
				}
			}

			System.out.println("Hardware[" + name + "]: Max RAM is " +
					memoryMax / 1000000 + " Megabytes");
			if (location == CLMemoryProvider.Location.HEAP) System.out.println("Hardware[" + name + "]: Heap RAM enabled");
			if (location == CLMemoryProvider.Location.HOST) System.out.println("Hardware[" + name + "]: Host RAM enabled");
			if (ENABLE_POOLING) System.out.println("Hardware[" + name + "]: Pooling enabled");

			start(context);
			contextListeners.forEach(l -> l.contextStarted(context));
		} else {
			System.out.println("Initializing Hardware...");
		}

		if (!enableCl) {
			this.context = new NativeDataContext(this, name, enableDoublePrecision, isNativeMemory(), externalNative, this.memoryMax);
			start(context);
			if (enableVerbose) System.out.println("Hardware[" + name + "]: Created NativeMemoryProvider");
		}

		if (timeSeriesSize > 0) {
			System.out.println("Hardware[" + name + "]: " + timeSeriesCount + " x " +
					2 * timeSeriesSize * getNumberSize() / 1024 + "kb timeseries(s) requested");
		}
	}

	public String getName() { return name; }

	public static Hardware getLocalHardware() { return local; }

	public void setMaximumOperationDepth(int depth) { OperationList.setMaxDepth(depth); }

	protected void identifyDevices() {
		if (platform != null && device != null) return;

		final int platformIndex = 0;
		final int deviceIndex = 0;
		final long deviceType = enableGpu ? CL.CL_DEVICE_TYPE_GPU : CL.CL_DEVICE_TYPE_CPU;

		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		if (enableVerbose) System.out.println("Hardware[" + name + "]: " + numPlatforms + " platforms available");

		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		platform = platforms[platformIndex];

		if (enableVerbose)
			System.out.println("Hardware[" + name + "]: Using platform " + platformIndex + " -- " + platform);

		/* Main Device Selection */

		int numDevicesArray[] = new int[1];
		CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
		int numDevices = numDevicesArray[0];

		if (enableVerbose)
			System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(deviceType) + "(s) available");

		cl_device_id devices[] = new cl_device_id[numDevices];
		CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
		device = devices[deviceIndex];

		System.out.println("Hardware[" + name + "]: Using " + deviceName(deviceType) + " " + deviceIndex);

		/* Kernel Device Selection */

		if (enableKernelQueue) {
			CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
			numDevices = numDevicesArray[0];

			if (enableVerbose)
				System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(CL.CL_DEVICE_TYPE_GPU) + "(s) available for kernels");

			if (numDevices > 0) {
				CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, numDevices, devices, null);
				kernelDevice = devices[deviceIndex];

				System.out.println("Hardware[" + name + "]: Using " + deviceName(CL.CL_DEVICE_TYPE_GPU) + " " + deviceIndex + " for kernels");
			}
		}
	}

	protected void start(DataContext ctx) {
		if (ctx instanceof CLDataContext) {
			identifyDevices();
			((CLDataContext) ctx).init(platform, device, kernelDevice);
		} else if (ctx instanceof NativeDataContext) {
			((NativeDataContext) ctx).init();
		}
	}

	public void addContextListener(ContextListener l) { contextListeners.add(l); }

	public void removeContextListener(ContextListener l) { contextListeners.remove(l); }

	public <T> T dataContext(Callable<T> exec) {
		DataContext current, next;

		if (context instanceof CLDataContext) {
			current = context;
			next = new CLDataContext(this, getName(), memoryMax, location);
		} else if (context instanceof NativeDataContext) {
			current = context;
			next = new NativeDataContext(this, getName(), isDoublePrecision(), isNativeMemory(), isExternalNative(), memoryMax);
		} else {
			return null;
		}

		String dcName = next.toString();
		if (dcName.contains(".")) {
			dcName = dcName.substring(dcName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + dcName);
			start(next);
			context = next;
			contextListeners.forEach(l -> l.contextStarted(context));
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			contextListeners.forEach(l -> l.contextDestroyed(next));
			context = current;
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + dcName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + dcName);
		}
	}

	public boolean isGPU() { return enableGpu; }

	public boolean isDoublePrecision() { return enableDoublePrecision; }

	public boolean isDestinationConsolidation() { return enableDestinationConsolidation; }

	public boolean isKernelSupported() { return enableKernel; }

	public boolean isExternalNative() { return externalNative; }

	public boolean isNativeMemory() { return nativeMemory; }

	public boolean isMemoryVolatile() { return memVolatile; }

	public String getNumberTypeName() { return isDoublePrecision() ? "double" : "float"; }

	public int getNumberSize() { return isDoublePrecision() ? Sizeof.cl_double : Sizeof.cl_float; }

	public int getMemoryScale() { return MEMORY_SCALE; }

	public int getDefaultPoolSize() { return ENABLE_POOLING ? 6250 * (int) Math.pow(2, MEMORY_SCALE) : -1; }

	public int getTimeSeriesSize() { return (int) timeSeriesSize; }

	public int getTimeSeriesCount() { return timeSeriesCount; }

	public String stringForDouble(double d) {
		return "(" + getNumberTypeName() + ") " + rawStringForDouble(d);
	}

	private String rawStringForDouble(double d) {
		if (isGPU()) {
			Float f = (float) d;
			if (f.isInfinite()) {
				return String.valueOf(f > 0 ? Float.MAX_VALUE : Float.MIN_VALUE);
			} else if (f.isNaN()) {
				return "0.0";
			}

			return String.valueOf((float) d);
		} else {
			Double v = d;
			if (v.isInfinite()) {
				return String.valueOf(v > 0 ? Double.MAX_VALUE : Double.MIN_VALUE);
			} else if (v.isNaN()) {
				return "0.0";
			}

			return String.valueOf(d);
		}
	}

	protected double doubleForString(String s) {
		s = s.trim();
		while (s.startsWith("(double)") || s.startsWith("(float)")) {
			if (s.startsWith("(double)")) {
				s = s.substring(8).trim();
			} else if (s.startsWith("(float)")) {
				s = s.substring(7).trim();
			}
		}

		return Double.parseDouble(s);
	}

	@Deprecated
	public synchronized AcceleratedFunctions getFunctions() {
		if (functions == null) {
			if (enableVerbose) System.out.println("Hardware[" + getName() + "]: Loading accelerated functions");
			functions = new AcceleratedFunctions();
			functions.init(this, loadSource(getName()));
			System.out.println("Hardware[" + getName() + "]: Accelerated functions loaded for " + getName());
		}

		return functions;
	}

	public DataContext getDataContext() { return context; }

	public ComputeContext getComputeContext() { return context.getComputeContext(); }

	public CLDataContext getClDataContext() { return context instanceof CLDataContext ? (CLDataContext) context : null; }

	public CLComputeContext getClComputeContext() {
		if (getDataContext().getComputeContext() instanceof CLComputeContext)
			return (CLComputeContext) getDataContext().getComputeContext();
		return null;
	}

	public MemoryProvider getMemoryProvider() { return context.getMemoryProvider(); }

	private static String deviceName(long type) {
		if (type == CL.CL_DEVICE_TYPE_CPU) {
			return "CPU";
		} else if (type == CL.CL_DEVICE_TYPE_GPU) {
			return "GPU";
		} else {
			throw new IllegalArgumentException("Unknown device type " + type);
		}
	}

	protected String loadSource() {
		return loadSource(enableDoublePrecision ? "local64" : "local32");
	}

	protected String loadSource(String name) {
		return loadSource(Hardware.class.getClassLoader().getResourceAsStream(name + ".cl"), false);
	}

	protected String loadSource(InputStream is) {
		return loadSource(is, true);
	}

	protected String loadSource(InputStream is, boolean includeLocal) {
		if (is == null) {
			throw new IllegalArgumentException("InputStream is null");
		}

		StringBuilder buf = new StringBuilder();

		if (includeLocal) {
			buf.append(loadSource());
			buf.append("\n");
		}

		try (BufferedReader in =
					 new BufferedReader(new InputStreamReader(is))) {
			String line;

			while ((line = in.readLine()) != null) {
				buf.append(line); buf.append("\n");
			}
		} catch (IOException e) {
			Issues.warn(null, "Unable to load kernel program source", e);
		}

		return buf.toString();
	}
}
