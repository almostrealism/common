/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.compute.CascadingOptimizationStrategy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.compute.ParallelismDiversityOptimization;
import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.compute.ProcessContextBase;
import io.almostrealism.compute.TraversableDepthTargetOptimization;
import io.almostrealism.expression.Expression;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.code.Precision;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.ctx.ContextListener;
import org.almostrealism.hardware.external.ExternalComputeContext;
import org.almostrealism.hardware.instructions.ComputationScopeCompiler;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.nio.NativeBufferMemoryProvider;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Central configuration and initialization point for the Almost Realism hardware acceleration system.
 *
 * <p>{@link Hardware} is responsible for:</p>
 * <ul>
 *   <li>Parsing environment variables to configure acceleration backends</li>
 *   <li>Initializing {@link DataContext}s for OpenCL, Metal, and JNI execution</li>
 *   <li>Managing memory providers and shared memory configuration</li>
 *   <li>Providing access to {@link ComputeContext}s for kernel compilation and execution</li>
 *   <li>Setting up profiling infrastructure</li>
 *   <li>Coordinating precision (FP32 vs FP64) across backends</li>
 * </ul>
 *
 * <h2>Singleton Pattern</h2>
 *
 * <p>{@link Hardware} follows the singleton pattern with a static {@link #getLocalHardware()}
 * accessor. The singleton is initialized during class loading based on environment variables:</p>
 * <pre>{@code
 * // Singleton automatically configured at startup
 * Hardware hw = Hardware.getLocalHardware();
 * DefaultComputer computer = hw.getComputer();
 * DataContext<MemoryData> ctx = hw.getDataContext();
 * }</pre>
 *
 * <h2>Environment Variable Configuration</h2>
 *
 * <p>Almost all {@link Hardware} behavior is controlled via environment variables, allowing
 * zero-code configuration for different execution environments:</p>
 *
 * <h3>AR_HARDWARE_DRIVER</h3>
 * <p><strong>Purpose:</strong> Specifies which hardware acceleration backend(s) to use.</p>
 * <p><strong>Values:</strong></p>
 * <ul>
 *   <li><strong>{@code native}</strong> - JNI backend with runtime-generated C code (default for CPU)</li>
 *   <li><strong>{@code cl}</strong> - OpenCL backend for GPU/CPU acceleration</li>
 *   <li><strong>{@code mtl}</strong> - Metal backend for Apple Silicon GPU</li>
 *   <li><strong>{@code cpu}</strong> - Abstract CPU requirement (maps to JNI on x86, JNI on ARM)</li>
 *   <li><strong>{@code gpu}</strong> - Abstract GPU requirement (maps to CL on x86, MTL on ARM)</li>
 *   <li><strong>{@code *}</strong> - Automatic selection (default):
 *     <ul>
 *       <li>ARM64 (Apple Silicon): JNI, MTL, CL</li>
 *       <li>x86/x64: CL, JNI (not on macOS)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Multiple drivers:</strong> Comma-separated list: {@code cl,native}</li>
 * </ul>
 *
 * <p><strong>Example:</strong></p>
 * <pre>
 * # Force OpenCL backend
 * export AR_HARDWARE_DRIVER=cl
 *
 * # Enable both OpenCL and JNI
 * export AR_HARDWARE_DRIVER=cl,native
 *
 * # Use abstract GPU requirement (auto-selects best GPU backend)
 * export AR_HARDWARE_DRIVER=gpu
 * </pre>
 *
 * <h3>AR_HARDWARE_LIBS</h3>
 * <p><strong>Purpose:</strong> Directory where generated native libraries are stored.</p>
 * <p><strong>Required:</strong> Yes (system will not function without this)</p>
 * <pre>
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * </pre>
 *
 * <h3>AR_HARDWARE_PRECISION</h3>
 * <p><strong>Purpose:</strong> Floating-point precision for computations.</p>
 * <p><strong>Values:</strong> {@code FP32} (float), {@code FP64} (double, default)</p>
 * <pre>
 * # Use 32-bit floats for faster GPU execution
 * export AR_HARDWARE_PRECISION=FP32
 * </pre>
 *
 * <h3>AR_HARDWARE_MEMORY_SCALE</h3>
 * <p><strong>Purpose:</strong> Controls maximum memory allocation size.</p>
 * <p><strong>Formula:</strong> Max reservation = 2^MEMORY_SCALE * 64MB</p>
 * <p><strong>Default:</strong> 4 (i.e., 2^4 * 64MB = 1GB)</p>
 * <pre>
 * # Allow 4GB max reservation (2^6 * 64MB = 4GB)
 * export AR_HARDWARE_MEMORY_SCALE=6
 * </pre>
 *
 * <h3>AR_HARDWARE_MEMORY_LOCATION</h3>
 * <p><strong>Purpose:</strong> Memory storage strategy for OpenCL.</p>
 * <p><strong>Values:</strong></p>
 * <ul>
 *   <li><strong>{@code device}</strong> - GPU/accelerator memory (default, fastest)</li>
 *   <li><strong>{@code host}</strong> - System RAM accessible by GPU</li>
 *   <li><strong>{@code heap}</strong> - Java heap (volatile, slowest)</li>
 *   <li><strong>{@code delegate}</strong> - Native buffer delegation</li>
 * </ul>
 *
 * <h3>AR_HARDWARE_NIO_MEMORY</h3>
 * <p><strong>Purpose:</strong> Enable NIO-based shared memory between backends.</p>
 * <p><strong>Values:</strong> {@code true}, {@code false}</p>
 * <pre>
 * export AR_HARDWARE_NIO_MEMORY=true
 * </pre>
 *
 * <h3>AR_HARDWARE_MAX_DEPTH</h3>
 * <p><strong>Purpose:</strong> Maximum {@link OperationList} nesting depth.</p>
 * <p><strong>Default:</strong> 500</p>
 * <pre>
 * export AR_HARDWARE_MAX_DEPTH=1000
 * </pre>
 *
 * <h3>AR_HARDWARE_OFF_HEAP_SIZE</h3>
 * <p><strong>Purpose:</strong> Off-heap buffer size in bytes.</p>
 * <p><strong>Default:</strong> 1024</p>
 *
 * <h3>AR_HARDWARE_EPSILON_64</h3>
 * <p><strong>Purpose:</strong> Use full FP64 epsilon precision.</p>
 * <p><strong>Default:</strong> {@code false} (uses FP32 epsilon even for FP64)</p>
 *
 * <h3>AR_HARDWARE_ASYNC</h3>
 * <p><strong>Purpose:</strong> Enable asynchronous execution.</p>
 * <p><strong>Default:</strong> {@code true}</p>
 *
 * <h2>Common Configuration Patterns</h2>
 *
 * <h3>Development (Fast Compilation, CPU Execution)</h3>
 * <pre>
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=native
 * export AR_HARDWARE_PRECISION=FP64
 * </pre>
 *
 * <h3>Production GPU (Maximum Performance)</h3>
 * <pre>
 * export AR_HARDWARE_LIBS=/var/ar_libs/
 * export AR_HARDWARE_DRIVER=gpu
 * export AR_HARDWARE_PRECISION=FP32
 * export AR_HARDWARE_MEMORY_SCALE=6
 * export AR_HARDWARE_MEMORY_LOCATION=device
 * </pre>
 *
 * <h3>Apple Silicon (Unified Memory)</h3>
 * <pre>
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=mtl
 * export AR_HARDWARE_NIO_MEMORY=true
 * export AR_HARDWARE_PRECISION=FP32
 * </pre>
 *
 * <h3>Multi-Backend (OpenCL + JNI Fallback)</h3>
 * <pre>
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=cl,native
 * export AR_HARDWARE_PRECISION=FP32
 * </pre>
 *
 * <h2>DataContext vs ComputeContext</h2>
 *
 * <h3>DataContext</h3>
 * <p>Manages memory allocation and data transfer for a specific backend:</p>
 * <ul>
 *   <li>{@link org.almostrealism.hardware.cl.CLDataContext} - OpenCL</li>
 *   <li>{@link MetalDataContext} - Metal</li>
 *   <li>{@link NativeDataContext} - JNI/C</li>
 * </ul>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>{@code
 * DataContext<MemoryData> ctx = Hardware.getLocalHardware().getDataContext();
 * MemoryProvider<?> provider = ctx.getMemoryProvider(1024);
 * }</pre>
 *
 * <h3>ComputeContext</h3>
 * <p>Handles kernel compilation and execution for a specific backend.</p>
 * <p><strong>Usage:</strong></p>
 * <pre>{@code
 * ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputeContext();
 * Runnable compiled = ctx.compileRunnable(...);
 * }</pre>
 *
 * <h2>Context Selection Based on Requirements</h2>
 *
 * <p>When multiple backends are configured, {@link Hardware} selects the appropriate context
 * based on {@link ComputeRequirement}s:</p>
 * <pre>{@code
 * // Get GPU context (prefers Metal on ARM, OpenCL on x86)
 * DataContext<MemoryData> gpuCtx = hw.getDataContext(ComputeRequirement.GPU);
 *
 * // Get CPU context (prefers JNI)
 * DataContext<MemoryData> cpuCtx = hw.getDataContext(ComputeRequirement.CPU);
 *
 * // Get OpenCL specifically
 * DataContext<MemoryData> clCtx = hw.getDataContext(ComputeRequirement.CL);
 *
 * // Accelerator preference (GPU backends)
 * DataContext<MemoryData> accel = hw.getDataContext(true, true);
 * }</pre>
 *
 * <h2>Shared Memory Configuration</h2>
 *
 * <p>When multiple backends are active, {@link Hardware} can configure shared memory
 * to avoid redundant data copies between backends:</p>
 *
 * <h3>Automatic Shared Memory (Metal + JNI on Apple Silicon)</h3>
 * <pre>
 * export AR_HARDWARE_DRIVER=*
 * export AR_HARDWARE_NIO_MEMORY=true
 * </pre>
 *
 * <p><strong>Result:</strong> JNI backend delegates memory allocation to Metal's unified memory,
 * allowing zero-copy sharing between Metal GPU kernels and JNI CPU operations.</p>
 *
 * <h3>Manual Shared Memory</h3>
 * <pre>{@code
 * // Metal provides shared memory for JNI
 * KernelPreferences.enableSharedMemory();
 * Hardware hw = Hardware.getLocalHardware();
 * // JNI will automatically use Metal's memory provider
 * }</pre>
 *
 * <h2>Profiling Support</h2>
 *
 * <p>Attach an {@link OperationProfile} to collect timing data across all operations:</p>
 * <pre>{@code
 * OperationProfile profile = new DefaultProfile();
 * Hardware.getLocalHardware().assignProfile(profile);
 *
 * // Run operations...
 * computation.get().run();
 *
 * // Profile now contains timing data
 * System.out.println("Total time: " + profile.getTotalTime());
 *
 * // Clear profiling
 * Hardware.getLocalHardware().clearProfile();
 * }</pre>
 *
 * <h2>Context Listeners</h2>
 *
 * <p>Register listeners to be notified of context lifecycle events:</p>
 * <pre>{@code
 * ContextListener listener = new ContextListener() {
 *     public void contextStarted(DataContext<?> ctx) {
 *         System.out.println("Context started: " + ctx.getName());
 *     }
 *
 *     public void contextDestroyed(DataContext<?> ctx) {
 *         System.out.println("Context destroyed: " + ctx.getName());
 *     }
 * };
 *
 * Hardware.getLocalHardware().addContextListener(listener);
 * }</pre>
 *
 * <p><strong>Note:</strong> Listeners are stored via {@link WeakReference} to prevent memory
 * leaks. Strong references must be maintained elsewhere.</p>
 *
 * <h2>Temporary Context Switching</h2>
 *
 * <p>Create isolated contexts for specific operations:</p>
 *
 * <h3>Data Context Isolation</h3>
 * <pre>{@code
 * // Create new isolated data context
 * Hardware hw = Hardware.getLocalHardware();
 * PackedCollection<?> result = hw.dataContext(() -> {
 *     // This code runs in a fresh DataContext
 *     // Allocated memory is automatically cleaned up
 *     PackedCollection<?> temp = new PackedCollection<>(1000);
 *     temp.fill(Math::random);
 *     return temp.copy();  // Return must be copied out
 * });
 * // Temporary context destroyed here
 * }</pre>
 *
 * <h3>Compute Context Isolation</h3>
 * <pre>{@code
 * // Force GPU execution for specific code
 * Hardware hw = Hardware.getLocalHardware();
 * hw.computeContext(() -> {
 *     // All operations in this block prefer GPU
 *     computation.get().run();
 *     return null;
 * }, ComputeRequirement.GPU);
 * }</pre>
 *
 * <h2>Precision Management</h2>
 *
 * <p>{@link Hardware} coordinates precision across backends:</p>
 * <pre>{@code
 * Precision p = Hardware.getLocalHardware().getPrecision();
 * System.out.println("Using " + p.name() + " precision");
 *
 * // Get epsilon for comparisons
 * double eps = Hardware.getLocalHardware().epsilon();
 * if (Math.abs(a - b) < eps) {
 *     // Values are approximately equal
 * }
 * }</pre>
 *
 * <p><strong>Precision Selection Rules:</strong></p>
 * <ol>
 *   <li>Use {@code AR_HARDWARE_PRECISION} if set</li>
 *   <li>If {@link KernelPreferences#isRequireUniformPrecision()}, use lowest backend precision</li>
 *   <li>Otherwise, allow mixed precision across backends</li>
 * </ol>
 *
 * <h2>Memory Management</h2>
 *
 * <p>Obtain memory providers for allocation:</p>
 * <pre>{@code
 * Hardware hw = Hardware.getLocalHardware();
 *
 * // Get provider for 1000 doubles
 * MemoryProvider<?> provider = hw.getMemoryProvider(1000);
 * Memory mem = provider.allocate(1000);
 *
 * // Get native buffer provider (if NIO memory enabled)
 * MemoryProvider<? extends RAM> nioProvider = hw.getNativeBufferMemoryProvider();
 * }</pre>
 *
 * <h2>Configuration Inspection</h2>
 *
 * <p>Query current configuration:</p>
 * <pre>{@code
 * Hardware hw = Hardware.getLocalHardware();
 *
 * // List all active contexts
 * for (DataContext<MemoryData> ctx : hw.getAllDataContexts()) {
 *     System.out.println("Context: " + ctx.getName());
 *     System.out.println("  Precision: " + ctx.getPrecision());
 *     System.out.println("  CPU: " + ctx.getComputeContext().isCPU());
 * }
 *
 * // Check configuration flags
 * boolean async = hw.isAsync();
 * boolean memVolatile = hw.isMemoryVolatile();
 * int memScale = hw.getMemoryScale();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link Hardware} itself is thread-safe, but context switching via {@link #dataContext(Callable)}
 * and {@link #computeContext(Callable, ComputeRequirement...)} uses thread-local storage. Each
 * thread maintains its own context stack.</p>
 *
 * <h2>Initialization Logging</h2>
 *
 * <p>Enable verbose logging to see initialization details:</p>
 * <pre>{@code
 * Hardware.enableVerbose = true;
 * // Output:
 * // Hardware[local]: Processing Hardware Requirements...
 * // Hardware[CL]: Max RAM is 1024 Megabytes (FP32)
 * // Hardware[JNI]: Enabling shared memory via CLMemoryProvider
 * }</pre>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <h3>Forgetting AR_HARDWARE_LIBS</h3>
 * <pre>
 * # BAD: Missing required environment variable
 * java -jar myapp.jar
 * # Error: NoClassDefFoundError
 *
 * # GOOD: Set AR_HARDWARE_LIBS before running
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=native
 * java -jar myapp.jar
 * </pre>
 *
 * <h3>Incompatible Memory Location with NIO</h3>
 * <pre>
 * # BAD: HOST location incompatible with NIO memory
 * export AR_HARDWARE_NIO_MEMORY=true
 * export AR_HARDWARE_MEMORY_LOCATION=host
 * # Warning: location will be set to DELEGATE instead
 *
 * # GOOD: Use DELEGATE with NIO memory
 * export AR_HARDWARE_NIO_MEMORY=true
 * export AR_HARDWARE_MEMORY_LOCATION=delegate
 * </pre>
 *
 * <h3>Precision Mismatch with Multiple Backends</h3>
 * <pre>
 * # If using multiple backends, ensure precision compatibility
 * export AR_HARDWARE_DRIVER=cl,native
 * export AR_HARDWARE_PRECISION=FP32  # Both backends use FP32
 * </pre>
 *
 * <h2>Advanced: Cascading Optimization Strategy</h2>
 *
 * <p>{@link Hardware} automatically configures a cascading optimization strategy for
 * {@link OperationList} compilation:</p>
 * <ol>
 *   <li><strong>ParallelismDiversityOptimization:</strong> Segment by parallelism count</li>
 *   <li><strong>TraversableDepthTargetOptimization:</strong> Optimize traversal depth</li>
 *   <li><strong>ParallelismTargetOptimization:</strong> Target optimal parallelism</li>
 * </ol>
 *
 * <p>These optimizations automatically reshape operation graphs for better hardware utilization.</p>
 *
 * @see DataContext
 * @see ComputeContext
 * @see DefaultComputer
 * @see ComputeRequirement
 * @see OperationProfile
 * @see Precision
 *
 * @author  Michael Murray
 */
public final class Hardware {
	public static boolean enableVerbose = false;
	public static boolean defaultKernelFriendly = true;

	public static Console console = Console.root().child()
			.addFilter(ConsoleFeatures.duplicateFilter(10 * 60 * 1000L));

	protected static final int MEMORY_SCALE;

	private static final boolean epsilon64 = SystemUtils.isEnabled("AR_HARDWARE_EPSILON_64").orElse(false);
	private static final boolean async = SystemUtils.isEnabled("AR_HARDWARE_ASYNC").orElse(true);

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
		} else if ("delegate".equalsIgnoreCase(memLocation)) {
			location = Location.DELEGATE;
		}

		String opDepth = SystemUtils.getProperty("AR_HARDWARE_MAX_DEPTH");
		if (opDepth != null) OperationList.setMaxDepth(Integer.parseInt(opDepth));

		String drivers[] = SystemUtils.getProperty("AR_HARDWARE_DRIVER", "*").split(",");

		List<ComputeRequirement> requirements = new ArrayList<>();

		boolean nioMem = false;

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
					nioMem = true;
				}
			} else {
				throw new IllegalStateException("Unknown driver " + driver);
			}
		}

		nioMem = SystemUtils.isEnabled("AR_HARDWARE_NIO_MEMORY").orElse(nioMem);

		if (nioMem) {
			if (memLocation != null) {
				if (location == Location.HOST) {
					console.warn("NIO memory is enabled, location will be set to DELEGATE instead of HOST");
				} else if (location != Location.DELEGATE) {
					throw new IllegalArgumentException("Cannot use location " + memLocation + " with NIO memory");
				}
			}

			location = Location.DELEGATE;
		}

		ProcessContextBase.setDefaultOptimizationStrategy(new CascadingOptimizationStrategy(
				new ParallelismDiversityOptimization(),
				new TraversableDepthTargetOptimization(),
				new ParallelismTargetOptimization()
		));

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
					System.out.println("Hardware[" + c.getName() +
							"]: Enabling shared memory via " +
							provider.getClass().getSimpleName());
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

	/**
	 * Returns the name of this hardware instance.
	 *
	 * <p>Typically "local" for the singleton instance. Used for logging and identification.</p>
	 *
	 * @return The hardware instance name
	 */
	public String getName() { return name; }

	/**
	 * Returns the effective precision for computations across all contexts.
	 *
	 * <p>When multiple backends are configured, returns the lowest precision (largest epsilon)
	 * among all contexts to ensure numerical compatibility. For example, if CL is FP32 and
	 * JNI is FP64, this returns FP32.</p>
	 *
	 * @return The effective precision for all operations
	 */
	public Precision getPrecision() {
		Precision precision = Precision.FP64;

		for (DataContext c : contexts) {
			if (c.getPrecision().epsilon(true) > precision.epsilon(true)) {
				precision = c.getPrecision();
			}
		}

		return precision;
	}

	/**
	 * Returns the singleton {@link Hardware} instance for this process.
	 *
	 * <p>The instance is initialized during class loading based on environment variables.
	 * All Almost Realism code should use this singleton for hardware access.</p>
	 *
	 * @return The local hardware singleton
	 */
	public static Hardware getLocalHardware() { return local; }

	/**
	 * Returns the {@link DefaultComputer} managing compilation and execution.
	 *
	 * <p>The computer coordinates all hardware contexts and provides high-level
	 * operation execution services.</p>
	 *
	 * @return The default computer instance
	 */
	public DefaultComputer getComputer() { return computer; }

	/**
	 * Sets the maximum nesting depth for {@link OperationList} compilation.
	 *
	 * <p>Deeper nesting allows more operations to be batched together but increases
	 * compilation time. Default is 500.</p>
	 *
	 * @param depth The maximum depth (typically 100-1000)
	 */
	public void setMaximumOperationDepth(int depth) { OperationList.setMaxDepth(depth); }

	/**
	 * Returns the epsilon value for floating-point comparisons.
	 *
	 * <p>By default, uses FP32 epsilon even for FP64 precision unless
	 * {@code AR_HARDWARE_EPSILON_64=true}. This provides more lenient
	 * equality checks suitable for hardware computations.</p>
	 *
	 * <p>Usage:</p>
	 * <pre>{@code
	 * double eps = Hardware.getLocalHardware().epsilon();
	 * if (Math.abs(a - b) < eps) {
	 *     // Values are approximately equal
	 * }
	 * }</pre>
	 *
	 * @return The epsilon threshold for approximate equality
	 */
	public double epsilon() {
		double eps = getPrecision().epsilon();

		if (!epsilon64 && eps < Precision.FP32.epsilon()) {
			eps = Precision.FP32.epsilon();
		}

		return eps;
	}

	/**
	 * Attaches an {@link OperationProfile} to collect timing data globally.
	 *
	 * <p>Once assigned, all hardware operations, compilations, and scope evaluations
	 * will report their timing to the profile. Pass null to disable profiling.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * OperationProfile profile = new DefaultProfile();
	 * Hardware.getLocalHardware().assignProfile(profile);
	 *
	 * // Run operations - timing is automatically collected
	 * computation.get().run();
	 *
	 * // Analyze results
	 * System.out.println("Total time: " + profile.getTotalTime());
	 * }</pre>
	 *
	 * @param profile The profile to collect timing data, or null to disable
	 */
	public void assignProfile(OperationProfile profile) {
		if (profile == null) {
			clearProfile();
		} else {
			HardwareOperator.timingListener = profile.getRuntimeListener();
			AbstractComputeContext.compilationTimingListener = profile.getCompilationListener();
			ComputationScopeCompiler.timing = profile.getScopeListener(true);
			Scope.timing = profile.getScopeListener(true);
			ScopeSettings.timing = profile.getScopeListener(false);
			Expression.timing = profile.getScopeListener(false);
		}
	}

	/**
	 * Removes any attached profiling, disabling timing collection.
	 *
	 * <p>Equivalent to calling {@link #assignProfile(OperationProfile)} with null.</p>
	 */
	public void clearProfile() {
		HardwareOperator.timingListener = null;
		AbstractComputeContext.compilationTimingListener = null;
		ComputationScopeCompiler.timing = null;
		Scope.timing = null;
		ScopeSettings.timing = null;
		Expression.timing = null;
	}

	/**
	 * Registers a {@link ContextListener} to receive context lifecycle events.
	 *
	 * <p>Listeners are stored via {@link WeakReference}, so callers must maintain
	 * a strong reference elsewhere or the listener will be garbage collected.</p>
	 *
	 * <p>Events include context creation ({@link ContextListener#contextStarted})
	 * and destruction ({@link ContextListener#contextDestroyed}).</p>
	 *
	 * @param l The listener to register
	 */
	public synchronized void addContextListener(ContextListener l) {
		contextListeners.add(new WeakReference<>(l));
	}

	/**
	 * Unregisters a {@link ContextListener}.
	 *
	 * <p>Also cleans up any garbage-collected weak references.</p>
	 *
	 * @param l The listener to remove
	 */
	public synchronized void removeContextListener(ContextListener l) {
		synchronized (contextListeners) {
			contextListeners.removeIf(c -> c.get() == null || c.get() == l);
		}
	}

	/**
	 * Applies an action to each registered {@link ContextListener}.
	 *
	 * <p>Automatically removes any garbage-collected weak references during iteration.</p>
	 *
	 * @param c The action to apply to each listener
	 */
	public synchronized void forEachContextListener(Consumer<ContextListener> c) {
		synchronized (contextListeners) {
			contextListeners.removeIf(v -> v.get() == null);
			contextListeners.forEach(v -> {
				ContextListener l = v.get();
				if (l != null) c.accept(l);
			});
		}
	}

	/**
	 * Executes a {@link Callable} in an isolated {@link DataContext}.
	 *
	 * <p>Creates a temporary context matching the current backend type, executes the
	 * callable, and destroys the context. All memory allocated within the callable is
	 * automatically cleaned up.</p>
	 *
	 * <p><strong>Important:</strong> Data must be copied out before the callable returns,
	 * as the temporary context is destroyed afterward.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * PackedCollection<?> result = Hardware.getLocalHardware().dataContext(() -> {
	 *     PackedCollection<?> temp = new PackedCollection<>(1000);
	 *     temp.fill(Math::random);
	 *     return temp.copy();  // Must copy before returning
	 * });
	 * }</pre>
	 *
	 * @param exec The callable to execute in the isolated context
	 * @param <T> The return type
	 * @return The result of the callable
	 * @throws RuntimeException if the callable throws an exception
	 */
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

	/**
	 * Executes a {@link Callable} with a specific {@link ComputeContext} preference.
	 *
	 * <p>Forces all operations within the callable to use a context matching the
	 * specified requirements. Useful for forcing GPU or CPU execution for specific code blocks.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * // Force GPU execution
	 * Hardware.getLocalHardware().computeContext(() -> {
	 *     computation.get().run();  // Runs on GPU
	 *     return null;
	 * }, ComputeRequirement.GPU);
	 *
	 * // Force CPU execution
	 * Hardware.getLocalHardware().computeContext(() -> {
	 *     computation.get().run();  // Runs on CPU
	 *     return null;
	 * }, ComputeRequirement.CPU);
	 * }</pre>
	 *
	 * @param exec The callable to execute
	 * @param expectations Requirements for context selection
	 * @param <T> The return type
	 * @return The result of the callable
	 * @throws RuntimeException if no context meets the requirements
	 */
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

	/**
	 * Returns whether asynchronous execution is enabled.
	 *
	 * <p>Controlled by {@code AR_HARDWARE_ASYNC} environment variable (default: true).</p>
	 *
	 * @return true if async execution is enabled
	 */
	public boolean isAsync() { return async; }

	/**
	 * Returns whether memory is volatile (stored in JVM heap).
	 *
	 * <p>True when {@code AR_HARDWARE_MEMORY_LOCATION=heap}.</p>
	 *
	 * @return true if memory is volatile
	 */
	public boolean isMemoryVolatile() { return memVolatile; }

	/**
	 * Returns the memory scale exponent for maximum allocation size.
	 *
	 * <p>Max reservation = 2^MEMORY_SCALE * 64MB. Default is 4 (1GB).</p>
	 *
	 * @return The memory scale exponent
	 */
	public int getMemoryScale() { return MEMORY_SCALE; }

	/**
	 * Returns the off-heap buffer size for the specified backend.
	 *
	 * <p>Controlled by {@code AR_HARDWARE_OFF_HEAP_SIZE} environment variable (default: 1024 bytes).</p>
	 *
	 * @param type The backend type (currently unused)
	 * @return The off-heap buffer size in bytes
	 */
	public int getOffHeapSize(ComputeRequirement type) {
		try {
			return Integer.parseInt(SystemUtils.getProperty("AR_HARDWARE_OFF_HEAP_SIZE"));
		} catch (NullPointerException | NumberFormatException e) {
			return 1024;
		}
	}

	/**
	 * Returns all configured {@link DataContext}s.
	 *
	 * <p>Includes all backends initialized based on {@code AR_HARDWARE_DRIVER}.
	 * The list is unmodifiable.</p>
	 *
	 * @return Unmodifiable list of all data contexts
	 */
	public List<DataContext<MemoryData>> getAllDataContexts() {
		return Collections.unmodifiableList(contexts);
	}

	/**
	 * Returns a {@link DataContext} matching the specified requirements.
	 *
	 * <p>If no requirements are specified and no explicit context is set,
	 * returns the first configured context. Otherwise selects a context
	 * matching all requirements.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * // Get default context
	 * DataContext<MemoryData> ctx = hardware.getDataContext();
	 *
	 * // Get GPU context
	 * DataContext<MemoryData> gpu = hardware.getDataContext(ComputeRequirement.GPU);
	 *
	 * // Get OpenCL specifically
	 * DataContext<MemoryData> cl = hardware.getDataContext(ComputeRequirement.CL);
	 * }</pre>
	 *
	 * @param requirements Optional requirements for context selection
	 * @return A matching data context
	 * @throws UnsupportedOperationException if no context matches requirements
	 */
	public DataContext<MemoryData> getDataContext(ComputeRequirement... requirements) {
		if (requirements.length == 0 && explicitComputeCtx.get() == null) {
			return contexts.get(0);
		}

		return getDataContext(false, false, requirements);
	}

	/**
	 * Returns a {@link DataContext} with detailed selection criteria.
	 *
	 * <p>Allows fine-grained control over context selection with preference hints:</p>
	 * <ul>
	 *   <li><strong>sequential:</strong> Prefer CPU/JNI backend for sequential execution</li>
	 *   <li><strong>accelerator:</strong> Prefer GPU backends (Metal, then OpenCL)</li>
	 *   <li><strong>requirements:</strong> Must satisfy all specified requirements</li>
	 * </ul>
	 *
	 * <p>Selection priority:</p>
	 * <ol>
	 *   <li>If explicit context set via {@link #computeContext} or {@link #dataContext}, use it</li>
	 *   <li>If only one context available, use it (with warning if requirements don't match)</li>
	 *   <li>Filter contexts by requirements</li>
	 *   <li>If accelerator=true, prefer Metal > OpenCL</li>
	 *   <li>If sequential=true, prefer JNI</li>
	 *   <li>Return first matching context</li>
	 * </ol>
	 *
	 * @param sequential Prefer sequential/CPU execution
	 * @param accelerator Prefer GPU execution
	 * @param requirements Required capabilities
	 * @return A matching data context, or null if none match
	 */
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

	/**
	 * Returns the default {@link ComputeContext}.
	 *
	 * <p>Equivalent to calling {@link #getComputeContext(ComputeRequirement...)} with no requirements.</p>
	 *
	 * @return The default compute context
	 */
	public ComputeContext<MemoryData> getComputeContext() {
		return getComputeContexts(false, false).get(0);
	}

	/**
	 * Returns a {@link ComputeContext} matching the specified requirements.
	 *
	 * @param requirements Required capabilities
	 * @return A matching compute context
	 * @throws RuntimeException if no context meets requirements
	 */
	public ComputeContext<MemoryData> getComputeContext(ComputeRequirement... requirements) {
		return getComputeContexts(false, false, requirements).get(0);
	}

	/**
	 * Returns all {@link ComputeContext}s for a {@link DataContext} matching the criteria.
	 *
	 * <p>A {@link DataContext} may provide multiple {@link ComputeContext}s (e.g., different
	 * optimization levels or profiling variants).</p>
	 *
	 * @param sequential Prefer sequential/CPU execution
	 * @param accelerator Prefer GPU execution
	 * @param requirements Required capabilities
	 * @return List of compute contexts from the selected data context
	 * @throws RuntimeException if no data context is available
	 */
	public List<ComputeContext<MemoryData>> getComputeContexts(boolean sequential, boolean accelerator, ComputeRequirement... requirements) {
		return Optional.ofNullable(getDataContext(sequential, accelerator, requirements)).map(dc -> dc.getComputeContexts())
				.orElseThrow(() -> new RuntimeException("No available data context"));
	}

	/**
	 * Returns a {@link MemoryProvider} suitable for allocating the specified number of elements.
	 *
	 * <p>The provider is obtained from the default {@link DataContext} and can allocate
	 * memory for the current precision (FP32 or FP64).</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * MemoryProvider<?> provider = Hardware.getLocalHardware().getMemoryProvider(1000);
	 * Memory mem = provider.allocate(1000);  // 1000 doubles
	 * }</pre>
	 *
	 * @param size Number of double-precision elements to allocate
	 * @return A memory provider from the default context
	 * @throws HardwareException if size * precision bytes exceeds Integer.MAX_VALUE
	 * @throws RuntimeException if no data context is available
	 */
	public MemoryProvider<? extends Memory> getMemoryProvider(int size) {
		long total = size;
		total *= getPrecision().bytes();

		if (total > Integer.MAX_VALUE) {
			throw new HardwareException("It is not possible to allocate " + total + " bytes of memory at once");
		}

		return Optional.ofNullable(getDataContext()).map(dc -> dc.getMemoryProvider(size))
				.orElseThrow(() -> new RuntimeException("No available data context"));
	}

	/**
	 * Returns the native buffer memory provider if NIO memory is enabled.
	 *
	 * <p>Only available when {@code AR_HARDWARE_NIO_MEMORY=true}. Provides direct
	 * native buffer allocation for shared memory between backends.</p>
	 *
	 * @return The NIO memory provider, or null if not enabled
	 */
	public MemoryProvider<? extends RAM> getNativeBufferMemoryProvider() {
		return nioMemory;
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
}
