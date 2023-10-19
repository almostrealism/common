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
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.ParallelProcess;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.ctx.ContextListener;
import org.almostrealism.hardware.external.ExternalComputeContext;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class Hardware {
	public static boolean enableVerbose = false;
	public static final boolean enableMultiThreading = true;
	public static boolean enableKernelOps;

	protected static final int MEMORY_SCALE;

	private static final boolean enableAsync = SystemUtils.isEnabled("AR_HARDWARE_ASYNC").orElse(false);

	private static final Hardware local;

	static {
		boolean aarch = SystemUtils.isAarch64();

		enableKernelOps = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_OPS").orElse(true);

		boolean enableDestinationConsolidation =
				SystemUtils.isEnabled("AR_HARDWARE_DESTINATION_CONSOLIDATION").orElse(false);

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
		if (driver == null) driver = "*";

		boolean nativeMemory = SystemUtils.isEnabled("AR_HARDWARE_NATIVE_MEMORY").orElse(true);

		List<ComputeRequirement> requirements = new ArrayList<>();

		if ("cl".equalsIgnoreCase(driver)) {
			requirements.add(ComputeRequirement.CL);
		} else if ("mtl".equalsIgnoreCase(driver)) {
			requirements.add(ComputeRequirement.MTL);
		} else if ("native".equalsIgnoreCase(driver)) {
			requirements.add(ComputeRequirement.JNI);
		} else if ("*".equalsIgnoreCase(driver)) {
			if (aarch) {
				requirements.add(ComputeRequirement.JNI);
				requirements.add(ComputeRequirement.MTL);
				requirements.add(ComputeRequirement.CL);
			} else {
				requirements.add(ComputeRequirement.CL);
				if (!SystemUtils.isMacOS())
					requirements.add(ComputeRequirement.JNI);
			}
		} else {
			throw new IllegalStateException("Unknown driver " + driver);
		}

		boolean favorLoops = requirements.size() == 1;

		if (favorLoops && requirements.contains(ComputeRequirement.MTL)) {
			KernelPreferences.setPreferLoops(true);
			KernelPreferences.setEnableSubdivision(false);
		}

		local = new Hardware(requirements, nativeMemory,
							enableDestinationConsolidation,
							location);
	}

	private final String name;

	private final boolean enableDestinationConsolidation;
	private final boolean nativeMemory;
	private final boolean memVolatile;
	private long maxReservation;
	private Location location;

	private List<DataContext<MemoryData>> contexts;
	private ThreadLocal<DataContext<MemoryData>> explicitDataCtx = new ThreadLocal<>();
	private ThreadLocal<ComputeContext<MemoryData>> explicitComputeCtx = new ThreadLocal<>();
	private List<ContextListener> contextListeners;

	private Hardware(List<ComputeRequirement> type, boolean nativeMemory,
					 boolean enableDestinationConsolidation,
					 Location location) {
		this("local", type, nativeMemory,
				enableDestinationConsolidation, location);
	}

	private Hardware(String name, List<ComputeRequirement> type, boolean nativeMemory,
					 boolean enableDestinationConsolidation,
					 Location location) {
		this.name = name;
		this.maxReservation = (long) Math.pow(2, getMemoryScale()) * 64L * 1000L * 1000L;
		this.location = location;

		this.enableDestinationConsolidation = enableDestinationConsolidation;
		this.nativeMemory = nativeMemory;
		this.memVolatile = location == Location.HEAP;
		this.contextListeners = new ArrayList<>();
		this.contexts = new ArrayList<>();

		processRequirements(type);
	}

	private void processRequirements(List<ComputeRequirement> requirements) {
		if (enableVerbose) {
			System.out.println("Hardware[" + getName() + "]: Processing Hardware Requirements...");
		}

		List<ComputeRequirement> done = new ArrayList<>();

		r: for (ComputeRequirement type : requirements) {
			if (type == ComputeRequirement.CPU) {
				// TODO  Choose the ideal CPU implementation for this system
				type = ComputeRequirement.CL;
			} else if (type == ComputeRequirement.GPU) {
				// TODO  Choose the ideal GPU implementation for this system
				type = SystemUtils.isAarch64() ? ComputeRequirement.MTL : ComputeRequirement.CL;
			}

			if (done.contains(type)) continue r;

			boolean locationUsed = false;
			DataContext ctx;
			String cname;

			if (type == ComputeRequirement.CL) {
				ctx = new CLDataContext(this, getName(), this.maxReservation, getOffHeapSize(type), this.location);
				locationUsed = true;
				cname = "CL";
			} else if (type == ComputeRequirement.MTL) {
				ctx = new MetalDataContext(this, getName(), this.maxReservation, getOffHeapSize(type));
				cname = "MTL";
			} else {
				ctx = new NativeDataContext(this, getName(), isNativeMemory(), this.maxReservation);
				cname = "JNI";
				if (enableVerbose) System.out.println("Hardware[" + getName() + "]: Created NativeDataContext");
			}

			if (locationUsed) {
				if (location == CLMemoryProvider.Location.HEAP)
					System.out.println("Hardware[" + getName() + "]: Heap RAM enabled");
				if (location == CLMemoryProvider.Location.HOST)
					System.out.println("Hardware[" + getName() + "]: Host RAM enabled");
			}

			System.out.println("Hardware[" + getName() + "]: Max RAM for " + cname + " is " +
					ctx.getPrecision().bytes() * maxReservation / 1000000 + " Megabytes");

			done.add(type);
			ctx.init();
			contexts.add(ctx);
			contextListeners.forEach(l -> l.contextStarted(ctx));
		}
	}

	public String getName() { return name; }

	public Precision getPrecision() {
		Precision precision = Precision.FP64;

		for (DataContext c : contexts) {
			if (c.getPrecision().epsilon() > precision.epsilon()) {
				precision = c.getPrecision();
			}
		}

		return precision;
	}

	public static Hardware getLocalHardware() { return local; }

	public static Computer<MemoryData> getComputer() {
		return new DefaultComputer(computation -> {
			// TODO  Need a smarter choice for which context to use
			int count = ParallelProcess.count(computation);
			return getLocalHardware().getComputeContext(count == 1, count > 128);
		});
	}

	public void setMaximumOperationDepth(int depth) { OperationList.setMaxDepth(depth); }

	public void addContextListener(ContextListener l) { contextListeners.add(l); }
	public void removeContextListener(ContextListener l) { contextListeners.remove(l); }

	public <T> T dataContext(Callable<T> exec) {
		DataContext<MemoryData> next, current = explicitDataCtx.get();

		if (getDataContext() instanceof CLDataContext) {
			next = new CLDataContext(this, "CL", maxReservation, getOffHeapSize(ComputeRequirement.CL), location);
		} else if (getDataContext() instanceof MetalDataContext) {
			next = new MetalDataContext(this,"MTL", maxReservation, getOffHeapSize(ComputeRequirement.MTL));
		} else if (getDataContext() instanceof NativeDataContext) {
			next = new NativeDataContext(this, "JNI", isNativeMemory(), maxReservation);
		} else {
			return null;
		}

		String dcName = next.toString();
		if (dcName.contains(".")) {
			dcName = dcName.substring(dcName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + dcName);
			next.init();
			explicitDataCtx.set(next);
			contextListeners.forEach(l -> l.contextStarted(getDataContext()));
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			contextListeners.forEach(l -> l.contextDestroyed(next));
			explicitDataCtx.set(current);
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + dcName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + dcName);
		}
	}

	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		return Optional.ofNullable(getDataContext(false, false, expectations))
				.map(dc -> {
					ComputeContext<MemoryData> last = explicitComputeCtx.get();

					try {
						explicitComputeCtx.set(dc.getComputeContext());
						return dc.computeContext(exec, expectations);
					} finally {
						explicitComputeCtx.set(last);
					}
				})
				.orElseThrow(() -> new RuntimeException("No DataContext meets the provided ComputeRequirements"));
	}

	public boolean isDestinationConsolidation() { return enableDestinationConsolidation; }

	public boolean isKernelSupported() { return true; }

	public boolean isNativeMemory() { return nativeMemory; }

	public boolean isMemoryVolatile() { return memVolatile; }

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

	public DataContext<MemoryData> getDataContext() {
		return getDataContext(false, false);
	}

	public DataContext<MemoryData> getDataContext(boolean sequential, boolean accelerator, ComputeRequirement... requirements) {
		ComputeContext<MemoryData> cc = explicitComputeCtx.get();
		DataContext<MemoryData> ctx = cc == null ? explicitDataCtx.get() : cc.getDataContext();

		if (ctx != null) {
			return filterContexts(List.of(ctx), requirements).stream().findAny().orElseThrow(UnsupportedOperationException::new);
		}

		List<DataContext<MemoryData>> filtered = filterContexts(contexts, requirements);

		if (filtered.isEmpty()) return null;

		if (accelerator) {
			// Favor metal
			for (DataContext<MemoryData> c : filtered) {
				if (c instanceof MetalDataContext) {
					return c;
				}
			}

			// Fallback to CL
			for (DataContext<MemoryData> c : filtered) {
				if (c instanceof CLDataContext) {
					return c;
				}
			}
		}

		if (sequential) {
			// Favor JNI
			for (DataContext<MemoryData> c : filtered) {
				if (c instanceof NativeDataContext) {
					return c;
				}
			}
		}

		return filtered.get(0);
	}

	public ComputeContext<MemoryData> getComputeContext() {
		return getComputeContext(false, false);
	}

	public ComputeContext<MemoryData> getComputeContext(ComputeRequirement... requirements) {
		return getComputeContext(false, false, requirements);
	}

	public ComputeContext<MemoryData> getComputeContext(boolean sequential, boolean accelerator, ComputeRequirement... requirements) {
		return Optional.ofNullable(getDataContext(sequential, accelerator, requirements)).map(dc -> dc.getComputeContext())
				.orElseThrow(() -> new RuntimeException("No available data context"));
	}

	public MemoryProvider<? extends Memory> getMemoryProvider(int size) {
		return Optional.ofNullable(getDataContext()).map(dc -> dc.getMemoryProvider(size))
				.orElseThrow(() -> new RuntimeException("No available data context"));
	}

	private static List<DataContext<MemoryData>> filterContexts(List<DataContext<MemoryData>> contexts, ComputeRequirement... requirements) {
		List<DataContext<MemoryData>> filtered = new ArrayList<>();

		d: for (DataContext<MemoryData> c : contexts) {
			for (ComputeRequirement r : requirements) {
				if (!supported(c, r)) {
					continue d;
				}
			}

			filtered.add(c);
		}

		return filtered;
	}

	private static boolean supported(DataContext<MemoryData> context, ComputeRequirement requirement) {
		switch (requirement) {
			case CPU:
				return context.getComputeContext().isCPU();
			case GPU:
				return !context.getComputeContext().isCPU();
			case FPGA:
				return false;
			case C:
				return context instanceof NativeDataContext;
			case CL:
				return context instanceof CLDataContext;
			case MTL:
				return context instanceof MetalDataContext;
			case JNI:
				return context instanceof NativeDataContext;
			case EXTERNAL:
				return context.getComputeContext() instanceof ExternalComputeContext;
			case PROFILING:
				return context.getComputeContext().isProfiling();
			default:
				return false;
		}
	}

	public static boolean isAsync() { return enableAsync; }
}
