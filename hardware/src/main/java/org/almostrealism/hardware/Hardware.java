/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.Computer;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.cl.CLMemory;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeLibrary;
import org.almostrealism.hardware.jni.NativeSupport;
import org.jocl.CL;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An interface to OpenCL. */
public final class Hardware {
	public static boolean enableVerbose = false;
	public static boolean enableMultiThreading = true;

	protected static final int MEMORY_SCALE;
	protected static final boolean ENABLE_POOLING;

	protected static final String LIB_FORMAT;

	protected static final int timeSeriesSize;
	protected static final int timeSeriesCount;

	private static final Hardware local;
	private static final List<Class> libs = new ArrayList<>();

	static {
		boolean gpu = "gpu".equalsIgnoreCase(System.getenv("AR_HARDWARE_PLATFORM")) ||
				"gpu".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PLATFORM"));

		String kernelsEnv = System.getenv("AR_HARDWARE_KERNELS");
		boolean disableKernels = kernelsEnv != null && !"enabled".equalsIgnoreCase(kernelsEnv) ||
				!"enabled".equalsIgnoreCase(System.getProperty("AR_HARDWARE_KERNELS", "enabled"));

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
		boolean heap = "heap".equalsIgnoreCase(memLocation);

		String libFormat = System.getProperty("AR_HARDWARE_LIB_FORMAT");
		if (libFormat == null) libFormat = System.getenv("AR_HARDWARE_LIB_FORMAT");
		LIB_FORMAT = Optional.ofNullable(libFormat).orElse("lib%NAME%.so");

		String tsSize = System.getProperty("AR_HARDWARE_TIMESERIES_SIZE");
		if (tsSize == null) tsSize = System.getenv("AR_HARDWARE_TIMESERIES_SIZE");

		String tsCount = System.getProperty("AR_HARDWARE_TIMESERIES_COUNT");
		if (tsCount == null) tsCount = System.getenv("AR_HARDWARE_TIMESERIES_COUNT");

		String nativeCompiler = System.getProperty("AR_HARDWARE_NATIVE_COMPILER");
		if (nativeCompiler == null) nativeCompiler = System.getenv("AR_HARDWARE_NATIVE_COMPILER");

		String libDir = System.getProperty("AR_HARDWARE_NATIVE_LIBS");
		if (libDir == null) libDir = System.getenv("AR_HARDWARE_NATIVE_LIBS");

		timeSeriesSize = Optional.ofNullable(tsSize).map(size -> (int) (100000 * Double.parseDouble(size))).orElse(-1);
		timeSeriesCount = Optional.ofNullable(tsCount).map(Integer::parseInt).orElse(30);

		if (sp) {
			local = new Hardware(nativeCompiler, libDir, gpu, !disableKernels, false, heap);
		} else {
			local = new Hardware(nativeCompiler, libDir, gpu, !disableKernels, heap);
		}
	}

	private final boolean enableGpu;
	private final boolean enableDoublePrecision;
	private final boolean enableKernel;
	private final boolean memVolatile;

	private final String nativeCompiler;
	private final String libDir;

	private final cl_context context;
	private final cl_command_queue queue;

	private final AcceleratedFunctions functions;
	private final Computer computer;
	private final CLMemoryProvider ram;
	
	private Hardware(String nativeCompiler, String libDir, boolean enableGpu, boolean enableKernels, boolean enableHeap) {
		this(nativeCompiler, libDir, enableGpu, enableKernels, !enableGpu, enableHeap);
	}

	private Hardware(String nativeCompiler, String libDir, boolean enableGpu, boolean enableKernels, boolean enableDoublePrecision, boolean enableHeap) {
		this(enableDoublePrecision ? "local64" : "local32", nativeCompiler, libDir, enableGpu, enableKernels, enableDoublePrecision, enableHeap);
	}

	private Hardware(String name, String nativeCompiler, String libDir, boolean enableGpu, boolean enableKernels, boolean enableHeap) {
		this(name, nativeCompiler, libDir, enableGpu, enableKernels, !enableGpu, enableHeap);
	}

