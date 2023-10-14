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
import io.almostrealism.code.Computer;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.expression.Cast;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.kernel.KernelPreferences;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.ctx.ContextListener;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.io.SystemUtils;

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

		local = new Hardware(memProvider, List.of(requirement),
						gpu, enableKernels, enableDestinationConsolidation, precision,
						location);

		// TODO  This is not a very desirable way of ensuring that Expressions are properly encoded
		// TODO  but until we further improve the interaction between org.almostrealism.hardware
		// TODO  and io.almostrealism.code it will have to do
		Expression.toDouble = e -> new Cast(Double.class, Hardware.getLocalHardware().getNumberTypeName(), e);
		DoubleConstant.stringForDouble = Hardware.getLocalHardware()::stringForDouble;
		KernelIndex.kernelIndex = Hardware.getLocalHardware().getComputeContext()::getKernelIndex;
	}

	private final String name;

	private final boolean enableGpu, enableKernelQueue = false;
	private final boolean enableKernel, enableDestinationConsolidation;
	private final boolean nativeMemory;
	private final boolean memVolatile;
	private long memoryMax;
	private Precision precision;
	private Location location;

	private List<DataContext<MemoryData>> contexts;
	private ThreadLocal<DataContext<MemoryData>> explicitContext = new ThreadLocal<>();
	private List<ContextListener> contextListeners;

	private Hardware(String memProvider, List<ComputeRequirement> type, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation,
					 Precision precision, Location location) {
		this(precision == Precision.FP64 ? "local64" : "local32", memProvider, type, enableGpu,
				enableKernels, enableDestinationConsolidation, precision, location);
	}

	private Hardware(String name, String memProvider, List<ComputeRequirement> type, boolean enableGpu,
					 boolean enableKernels, boolean enableDestinationConsolidation,
					 Precision precision, Location location) {
		this.name = name;

		this.memoryMax = (long) Math.pow(2, getMemoryScale()) * 64L * 1000L * 1000L;
		this.memoryMax = memoryMax * precision.bytes();

		this.precision = precision;
		this.location = location;

		this.enableGpu = enableGpu;
		this.enableKernel = enableKernels;
		this.enableDestinationConsolidation = enableDestinationConsolidation;
		this.nativeMemory = "native".equalsIgnoreCase(memProvider);
		this.memVolatile = location == Location.HEAP;
		this.contextListeners = new ArrayList<>();
		this.contexts = new ArrayList<>();

		processRequirements(type);
	}

	private void processRequirements(List<ComputeRequirement> requirements) {
		if (enableVerbose) {
			System.out.println("Hardware[" + getName() + "]: Processing Hardware Requirements...");
		}

		for (ComputeRequirement type : requirements) {
			if (type == ComputeRequirement.CPU) {
				// TODO  Choose the ideal CPU implementation for this system
				type = ComputeRequirement.CL;
			} else if (type == ComputeRequirement.GPU) {
				// TODO  Choose the ideal GPU implementation for this system
				type = SystemUtils.isAarch64() ? ComputeRequirement.MTL : ComputeRequirement.CL;
			}

			boolean locationUsed = false;
			DataContext ctx;

			if (type == ComputeRequirement.CL) {
				ctx = new CLDataContext(this, getName(), this.memoryMax, getOffHeapSize(type), this.location);
				locationUsed = true;
			} else if (type == ComputeRequirement.MTL) {
				ctx = new MetalDataContext(this, getName(), this.memoryMax, getOffHeapSize(type));
			} else {
				ctx = new NativeDataContext(this, getName(), isNativeMemory(), this.memoryMax);
				if (enableVerbose) System.out.println("Hardware[" + getName() + "]: Created NativeDataContext");
			}

			if (locationUsed) {
				if (location == CLMemoryProvider.Location.HEAP)
					System.out.println("Hardware[" + getName() + "]: Heap RAM enabled");
				if (location == CLMemoryProvider.Location.HOST)
					System.out.println("Hardware[" + getName() + "]: Host RAM enabled");
			}

			System.out.println("Hardware[" + getName() + "]: Max RAM is " +
					memoryMax / 1000000 + " Megabytes");

			start(ctx);
			contexts.add(ctx);
			contextListeners.forEach(l -> l.contextStarted(ctx));
		}
	}

	public String getName() { return name; }

	public static Hardware getLocalHardware() { return local; }

	public static Computer<MemoryData> getComputer() {
		// TODO  Need a smarter choice for which context to use, depending on the computation
		return new DefaultComputer(computation -> {
			return getLocalHardware().getComputeContext();
		});
	}

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
		DataContext<MemoryData> next, current = explicitContext.get();

		if (getDataContext() instanceof CLDataContext) {
			next = new CLDataContext(this, getName(), memoryMax, getOffHeapSize(ComputeRequirement.CL), location);
		} else if (getDataContext() instanceof MetalDataContext) {
			next = new MetalDataContext(this, getName(), memoryMax, getOffHeapSize(ComputeRequirement.MTL));
		} else if (getDataContext() instanceof NativeDataContext) {
			next = new NativeDataContext(this, getName(), isNativeMemory(), memoryMax);
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
			explicitContext.set(next);
			contextListeners.forEach(l -> l.contextStarted(getDataContext()));
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			contextListeners.forEach(l -> l.contextDestroyed(next));
			explicitContext.set(current);
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + dcName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + dcName);
		}
	}

	public Precision getPrecision() { return precision; }

	public boolean isDestinationConsolidation() { return enableDestinationConsolidation; }

	public boolean isKernelSupported() { return enableKernel; }

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

	public int getOffHeapSize(ComputeRequirement type) {
		try {
			if (type == ComputeRequirement.MTL) {
				// TODO  This workaround should not be required
				return 0;
			}

			return Integer.parseInt(SystemUtils.getProperty("AR_HARDWARE_OFF_HEAP_SIZE"));
		} catch (NullPointerException | NumberFormatException e) {
			return 1024;
		}
	}

	public String stringForDouble(double d) {
		if (enableCast) {
			return "(" + getNumberTypeName() + ") " + rawStringForDouble(d);
		} else {
			return rawStringForDouble(d);
		}
	}

	private String rawStringForDouble(double d) {
		if (getPrecision() != Precision.FP64) {
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

	public DataContext<MemoryData> getDataContext() {
		DataContext<MemoryData> ctx = explicitContext.get();
		if (ctx != null) return ctx;
		if (contexts.isEmpty()) return null;
		return contexts.get(0);
	}

	public ComputeContext<MemoryData> getComputeContext() { return getDataContext().getComputeContext(); }

	public MemoryProvider getMemoryProvider(int size) { return getDataContext().getMemoryProvider(size); }

	public static boolean isAsync() { return enableAsync; }
}
