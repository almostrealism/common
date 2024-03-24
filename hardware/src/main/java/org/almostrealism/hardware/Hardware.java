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
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.kernel.KernelPreferences;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.ctx.ContextListener;
import org.almostrealism.hardware.external.ExternalComputeContext;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.nio.NativeBufferMemoryProvider;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class Hardware {
	public static boolean enableVerbose = false;
	public static boolean defaultKernelFriendly = true;

	public static Console console = Console.root().child()
			.addFilter(ConsoleFeatures.duplicateFilter(10 * 60 * 1000L));

	protected static final int MEMORY_SCALE;

	private static final boolean enableAsync = SystemUtils.isEnabled("AR_HARDWARE_ASYNC").orElse(false);

	private static final Hardware local;

	static {
		boolean aarch = SystemUtils.isAarch64();

		if (!SystemUtils.isEnabled("AR_HARDWARE_KERNEL_OPS").orElse(true)) {
			throw new UnsupportedOperationException();
		}

		String memScale = System.getProperty("AR_HARDWARE_MEMORY_SCALE");
		if (memScale == null) memScale = System.getenv("AR_HARDWARE_MEMORY_SCALE");
		MEMORY_SCALE = Optional.ofNullable(memScale).map(Integer::parseInt).orElse(4);

		String memLocation = SystemUtils.getProperty("AR_HARDWARE_MEMORY_LOCATION");
		Location location = Location.DEVICE;
		if ("heap".equalsIgnoreCase(memLocation)) {
			location = Location.HEAP;
		} else if ("host".equalsIgnoreCase(memLocation)) {
			location = Location.HOST;
		}

		boolean nioMem = SystemUtils.isEnabled("AR_HARDWARE_NIO_MEMORY").orElse(false);
		if (nioMem) {
			if (memLocation != null && location != Location.DELEGATE) {
				throw new IllegalArgumentException("Cannot use location " + memLocation + " with NIO memory");
			}

			location = Location.DELEGATE;
		}

		String opDepth = SystemUtils.getProperty("AR_HARDWARE_MAX_DEPTH");
		if (opDepth != null) OperationList.setMaxDepth(Integer.parseInt(opDepth));

		String drivers[] = SystemUtils.getProperty("AR_HARDWARE_DRIVER", "*").split(",");

		List<ComputeRequirement> requirements = new ArrayList<>();

		for (String driver : drivers) {
			if ("cl".equalsIgnoreCase(driver)) {
				KernelPreferences.requireUniformPrecision();
				requirements.add(ComputeRequirement.CL);
			} else if ("mtl".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.MTL);
			} else if ("native".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.JNI);
			} else if ("cpu".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.CPU);
			} else if ("gpu".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.GPU);
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

				if (drivers.length <= 1 && requirements.contains(ComputeRequirement.MTL)) {
					KernelPreferences.enableSharedMemory();
				}
			} else {
				throw new IllegalStateException("Unknown driver " + driver);
			}
		}

		local = new Hardware(requirements, location, nioMem);
	}

	private final String name;
	private final boolean memVolatile;
	private long maxReservation;
	private Location location;
	private NativeBufferMemoryProvider nioMemory;

	private DefaultComputer computer;
	private List<DataContext<MemoryData>> contexts;
	private ThreadLocal<DataContext<MemoryData>> explicitDataCtx = new ThreadLocal<>();
	private ThreadLocal<ComputeContext<MemoryData>> explicitComputeCtx = new ThreadLocal<>();
	private final List<WeakReference<ContextListener>> contextListeners;

	private Hardware(List<ComputeRequirement> type, Location location, boolean nioMemory) {
		this("local", type, location, nioMemory);
	}

	private Hardware(String name, List<ComputeRequirement> reqs, Location location, boolean nioMemory) {
		this.name = name;
		this.maxReservation = (long) Math.pow(2, getMemoryScale()) * 64L * 1000L * 1000L;
		this.location = location;
		this.memVolatile = location == Location.HEAP;
		this.contextListeners = Collections.synchronizedList(new ArrayList<>());
		this.contexts = new ArrayList<>();

		int count;

		if (nioMemory) {
			this.nioMemory = new NativeBufferMemoryProvider(Precision.FP32, Precision.FP32.bytes() * maxReservation);
			count = processRequirements(reqs, Precision.FP32);
		} else {
			count = processRequirements(reqs);
		}

		if (count > 0) {
			this.computer = new DefaultComputer(this);
		}

		Thread cleanup = new Thread(
				() -> {
					try {
						while (true) {
							Thread.sleep(10 * 60 * 1000L);

							synchronized (contextListeners) {
								contextListeners.removeIf(f -> f.get() == null);
							}
						}
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				},
				"Hardware WeakReference Cleanup");
		cleanup.setDaemon(true);
		cleanup.start();
	}

	private int processRequirements(List<ComputeRequirement> requirements) {
		Precision precision = Precision.valueOf(SystemUtils.getProperty("AR_HARDWARE_PRECISION", "FP64"));

		if (KernelPreferences.isEnableSharedMemory() || KernelPreferences.isRequireUniformPrecision()) {
			for (ComputeRequirement r : requirements) {
				if (r.getMaximumPrecision().bytes() < precision.bytes()) {
					precision = r.getMaximumPrecision();
				}
			}
		}

		return processRequirements(requirements, precision);
	}

	private int processRequirements(List<ComputeRequirement> requirements, Precision precision) {
		if (enableVerbose) {
			System.out.println("Hardware[" + getName() + "]: Processing Hardware Requirements...");
		}

		List<ComputeRequirement> done = new ArrayList<>();

		boolean kernelFriendly = defaultKernelFriendly;
		DataContext<MemoryData> sharedMemoryCtx = null;

		r: for (ComputeRequirement type : requirements) {
			if (type == ComputeRequirement.CPU) {
				type = SystemUtils.isAarch64() ? ComputeRequirement.JNI : ComputeRequirement.CL;
			} else if (type == ComputeRequirement.GPU) {
				type = SystemUtils.isAarch64() ? ComputeRequirement.MTL : ComputeRequirement.CL;
			}

			if (done.contains(type)) continue r;

			boolean locationUsed = false;
			DataContext ctx;

			if (type == ComputeRequirement.CL) {
				ctx = new CLDataContext("CL", this.maxReservation, getOffHeapSize(type), this.location);
				((CLDataContext) ctx).setDelegateMemoryProvider(nioMemory);
				locationUsed = true;
				kernelFriendly = true;
			} else if (type == ComputeRequirement.MTL) {
				ctx = new MetalDataContext("MTL", this.maxReservation, getOffHeapSize(type));
				kernelFriendly = true;
			} else {
				ctx = new NativeDataContext("JNI", precision, this.maxReservation);
			}

			if (locationUsed) {
				if (location == Location.HEAP)
					System.out.println("Hardware[" + ctx.getName() + "]: Heap RAM enabled");
				if (location == Location.HOST)
					System.out.println("Hardware[" + ctx.getName() + "]: Host RAM enabled");
				if (location == Location.DELEGATE)
					System.out.println("Hardware[" + ctx.getName() + "]: Delegate RAM enabled");
			}

			done.add(type);
			ctx.init();

			System.out.println("Hardware[" + ctx.getName() + "]: Max RAM is " +
					ctx.getPrecision().bytes() * maxReservation / 1000000 + " Megabytes (" +
					ctx.getPrecision().name() + ")");

			if (KernelPreferences.isEnableSharedMemory() && sharedMemoryCtx == null) {
				if (!(ctx instanceof NativeDataContext)) {
					sharedMemoryCtx = ctx;
				}
			}

			contexts.add(ctx);

			forEachContextListener(l -> l.contextStarted(getDataContext()));
		}

		MemoryProvider<? extends Memory> provider = null;
		if (sharedMemoryCtx != null) {
			if (sharedMemoryCtx instanceof MetalDataContext) {
				provider = ((MetalDataContext) sharedMemoryCtx).getMemoryProvider();
			} else if (sharedMemoryCtx instanceof CLDataContext) {
				provider = ((CLDataContext) sharedMemoryCtx).getMemoryProvider();
			}
		}

		if (provider == null && nioMemory != null) {
			provider = nioMemory;
		}

		if (provider != null) {
			for (DataContext<MemoryData> c : contexts) {
				if (c instanceof NativeDataContext) {
					System.out.println("Hardware[" + c.getName() + "]: Enabling shared memory via " + provider);
					((NativeDataContext) c).setDelegate(sharedMemoryCtx);
					((NativeDataContext) c).setMemoryProvider(provider);
				}
			}
		}

		if (!kernelFriendly) {
			System.out.println("Hardware[" + getName() + "]: Kernels will be avoided");
			KernelPreferences.setPreferKernels(false);
		}

		return done.size();
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

	public DefaultComputer getComputer() { return computer; }

	public void setMaximumOperationDepth(int depth) { OperationList.setMaxDepth(depth); }

	public synchronized void addContextListener(ContextListener l) {
		contextListeners.add(new WeakReference<>(l));
	}

	public synchronized void removeContextListener(ContextListener l) {
		synchronized (contextListeners) {
			contextListeners.removeIf(c -> c.get() == null || c.get() == l);
		}
	}

	public synchronized void forEachContextListener(Consumer<ContextListener> c) {
		synchronized (contextListeners) {
			contextListeners.removeIf(v -> v.get() == null);
			contextListeners.forEach(v -> {
				ContextListener l = v.get();
				if (l != null) c.accept(l);
			});
		}
	}

	public <T> T dataContext(Callable<T> exec) {
		DataContext<MemoryData> next, current = explicitDataCtx.get();

		DataContext<MemoryData> dc = getDataContext();

		if (dc instanceof CLDataContext) {
			next = new CLDataContext("CL", maxReservation, getOffHeapSize(ComputeRequirement.CL), location);
		} else if (dc instanceof MetalDataContext) {
			next = new MetalDataContext("MTL", maxReservation, getOffHeapSize(ComputeRequirement.MTL));
		} else if (dc instanceof NativeDataContext) {
			next = new NativeDataContext("JNI", getDataContext().getPrecision(), maxReservation);
		} else {
			return null;
		}

		String dcName = next.toString();
		if (dcName.contains(".")) {
			dcName = dcName.substring(dcName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + next.getName() + "]: Start " + dcName);
			next.init();
			explicitDataCtx.set(next);
			forEachContextListener(l -> l.contextStarted(getDataContext()));
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			forEachContextListener(l -> l.contextDestroyed(next));
			explicitDataCtx.set(current);
			if (Hardware.enableVerbose) System.out.println("Hardware[" + next.getName() + "]: End " + dcName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + next.getName() + "]: Destroyed " + dcName);
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

	public boolean isMemoryVolatile() { return memVolatile; }

	public int getMemoryScale() { return MEMORY_SCALE; }

	public int getOffHeapSize(ComputeRequirement type) {
		try {
			return Integer.parseInt(SystemUtils.getProperty("AR_HARDWARE_OFF_HEAP_SIZE"));
		} catch (NullPointerException | NumberFormatException e) {
			return 1024;
		}
	}

	public DataContext<MemoryData> getDataContext(ComputeRequirement... requirements) {
		if (requirements.length == 0 && explicitComputeCtx.get() == null) {
			return contexts.get(0);
		}

		return getDataContext(false, false, requirements);
	}

	public DataContext<MemoryData> getDataContext(boolean sequential, boolean accelerator, ComputeRequirement... requirements) {
		ComputeContext<MemoryData> cc = explicitComputeCtx.get();
		DataContext<MemoryData> ctx = cc == null ? explicitDataCtx.get() : cc.getDataContext();

		if (ctx != null) {
			return filterContexts(List.of(ctx), requirements).stream().findAny().orElseThrow(UnsupportedOperationException::new);
		}

		if (contexts.size() == 1) {
			boolean supported = true;
			for (ComputeRequirement r : requirements) {
				if (!supported(contexts.get(0), r)) supported = false;
			}

			if (!supported) {
				System.out.println("WARN: Ignoring ComputeRequirement as only one DataContext is available");
			}

			return contexts.get(0);
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
		return getComputeContexts(false, false).get(0);
	}

	public ComputeContext<MemoryData> getComputeContext(ComputeRequirement... requirements) {
		return getComputeContexts(false, false, requirements).get(0);
	}

	public List<ComputeContext<MemoryData>> getComputeContexts(boolean sequential, boolean accelerator, ComputeRequirement... requirements) {
		return Optional.ofNullable(getDataContext(sequential, accelerator, requirements)).map(dc -> dc.getComputeContexts())
				.orElseThrow(() -> new RuntimeException("No available data context"));
	}

	public MemoryProvider<? extends Memory> getMemoryProvider(int size) {
		long total = size;
		total *= getPrecision().bytes();

		if (total > Integer.MAX_VALUE) {
			throw new HardwareException("It is not possible to allocate " + total + " bytes of memory at once");
		}

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
