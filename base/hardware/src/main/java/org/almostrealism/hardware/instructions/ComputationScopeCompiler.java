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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import io.almostrealism.uml.Named;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;
import org.almostrealism.io.SystemUtils;

import java.util.List;
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
 *   <li><strong>Scope simplification:</strong> Optimize and flatten the scope tree</li>
 *   <li><strong>Metadata enrichment:</strong> Add shape, signature, and traversal policy</li>
 *   <li><strong>Kernel structure support:</strong> Manage kernel series and traversal caching</li>
 * </ol>
 *
 * <h2>Compilation Lifecycle</h2>
 *
 * <pre>{@code
 * Computation<Matrix> computation = add(a, b);
 * ComputationScopeCompiler<Matrix> compiler =
 *     new ComputationScopeCompiler<>(computation);
 *
 * // Prepare scope inputs with the structure context that belongs to
 * // the instruction set manager owning the compiled result
 * ArgumentProvider inputManager = ...;
 * compiler.prepareScope(inputManager, structureContext);
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
 * <p>The compiler does not own kernel structure state: it compiles with the
 * {@link KernelStructureContext} supplied to
 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)}, which belongs to the
 * instruction set manager and owns the kernel series cache and traversal operations the
 * compiled code references (see
 * {@link org.almostrealism.hardware.kernel.CompiledKernelStructureContext}). This keeps
 * the lifecycle of those resources aligned with the compiled instructions rather than
 * with the compiler, which is transient.</p>
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
public class ComputationScopeCompiler<T> implements
		ScopeLifecycle, Destroyable, OperationInfo, Signature, ConsoleFeatures {
	/** Enables verbose logging during compilation when set to {@code true}. */
	public static boolean verboseCompile = SystemUtils.isEnabled("AR_HARDWARE_VERBOSE_COMPILE").orElse(false);

	/** Optional listener for recording scope compilation timing metrics. */
	public static ScopeTimingListener timing;

	/** The computation being compiled into a scope. */
	private Computation<T> computation;

	/** The compiled scope, or {@code null} if not yet compiled. */
	private Scope<T> scope;

	/**
	 * The kernel structure context this compiler compiles with, supplied through
	 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)}. It belongs to the
	 * instruction set manager and owns the kernel structure resources the compiled code
	 * references; this compiler only consults it.
	 */
	private KernelStructureContext structureContext;

	/**
	 * Constructs a new compiler for the specified computation.
	 *
	 * @param computation  the computation to compile into a scope
	 */
	public ComputationScopeCompiler(Computation<T> computation) {
		this.computation = computation;
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
	 * Prepares scope inputs with the given kernel structure context.
	 *
	 * <p>The context belongs to the instruction set manager and owns whatever kernel
	 * structure resources it exposes; this compiler retains it only to compile with.
	 * Preparing is safe to repeat — for example to replay an already-compiled
	 * computation's argument fold against a fresh {@link ArgumentProvider} when
	 * verifying reuse — because the context materializes its resources at most once,
	 * whatever how many times the scope is prepared.</p>
	 *
	 * @param manager the scope input manager
	 * @param context the kernel structure context of the compiled instructions
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		ScopeLifecycle.super.prepareScope(manager, context);
		getComputation().prepareScope(manager, context);
		this.structureContext = context;
	}

	/**
	 * Resets cached arguments and clears the kernel maximum cache.
	 * Should be called when the computation's arguments change.
	 */
	@Override
	public void resetArguments() {
		ScopeLifecycle.super.resetArguments();
		getComputation().resetArguments();
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

			if (structureContext == null) {
				throw new HardwareException(
						"No KernelStructureContext was supplied via prepareScope before compiling " +
						Named.nameOf(getComputation()));
			}

			scope = c.getScope(structureContext);
			if (timing != null) {
				timing.recordDuration(getMetadata(), scope.getMetadata(),
						"getScope", System.nanoTime() - start);
			}

			start = System.nanoTime();
			scope.convertArgumentsToRequiredScopes(structureContext);
			if (timing != null) {
				timing.recordDuration(getMetadata(), scope.getMetadata(),
						"convertRequired", System.nanoTime() - start);
			}

			scope = scope.simplify(structureContext);

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
	 * Releases this compiler's own transient state.
	 *
	 * <p>Nothing the compiled code depends on is destroyed here: the kernel structure
	 * resources belong to the instruction set manager's
	 * {@link org.almostrealism.hardware.kernel.CompiledKernelStructureContext}, so
	 * compiled instructions remain usable after the compiler itself is destroyed.</p>
	 */
	@Override
	public void destroy() {
		scope = null;
		structureContext = null;
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
