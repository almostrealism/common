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

package org.almostrealism.hardware.instructions;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Scope;
import io.almostrealism.uml.Named;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.hardware.kernel.KernelTraversalOperation;
import org.almostrealism.hardware.kernel.KernelTraversalOperationGenerator;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Compiles {@link Computation} instances into {@link Scope} objects for hardware execution.
 *
 * <p>{@link ComputationScopeCompiler} is the central component that transforms high-level
 * {@link Computation} objects into low-level {@link Scope} representations suitable for
 * compilation to native code. It handles:</p>
 *
 * <ol>
 *   <li><strong>Scope generation:</strong> Call {@code Computation.getScope()} to obtain AST</li>
 *   <li><strong>Argument preparation:</strong> Set up {@link io.almostrealism.code.ArgumentMap} for inputs</li>
 *   <li><strong>Scope simplification:</strong> Optimize and flatten the scope tree</li>
 *   <li><strong>Metadata enrichment:</strong> Add shape, signature, and traversal policy</li>
 *   <li><strong>Kernel structure support:</strong> Manage kernel series and traversal caching</li>
 * </ol>
 *
 * <h2>Compilation Lifecycle</h2>
 *
 * <pre>{@code
 * // Create compiler
 * Computation<Matrix> computation = add(a, b);
 * NameProvider nameProvider = () -> "add_operation";
 * ComputationScopeCompiler<Matrix> compiler =
 *     new ComputationScopeCompiler<>(computation, nameProvider);
 *
 * // Prepare arguments (optional, for Process trees)
 * ArgumentMap argMap = new ArgumentMap();
 * compiler.prepareArguments(argMap);
 *
 * // Prepare scope inputs
 * ScopeInputManager inputManager = ...;
 * compiler.prepareScope(inputManager);
 *
 * // Compile to Scope
 * Scope<Matrix> scope = compiler.compile();
 *
 * // Enrich metadata
 * compiler.postCompile();
 *
 * // Check status
 * if (compiler.isCompiled()) {
 *     System.out.println("Scope signature: " + compiler.signature());
 * }
 *
 * // Cleanup
 * compiler.destroy();
 * }</pre>
 *
 * <h2>Kernel Structure Support</h2>
 *
 * <p>{@link ComputationScopeCompiler} implements {@link KernelStructureContext} to provide
 * kernel series and traversal data for GPU optimization:</p>
 *
 * <ul>
 *   <li><strong>{@link org.almostrealism.hardware.kernel.KernelSeriesCache}:</strong> Caches precomputed series data</li>
 *   <li><strong>{@link org.almostrealism.hardware.kernel.KernelTraversalOperationGenerator}:</strong> Generates traversal patterns</li>
 * </ul>
 *
 * <pre>{@code
 * // Kernel structure is enabled by default
 * compiler.isKernelStructureSupported();  // true
 *
 * // Access kernel providers
 * KernelSeriesProvider seriesProvider = compiler.getSeriesProvider();
 * KernelTraversalProvider traversalProvider = compiler.getTraversalProvider();
 * }</pre>
 *
 * <p><strong>Note:</strong> Kernel structure is disabled for {@link KernelTraversalOperation} to prevent
 * recursive traversal generation.</p>
 *
 * <h2>Signature Generation</h2>
 *
 * <p>Implements {@link Signature} to generate unique operation signatures for caching:</p>
 *
 * <pre>{@code
 * String signature = compiler.signature();
 * // Example: "Add_f64_3_2&distinct=2;"
 * //   - Operation: Add
 * //   - Precision: FP64
 * //   - Shape: 3x2
 * //   - Distinct arguments: 2
 * }</pre>
 *
 * <h2>Timing and Profiling</h2>
 *
 * <p>Supports optional timing via {@code ComputationScopeCompiler.timing}:</p>
 *
 * <pre>{@code
 * // Enable timing
 * ComputationScopeCompiler.timing = new MyScopeTimingListener();
 *
 * // Compile (timing is recorded)
 * Scope<Matrix> scope = compiler.compile();
 *
 * // Timing records:
 * // - "getScope": Time to call Computation.getScope()
 * // - "convertRequired": Time to convert arguments to required scopes
 * }</pre>
 *
 * <h2>Verbose Compilation</h2>
 *
 * <p>Set {@code AR_HARDWARE_VERBOSE_COMPILE=true} to log compilation events:</p>
 *
 * <pre>
 * export AR_HARDWARE_VERBOSE_COMPILE=true
 *
 * // Logs:
 * // Compiling Add_f64_3_2
 * // Done compiling Add_f64_3_2
 * </pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>Compilation errors are wrapped in {@link org.almostrealism.hardware.HardwareException}:</p>
 *
 * <pre>{@code
 * try {
 *     Scope<Matrix> scope = compiler.compile();
 * } catch (HardwareException e) {
 *     // Error message includes operation name
 *     System.err.println(e.getMessage());  // "Cannot compile Add_f64_3_2"
 * }
 * }</pre>
 *
 * <h2>Shape Validation</h2>
 *
 * <p>{@code postCompile()} validates that {@link Scope} metadata matches {@link Computation} shape:</p>
 *
 * <pre>{@code
 * compiler.postCompile();
 * // Throws IllegalArgumentException if shape mismatch
 * }</pre>
 *
 * @param <T> The type of value produced by the compiled scope
 * @see Computation
 * @see Scope
 * @see KernelStructureContext
 * @see ScopeInstructionsManager
 */