	private Hardware(String name, String nativeCompiler, String libDir, boolean enableGpu, boolean enableKernels, boolean enableDoublePrecision, boolean enableHeap) {
		long memoryMax = (long) Math.pow(2, getMemoryScale()) * 256L * 1000L * 1000L;
		if (enableDoublePrecision) memoryMax = memoryMax * 2;

		this.enableGpu = enableGpu;
		this.enableDoublePrecision = enableDoublePrecision;
		this.enableKernel = enableKernels;
		this.nativeCompiler = nativeCompiler;
		this.libDir = libDir;
		this.memVolatile = enableHeap;

		final int platformIndex = 0;
		final int deviceIndex = 0;
		final long deviceType = enableGpu ? CL.CL_DEVICE_TYPE_GPU : CL.CL_DEVICE_TYPE_CPU;

		CL.setExceptionsEnabled(true);

		if (enableGpu) {
			System.out.println("Initializing Hardware (GPU Enabled)...");
		} else {
			System.out.println("Initializing Hardware...");
		}

		System.out.println("Hardware[" + name + "]: Max RAM is " +
				memoryMax / 1000000 + " Megabytes");
		if (enableHeap) System.out.println("Hardware[" + name + "]: Heap RAM enabled");

		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		if (enableVerbose) System.out.println("Hardware[" + name + "]: " + numPlatforms + " platforms available");

		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		cl_platform_id platform = platforms[platformIndex];

		if (enableVerbose) System.out.println("Hardware[" + name + "]: Using platform " + platformIndex + " -- " + platform);

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		int numDevicesArray[] = new int[1];
		CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
		int numDevices = numDevicesArray[0];

		System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(deviceType) + "(s) available");

		cl_device_id devices[] = new cl_device_id[numDevices];
		CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
		cl_device_id device = devices[deviceIndex];

		System.out.println("Hardware[" + name + "]: Using " + deviceName(deviceType) + " " + deviceIndex);

		context = CL.clCreateContext(contextProperties, 1, new cl_device_id[] { device },
								null, null, null);
		if (enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		queue = CL.clCreateCommandQueue(context, device, 0, null);
		if (enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL command queue initialized");

		if (enableVerbose) System.out.println("Hardware[" + name + "]: Loading accelerated functions");
		functions = new AcceleratedFunctions();
		functions.init(this, loadSource(name));
		System.out.println("Hardware[" + name + "]: Accelerated functions loaded for " + name);

		computer = new DefaultComputer();
		if (enableVerbose) System.out.println("Hardware[" + name + "]: Created DefaultComputer");

		ram = new CLMemoryProvider(getContext(), getNumberSize(), memoryMax, enableHeap);
		if (enableVerbose) System.out.println("Hardware[" + name + "]: Created MemoryProvider");

		if (timeSeriesSize > 0) {
			System.out.println("Hardware[" + name + "]: " + timeSeriesCount + " x " +
					2 * timeSeriesSize * getNumberSize() / 1024 + "kb timeseries(s) available");
		}
	}

	public static Hardware getLocalHardware() { return local; }

	public Computer getComputer() { return computer; }

	public NativeCompiler getNativeCompiler() { return new NativeCompiler(nativeCompiler, libDir, LIB_FORMAT); }

	public synchronized void loadNative(NativeSupport lib) throws IOException, InterruptedException {
		if (libs.contains(lib.getClass())) return;

		getNativeCompiler().compileAndLoad(lib.getClass(), lib.get());
		libs.add(lib.getClass());
	}

	public boolean isGPU() { return enableGpu; }

	public boolean isDoublePrecision() { return enableDoublePrecision; }

	public boolean isKernelSupported() { return enableKernel; }

	public boolean isNativeSupported() { return nativeCompiler != null && libDir != null && LIB_FORMAT != null; }

	public boolean isMemoryVolatile() { return memVolatile; }

	public String getNumberTypeName() { return isDoublePrecision() ? "double" : "float"; }

	public int getNumberSize() { return isDoublePrecision() ? Sizeof.cl_double : Sizeof.cl_float; }

	public int getMemoryScale() { return MEMORY_SCALE; }

	public int getDefaultPoolSize() { return ENABLE_POOLING ? 6250 * (int) Math.pow(2, MEMORY_SCALE) : -1; }

	public int getTimeSeriesSize() { return timeSeriesSize; }

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

	public cl_context getContext() { return context; }

	public cl_command_queue getQueue() { return queue; }

	public AcceleratedFunctions getFunctions() { return functions; }

	public MemoryProvider<RAM> getMemoryProvider() {
		return ram;
	}

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
