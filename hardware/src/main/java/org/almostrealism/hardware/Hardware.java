/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.expression.Cast;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.kernel.KernelPreferences;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.ctx.ContextListener;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.io.SystemUtils;
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
	public static boolean enableCast = false;
	public static final boolean enableMultiThreading = true;
	public static boolean enableKernelOps;

	protected static final int MEMORY_SCALE;
	protected static final boolean ENABLE_POOLING;

	protected static final long timeSeriesSize;
	protected static final int timeSeriesCount;

	private static final boolean enableAsync = SystemUtils.isEnabled("AR_HARDWARE_ASYNC").orElse(false);

	private static final Hardware local;

	static {
		boolean aarch = SystemUtils.isAarch64();

		boolean gpu = "gpu".equalsIgnoreCase(System.getenv("AR_HARDWARE_PLATFORM")) ||
				"gpu".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PLATFORM"));

		boolean enableKernels = SystemUtils.isEnabled("AR_ENABLE_KERNELS").orElse(true);
		enableKernelOps = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_OPS").orElse(true);

		boolean enableDestinationConsolidation =
				SystemUtils.isEnabled("AR_HARDWARE_DESTINATION_CONSOLIDATION").orElse(false);

		Precision precision = Precision.FP64;

		if ("16".equalsIgnoreCase(System.getenv("AR_HARDWARE_PRECISION")) ||
				"16".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PRECISION"))) {
			precision = Precision.FP16;
		} else if ("32".equalsIgnoreCase(System.getenv("AR_HARDWARE_PRECISION")) ||
				"32".equalsIgnoreCase(System.getProperty("AR_HARDWARE_PRECISION"))) {
			precision = Precision.FP32;
		} else if (precision == Precision.FP64 && gpu) {
			precision = Precision.FP32;
		}

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
		// if (tsSize == null) tsSize = "100";

		String tsCount = System.getProperty("AR_HARDWARE_TIMESERIES_COUNT");
		if (tsCount == null) tsCount = System.getenv("AR_HARDWARE_TIMESERIES_COUNT");
		if (tsCount == null) tsCount = "24";

		String driver = System.getProperty("AR_HARDWARE_DRIVER");
		if (driver == null) driver = System.getenv("AR_HARDWARE_DRIVER");
		if (driver == null) driver = aarch ? "mtl" : "cl";

		ComputeRequirement requirement = ComputeRequirement.CL;
		if ("mtl".equalsIgnoreCase(driver)) {
			requirement = ComputeRequirement.MTL;
		} else if ("native".equalsIgnoreCase(driver)) {
			requirement = ComputeRequirement.JNI;
		}

		String memProvider = System.getProperty("AR_HARDWARE_MEMORY_PROVIDER");
		if (memProvider == null) memProvider = System.getenv("AR_HARDWARE_MEMORY_PROVIDER");
		if (memProvider == null) {
			if (requirement == ComputeRequirement.CL) {
				memProvider = "cl";
			} else if (requirement == ComputeRequirement.MTL) {
				memProvider = "mtl";
			} else if (requirement == ComputeRequirement.JNI) {
				memProvider = "native";
			} else {
				throw new IllegalStateException("No memory provider for " + requirement);
			}
		}
		if (memProvider.equalsIgnoreCase("native") || memProvider.equalsIgnoreCase("jvm")) {
			gpu = false;
			precision = Precision.FP64;
		} else if (memProvider.equalsIgnoreCase("mtl") && precision == Precision.FP64) {
			precision = Precision.FP32;
		}

		if (memProvider.equalsIgnoreCase("mtl")) {
			KernelPreferences.setPreferLoops(true);
			KernelPreferences.setEnableSubdivision(false);
		}

		timeSeriesSize = Optional.ofNullable(tsSize).map(size -> (int) (200000 * Double.parseDouble(size))).orElse(-1);
		timeSeriesCount = Optional.ofNullable(tsCount).map(Integer::parseInt).orElse(30);

		// TODO  This should not have to be here; only the NativeCompiler needs to know this
		String exec = System.getProperty("AR_HARDWARE_NATIVE_EXECUTION");
		if (exec == null) exec = System.getenv("AR_HARDWARE_NATIVE_EXECUTION");

		local = new Hardware(memProvider, requirement,
						gpu, enableKernels, enableDestinationConsolidation, precision,
						"external".equalsIgnoreCase(exec), location);

		// TODO  This is not a very desirable way of ensuring the doubles are properly encoded
		// TODO  but until we further improve the interaction between org.almostrealism.hardware
		// TODO  and io.almostrealism.code it will have to do
		Expression.toDouble = e -> new Cast(Double.class, Hardware.getLocalHardware().getNumberTypeName(), e);
		DoubleConstant.stringForDouble = Hardware.getLocalHardware()::stringForDouble;

		// TODO  This is not a very desirable way to configure kernel support either
		KernelIndex.kernelIndex = KernelSupport::getKernelIndex;
	}

	private final String name;

	private final boolean enableGpu, enableKernelQueue = false;
	private final boolean enableKernel, enableDestinationConsolidation;
	private final boolean externalNative, nativeMemory;
	private final boolean memVolatile;
	private long memoryMax;
	private Precision precision;
	private Location location;

	private DataContext<MemoryData> context;
	private List<ContextListener> contextListeners;

	private AcceleratedFunctions functions;

	private Hardware(String memProvider, ComputeRequirement type, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation,
					 Precision precision, boolean externalNative, Location location) {
		this(precision == Precision.FP64 ? "local64" : "local32", memProvider, type, enableGpu,
				enableKernels, enableDestinationConsolidation, precision, externalNative, location);
	}

	private Hardware(String name, String memProvider, ComputeRequirement type, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation,
					 Precision precision, boolean externalNative, Location location) {
		this.name = name;

		this.memoryMax = (long) Math.pow(2, getMemoryScale()) * 64L * 1000L * 1000L;
		this.memoryMax = memoryMax * precision.bytes();

		this.precision = precision;
		this.location = location;

		this.enableGpu = enableGpu;
		this.enableKernel = enableKernels;
		this.enableDestinationConsolidation = enableDestinationConsolidation;
		this.externalNative = externalNative;
		this.nativeMemory = "native".equalsIgnoreCase(memProvider);
		this.memVolatile = location == Location.HEAP;
		this.contextListeners = new ArrayList<>();

		if (type == ComputeRequirement.CL) {
			this.context = new CLDataContext(this, name, this.memoryMax, getOffHeapSize(), this.location);

			if (enableVerbose) {
				if (enableGpu) {
					System.out.println("Initializing Hardware (GPU Enabled)...");
				} else {
					System.out.println("Initializing Hardware...");
				}
			}

			System.out.println("Hardware[" + name + "]: Max RAM is " +
					memoryMax / 1000000 + " Megabytes");
			if (location == CLMemoryProvider.Location.HEAP)
				System.out.println("Hardware[" + name + "]: Heap RAM enabled");
			if (location == CLMemoryProvider.Location.HOST)
				System.out.println("Hardware[" + name + "]: Host RAM enabled");
			if (ENABLE_POOLING) System.out.println("Hardware[" + name + "]: Pooling enabled");

			start(context);
			contextListeners.forEach(l -> l.contextStarted(context));
		} else if (type == ComputeRequirement.MTL) {
			int offHeapSize = 0; // TODO getOffHeapSize();
			this.context = new MetalDataContext(this, name, this.memoryMax, offHeapSize);

			if (enableVerbose) {
				if (enableGpu) {
					System.out.println("Initializing Hardware (GPU Enabled)...");
				} else {
					System.out.println("Initializing Hardware...");
				}
			}

			System.out.println("Hardware[" + name + "]: Max RAM is " +
					memoryMax / 1000000 + " Megabytes");
			if (ENABLE_POOLING) System.out.println("Hardware[" + name + "]: Pooling enabled");

			start(context);
			contextListeners.forEach(l -> l.contextStarted(context));
		} else {
			System.out.println("Initializing Hardware...");
			this.context = new NativeDataContext(this, name, precision == Precision.FP64, isNativeMemory(), externalNative, this.memoryMax);
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

	protected void start(DataContext ctx) {
		if (ctx instanceof CLDataContext) {
			((CLDataContext) ctx).init(enableGpu, enableKernelQueue);
		} else if (ctx instanceof MetalDataContext) {
			((MetalDataContext) ctx).init(enableGpu, enableKernelQueue);
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
			next = new CLDataContext(this, getName(), memoryMax, getOffHeapSize(), location);
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

	@Deprecated
	public boolean isDoublePrecision() { return precision == Precision.FP64; }

	public Precision getPrecision() { return precision; }

	public boolean isDestinationConsolidation() { return enableDestinationConsolidation; }

	public boolean isKernelSupported() { return enableKernel; }

	public boolean isExternalNative() { return externalNative; }

	public boolean isNativeMemory() { return nativeMemory; }

	public boolean isMemoryVolatile() { return memVolatile; }

	public String getNumberTypeName() {
		switch (precision) {
			case FP16:
				return "bfloat";
			case FP32:
				return "float";
			case FP64:
				return "double";
			default:
				return "float";
		}
	}

	public int getNumberSize() { return precision.bytes(); }

	public int getMemoryScale() { return MEMORY_SCALE; }

	public int getOffHeapSize() {
		try {
			return Integer.parseInt(SystemUtils.getProperty("AR_HARDWARE_OFF_HEAP_SIZE"));
		} catch (NullPointerException | NumberFormatException e) {
			return 1024;
		}
	}

	public int getDefaultPoolSize() { return ENABLE_POOLING ? 6250 * (int) Math.pow(2, MEMORY_SCALE) : -1; }

	public int getTimeSeriesSize() { return (int) timeSeriesSize; }

	public int getTimeSeriesCount() { return timeSeriesCount; }

	public String stringForDouble(double d) {
		if (enableCast) {
			return "(" + getNumberTypeName() + ") " + rawStringForDouble(d);
		} else {
			return rawStringForDouble(d);
		}
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
		while (s.startsWith("(double)") || s.startsWith("(float)") || s.startsWith("(bfloat)") || s.startsWith("(half)")) {
			if (s.startsWith("(double)")) {
				s = s.substring(8).trim();
			} else if (s.startsWith("(float)")) {
				s = s.substring(7).trim();
			} else if (s.startsWith("(bfloat)")) {
				s = s.substring(8).trim();
			} else if (s.startsWith("(half)")) {
				s = s.substring(6).trim();
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

	public DataContext<MemoryData> getDataContext() { return context; }

	public ComputeContext<MemoryData> getComputeContext() { return context.getComputeContext(); }

	public CLDataContext getClDataContext() { return context instanceof CLDataContext ? (CLDataContext) context : null; }

	public CLComputeContext getClComputeContext() {
		if (getDataContext().getComputeContext() instanceof CLComputeContext)
			return (CLComputeContext) getDataContext().getComputeContext();
		return null;
	}

	public MemoryProvider getMemoryProvider(int size) { return context.getMemoryProvider(size); }

	protected String loadSource() {
		return loadSource(precision == Precision.FP64 ? "local64" : "local32");
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

	public static boolean isAsync() { return enableAsync; }
}