public class ComputationScopeCompiler<T> implements KernelStructureContext,
		ScopeLifecycle, Destroyable, OperationInfo, Signature, ConsoleFeatures {
	/** Enables verbose logging during compilation when set to {@code true}. */
	public static boolean verboseCompile = SystemUtils.isEnabled("AR_HARDWARE_VERBOSE_COMPILE").orElse(false);

	/** Optional listener for recording scope compilation timing metrics. */
	public static ScopeTimingListener timing;

	/** The computation being compiled into a scope. */
	private Computation<T> computation;

	/** Provider for generating unique names during scope compilation. */
	private NameProvider nameProvider;

	/** Cache for kernel series data used in GPU optimization. */
	private KernelSeriesCache kernelSeriesCache;

	/** Generator for kernel traversal operations. */
	private KernelTraversalOperationGenerator traversalGenerator;

	/** Cached maximum kernel count for this computation. */
	private OptionalLong kernelMaximum;

	/** The compiled scope, or {@code null} if not yet compiled. */
	private Scope<T> scope;

	/**
	 * Constructs a new compiler for the specified computation.
	 *
	 * @param computation  the computation to compile into a scope
	 * @param nameProvider provider for generating unique argument names
	 */
	public ComputationScopeCompiler(Computation<T> computation, NameProvider nameProvider) {
		this.computation = computation;
		this.nameProvider = nameProvider;
	}

	/**
	 * Checks if kernel structure optimization is supported for this computation.
	 * Returns {@code false} for {@link KernelTraversalOperation} to prevent recursive
	 * traversal generation, {@code true} for all other computations.
	 *
	 * @return {@code true} if kernel structure optimization is supported
	 */
	public boolean isKernelStructureSupported() {
		if (computation instanceof KernelTraversalOperation) {
			// Kernel traversal caching should not be recursively
			// applied to the operation that generates traversal
			// series data for another operation
			return false;
		}

		return true;
	}

	/**
	 * Returns the computation being compiled.
	 *
	 * @return the computation
	 */
	public Computation<T> getComputation() { return computation; }

	/**
	 * Returns the operation metadata from the underlying computation.
	 *
	 * @return the {@link OperationMetadata} if the computation implements {@link OperationInfo}, otherwise {@code null}
	 */
	@Override
	public OperationMetadata getMetadata() {
		return computation instanceof OperationInfo ? ((OperationInfo) computation).getMetadata() : null;
	}

	/**
	 * Returns the compute requirements for this compilation.
	 * Delegates to the compiled scope if available, otherwise to the computation.
	 *
	 * @return the list of compute requirements
	 */
	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		if (scope != null) return scope.getComputeRequirements();
		if (getComputation() instanceof OperationInfo) {
			return ((OperationInfo) getComputation()).getComputeRequirements();
		}

		return OperationInfo.super.getComputeRequirements();
	}

	/**
	 * Returns the maximum kernel count for this computation.
	 * The value is cached after the first call. Returns an empty optional
	 * if the computation does not have a fixed count.
	 *
	 * @return the maximum kernel count, or empty if not fixed
	 */
	@Override
	public OptionalLong getKernelMaximum() {
		if (kernelMaximum == null) {
			kernelMaximum = Countable.isFixedCount(getComputation()) ?
					OptionalLong.of(Countable.countLong(getComputation())) : OptionalLong.empty();
		}

		return kernelMaximum;
	}

	/**
	 * Returns the kernel series provider for GPU optimization.
	 *
	 * @return the {@link KernelSeriesProvider} if kernel structure is supported, otherwise {@code null}
	 */
	@Override
	public KernelSeriesProvider getSeriesProvider() {
		return isKernelStructureSupported() ? kernelSeriesCache : null;
	}

	/**
	 * Returns the kernel traversal provider for GPU optimization.
	 *
	 * @return the {@link KernelTraversalProvider} if kernel structure is supported, otherwise {@code null}
	 */
	@Override
	public KernelTraversalProvider getTraversalProvider() {
		return isKernelStructureSupported() ? traversalGenerator : null;
	}

	/**
	 * Prepares arguments for compilation by delegating to the underlying computation.
	 *
	 * @param map the argument map to populate
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.super.prepareArguments(map);
		getComputation().prepareArguments(map);
	}

	/**
	 * Prepares the scope inputs using this compiler as the kernel structure context.
	 *
	 * @param manager the scope input manager
	 */
	public void prepareScope(ScopeInputManager manager) {
		prepareScope(manager, this);
	}

	/**
	 * Prepares scope inputs and initializes kernel caches.
	 * Creates the kernel series cache and traversal generator for GPU optimization.
	 *
	 * @param manager the scope input manager
	 * @param context the kernel structure context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		ScopeLifecycle.super.prepareScope(manager, context);
		getComputation().prepareScope(manager, context);

		this.kernelSeriesCache = KernelSeriesCache.create(getComputation(),
				data -> manager.argumentForInput(nameProvider).apply(() -> new Provider<>(data)));
		this.traversalGenerator = KernelTraversalOperationGenerator.create(getComputation(),
				data -> manager.argumentForInput(nameProvider).apply((Supplier) data));
	}

	/**
	 * Resets cached arguments and clears the kernel maximum cache.
	 * Should be called when the computation's arguments change.
	 */
	@Override
	public void resetArguments() {
		ScopeLifecycle.super.resetArguments();
		getComputation().resetArguments();
		this.kernelMaximum = null;
	}

	/**
	 * Returns the compiled scope, or {@code null} if not yet compiled.
	 *
	 * @return the compiled scope, or null
	 */
	public Scope<T> getScope() { return scope; }

	/**
	 * Compiles the computation into a scope.
	 * Performs scope generation, argument conversion, and simplification.
	 * Records timing metrics if a timing listener is configured.
	 *
	 * @return the compiled scope
	 * @throws HardwareException if compilation fails
	 */
	public synchronized Scope<T> compile() {
		if (scope != null) {
			warn("Attempting to compile an operation which was already compiled");
			return scope;
		}

		if (verboseCompile) log("Compiling " + Named.nameOf(getComputation()));

		try {
			Computation<T> c = getComputation();

			long start = System.nanoTime();

			scope = c.getScope(this);
			if (timing != null) {
				timing.recordDuration(getMetadata(), scope.getMetadata(),
						"getScope", System.nanoTime() - start);
			}

			start = System.nanoTime();
			scope.convertArgumentsToRequiredScopes(this);
			if (timing != null) {
				timing.recordDuration(getMetadata(), scope.getMetadata(),
						"convertRequired", System.nanoTime() - start);
			}

			scope = scope.simplify(this);

			if (verboseCompile) log("Done compiling " + Named.nameOf(getComputation()));
			return scope;
		} catch (Exception e) {
			throw new HardwareException("Cannot compile " + Named.nameOf(getComputation()), e);
		}
	}

	/**
	 * Performs post-compilation processing including shape validation and signature assignment.
	 * Validates that the scope metadata shape matches the computation shape if applicable.
	 *
	 * @throws IllegalArgumentException if there is a shape mismatch between scope and computation
	 */
	public synchronized void postCompile() {
		if (getComputation() instanceof Shape) {
			TraversalPolicy shape = scope.getMetadata().getShape();

			if (shape == null) {
				warn("Missing TraversalPolicy for Scope metadata");
				scope.setMetadata(scope.getMetadata().withShape(((Shape<?>) getComputation()).getShape()));
			} else if (!shape.equals(((Shape<?>) getComputation()).getShape())) {
				throw new IllegalArgumentException("Shape mismatch between Scope metadata and Computation");
			}
		}

		scope.setMetadata(scope.getMetadata().withSignature(signature()));
	}

	/**
	 * Returns {@code true} if the computation has been compiled into a scope.
	 *
	 * @return true if compiled, false otherwise
	 */
	public boolean isCompiled() { return scope != null; }

	/**
	 * Generates a unique signature for this compilation.
	 * For {@link Process} computations, appends the distinct child count to the signature.
	 *
	 * @return the signature string, or {@code null} if metadata has no signature
	 */
	@Override
	public String signature() {
		String signature = getMetadata().getSignature();
		if (signature == null) return null;

		if (computation instanceof Process<?,?>) {
			// TODO  This may not be enough information to distinguish between
			// TODO  operations, as a Process that had arguments (A, A, B) and
			// TODO  (A, B, B) would retain the same signature
			int distinct = ((Process<?,?>) computation).children()
					.collect(Collectors.toSet()).size();
			return signature + "&distinct=" + distinct + ";";
		}

		return signature;
	}

	/**
	 * Releases resources held by this compiler including the compiled scope,
	 * kernel series cache, and traversal generator.
	 */
	@Override
	public void destroy() {
		scope = null;

		if (kernelSeriesCache != null) {
			kernelSeriesCache.destroy();
			kernelSeriesCache = null;
		}

		if (traversalGenerator != null) {
			traversalGenerator.destroy();
			traversalGenerator = null;
		}
	}

	/**
	 * Returns a description of this compiler.
	 * Delegates to the computation's description if it implements {@link Describable}.
	 *
	 * @return the description string
	 */
	@Override
	public String describe() {
		if (getComputation() instanceof Describable) {
			return ((Describable) getComputation()).describe();
		} else {
			return toString();
		}
	}

	/** Returns the console for logging output. */
	@Override
	public Console console() { return Hardware.console; }
}
