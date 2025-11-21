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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DefaultNameProvider;
import io.almostrealism.code.Execution;
import io.almostrealism.code.NamedFunction;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.NameProvider;
import io.almostrealism.compute.Process;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.uml.Named;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.arguments.ProcessArgumentMap;
import org.almostrealism.hardware.instructions.ComputableInstructionSetManager;
import org.almostrealism.hardware.instructions.ComputationInstructionsManager;
import org.almostrealism.hardware.instructions.ComputationScopeCompiler;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.instructions.ScopeInstructionsManager;
import org.almostrealism.hardware.instructions.ScopeSignatureExecutionKey;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.io.Describable;
import org.almostrealism.lifecycle.WeakRunnable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Accelerated operation that wraps a {@link Computation} for compilation and execution on hardware accelerators.
 *
 * <p>{@link AcceleratedComputationOperation} bridges the gap between high-level {@link Computation} abstractions
 * and low-level hardware execution by compiling computations into {@link Scope} objects, managing instruction set
 * caching via signatures, and coordinating execution through the {@link AcceleratedOperation} framework.</p>
 *
 * <h2>Computation Compilation</h2>
 *
 * <p>The wrapped {@link Computation} is compiled into executable code via {@link ComputationScopeCompiler}:</p>
 * <pre>{@code
 * // Create computation
 * Computation<PackedCollection<?>> multiply = c -> c.multiply(2.0);
 *
 * // Wrap for acceleration
 * AcceleratedComputationOperation op = new AcceleratedComputationOperation(
 *     Hardware.getLocalHardware().getComputeContext(),
 *     multiply,
 *     true  // kernel mode
 * );
 *
 * // Compilation happens during prepareScope or first execution
 * op.prepareScope();  // Compiles Computation -> Scope -> Kernel/Native
 * }</pre>
 *
 * <h2>Instruction Set Reuse via Signatures</h2>
 *
 * <p>When {@link ScopeSettings#enableInstructionSetReuse} is true and the computation provides
 * a signature, compiled kernels are cached and shared across multiple instances:</p>
 * <pre>{@code
 * // First computation with signature "vectorAdd"
 * Computation<PackedCollection<?>> add1 = ...;
 * add1.getMetadata().setSignature("vectorAdd");
 * AcceleratedComputationOperation op1 = new AcceleratedComputationOperation(..., add1, true);
 * op1.prepareScope();  // Compiles and caches under signature "vectorAdd"
 *
 * // Second computation with same signature
 * Computation<PackedCollection<?>> add2 = ...;
 * add2.getMetadata().setSignature("vectorAdd");
 * AcceleratedComputationOperation op2 = new AcceleratedComputationOperation(..., add2, true);
 * op2.prepareScope();  // Reuses cached kernel from "vectorAdd"
 * }</pre>
 *
 * <p>This significantly reduces compilation overhead for repeated operations with the same structure.</p>
 *
 * <h2>Instruction Set Management</h2>
 *
 * <p>Two instruction set managers are used depending on signature availability:</p>
 * <ul>
 *   <li><b>{@link ScopeInstructionsManager}:</b> When signature is available, enables cross-instance caching</li>
 *   <li><b>{@link ComputationInstructionsManager}:</b> When no signature, creates operation-specific instruction set</li>
 * </ul>
 *
 * <pre>{@code
 * @Override
 * public ComputableInstructionSetManager getInstructionSetManager() {
 *     String signature = signature();
 *
 *     if (ScopeSettings.enableInstructionSetReuse && signature != null) {
 *         // Reusable instruction set shared across operations
 *         return computer.getScopeInstructionsManager(signature, ...);
 *     } else {
 *         // Operation-specific instruction set
 *         return new ComputationInstructionsManager(...);
 *     }
 * }
 * }</pre>
 *
 * <h2>Execution Keys</h2>
 *
 * <p>Execution keys uniquely identify compiled kernels within an instruction set:</p>
 * <ul>
 *   <li><b>{@link ScopeSignatureExecutionKey}:</b> When signature-based caching is enabled</li>
 *   <li><b>{@link DefaultExecutionKey}:</b> Based on function name and argument count otherwise</li>
 * </ul>
 *
 * <h2>Integration with Computation</h2>
 *
 * <p>Properties are delegated to the wrapped {@link Computation}:</p>
 * <ul>
 *   <li><b>Metadata:</b> {@link #getMetadata()} delegates to {@link OperationInfo#getMetadata()}</li>
 *   <li><b>Count:</b> {@link #getCountLong()} delegates to {@link Countable#getCountLong()} or {@link ParallelProcess#getParallelism()}</li>
 *   <li><b>Compute Requirements:</b> {@link #getComputeRequirements()} from {@link ComputationScopeCompiler}</li>
 *   <li><b>Name:</b> {@link #getName()} via {@link Named}</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>{@code
 * // Create operation
 * AcceleratedComputationOperation op = new AcceleratedComputationOperation(context, computation, true);
 *
 * // Prepare scope (triggers compilation if needed)
 * op.prepareScope();
 *
 * // Execute (uses cached kernel if available)
 * AcceleratedProcessDetails process = op.apply(output, args);
 *
 * // Cleanup
 * op.destroy();
 * }</pre>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Creating from Computation</h3>
 * <pre>{@code
 * // Define computation
 * Computation<PackedCollection<?>> normalize = c -> c.divide(c.max());
 *
 * // Wrap for GPU execution
 * AcceleratedComputationOperation normalizeOp = new AcceleratedComputationOperation(
 *     Hardware.getLocalHardware().getComputeContext(),
 *     normalize,
 *     true
 * );
 * }</pre>
 *
 * <h3>Using with Signature-Based Caching</h3>
 * <pre>{@code
 * // Enable instruction set reuse
 * ScopeSettings.enableInstructionSetReuse = true;
 *
 * // Create computation with signature
 * Computation<PackedCollection<?>> op = ...;
 * op.getMetadata().setSignature("myOperation");
 *
 * // All operations with this signature share compiled kernels
 * AcceleratedComputationOperation acc = new AcceleratedComputationOperation(context, op, true);
 * }</pre>
 *
 * @param <T> The type of data produced by the wrapped {@link Computation}
 * @see AcceleratedOperation
 * @see Computation
 * @see ComputationScopeCompiler
 * @see ScopeInstructionsManager
 * @see ComputableInstructionSetManager
 */
public class AcceleratedComputationOperation<T> extends AcceleratedOperation<MemoryData>
		implements Countable, Signature {

	private Computation<T> computation;
	private ComputationScopeCompiler<T> compiler;

	private ComputableInstructionSetManager<?> instructions;
	private ExecutionKey executionKey;

	/**
	 * Creates an accelerated operation for the specified computation.
	 *
	 * <p>Wraps the {@link Computation} for hardware execution, initializing the function
	 * name and preparing for compilation. The operation is not compiled until first use.</p>
	 *
	 * @param context The {@link ComputeContext} to execute on (OpenCL, Metal, JNI, etc.)
	 * @param c The {@link Computation} to accelerate
	 * @param kernel true to compile as a hardware kernel, false for operation-level execution
	 */
	public AcceleratedComputationOperation(ComputeContext<MemoryData> context, Computation<T> c, boolean kernel) {
		super(context, kernel);
		this.computation = c;
		init();
	}

	/**
	 * Initializes the function name from the wrapped {@link Computation}.
	 *
	 * <p>If the computation implements {@link NamedFunction}, uses its function name.
	 * Otherwise, generates a name from the computation's class.</p>
	 */
	public void init() {
		if (getComputation() instanceof NamedFunction) {
			setFunctionName(((NamedFunction) getComputation()).getFunctionName());
		} else {
			setFunctionName(functionName(getComputation().getClass()));
		}
	}

	/**
	 * Returns the {@link NameProvider} for the wrapped computation.
	 *
	 * <p>Provides naming services for scope variables and functions during compilation.</p>
	 *
	 * @return The name provider for this computation
	 * @throws UnsupportedOperationException if computation is not a {@link NamedFunction}
	 */
	public NameProvider getNameProvider() {
		if (getComputation() instanceof NamedFunction) {
			return new DefaultNameProvider((NamedFunction) getComputation());
		}

		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the wrapped {@link Computation} being accelerated.
	 *
	 * @return The computation
	 */
	public Computation<T> getComputation() { return computation; }

	/**
	 * Returns the {@link ComputationScopeCompiler} for this operation.
	 *
	 * <p>Creates the compiler on first access. The compiler translates the
	 * {@link Computation} into a {@link Scope} suitable for hardware execution.</p>
	 *
	 * @return The computation scope compiler
	 */
	public ComputationScopeCompiler<T> getCompiler() {
		if (compiler == null) {
			compiler = new ComputationScopeCompiler<>(getComputation(), getNameProvider());
		}

		return compiler;
	}

	/**
	 * Returns the {@link OperationMetadata} for this operation.
	 *
	 * <p>Delegates to the wrapped computation if it implements {@link OperationInfo}.</p>
	 *
	 * @return The operation metadata
	 * @throws UnsupportedOperationException if computation does not provide metadata
	 */
	@Override
	public OperationMetadata getMetadata() {
		if (computation instanceof OperationInfo) {
			return ((OperationInfo) computation).getMetadata();
		}

		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the parallelism count for this operation.
	 *
	 * <p>Determines how many parallel work items to dispatch:</p>
	 * <ul>
	 *   <li>If computation is a {@link ParallelProcess}, returns its parallelism</li>
	 *   <li>If computation is {@link Countable}, returns its count</li>
	 *   <li>Otherwise returns 1 (sequential execution)</li>
	 * </ul>
	 *
	 * @return The number of parallel work items
	 */
	@Override
	public long getCountLong() {
		if (getComputation() instanceof ParallelProcess) {
			return ((ParallelProcess) getComputation()).getParallelism();
		}

		return getComputation() instanceof Countable ? ((Countable) getComputation()).getCountLong() : 1;
	}

	/**
	 * Returns whether the parallelism count is fixed at compile time.
	 *
	 * <p>Fixed counts allow kernel optimizations. Returns true unless the
	 * computation is a {@link Countable} with variable count.</p>
	 *
	 * @return true if count is fixed, false if dynamic
	 */
	@Override
	public boolean isFixedCount() {
		return !(getComputation() instanceof Countable) || ((Countable) getComputation()).isFixedCount();
	}

	/**
	 * Returns the human-readable name of this operation.
	 *
	 * <p>Delegates to {@link Named#nameOf(Object)} on the wrapped computation.</p>
	 *
	 * @return The operation name
	 */
	@Override
	public String getName() {
		return Named.nameOf(getComputation());
	}

	/**
	 * Returns the compute requirements for this operation.
	 *
	 * <p>Requirements specify hardware constraints (GPU-only, CPU-only, memory limits, etc.)
	 * that filter context selection. Delegates to the {@link ComputationScopeCompiler}.</p>
	 *
	 * @return The list of compute requirements
	 */
	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		return getCompiler().getComputeRequirements();
	}

	/**
	 * Returns the instruction set manager for compiled kernels.
	 *
	 * <p>Creates the manager on first access, choosing between:</p>
	 * <ul>
	 *   <li><b>{@link ScopeInstructionsManager}:</b> When signature-based reuse is enabled,
	 *       allows kernel sharing across operations with the same signature</li>
	 *   <li><b>{@link ComputationInstructionsManager}:</b> Operation-specific manager
	 *       when reuse is disabled or no signature is available</li>
	 * </ul>
	 *
	 * <p>The manager handles kernel compilation, caching, and lifecycle.</p>
	 *
	 * @param <K> The execution key type
	 * @return The instruction set manager
	 */
	@Override
	public <K extends ExecutionKey> ComputableInstructionSetManager<K> getInstructionSetManager() {
		if (instructions == null) {
			String signature = signature();

			if (ScopeSettings.enableInstructionSetReuse && signature != null) {
				DefaultComputer computer = Hardware.getLocalHardware().getComputer();

				instructions = computer.getScopeInstructionsManager(
						signature, getComputation(), getComputeContext(), this::getScope);
				((ScopeInstructionsManager) instructions)
						.addDestroyListener(new WeakRunnable<>(this, AcceleratedComputationOperation::resetInstructions));
			} else {
				instructions = new ComputationInstructionsManager(
						getComputeContext(), this::getScope);
				if (!compiler.isCompiled()) compile();
			}
		}

		return (ComputableInstructionSetManager) instructions;
	}

	/**
	 * Returns the execution key uniquely identifying this operation's kernel.
	 *
	 * <p>The execution key type depends on whether signature-based caching is enabled:</p>
	 * <ul>
	 *   <li><b>{@link ScopeSignatureExecutionKey}:</b> When signature is available,
	 *       enables cross-operation kernel sharing</li>
	 *   <li><b>{@link DefaultExecutionKey}:</b> Based on function name and argument count,
	 *       unique to this operation instance</li>
	 * </ul>
	 *
	 * @return The execution key
	 */
	@Override
	public ExecutionKey getExecutionKey() {
		if (executionKey != null)
			return executionKey;

		String signature = getMetadata().getSignature();

		if (ScopeSettings.enableInstructionSetReuse && signature != null) {
			return new ScopeSignatureExecutionKey(signature);
		} else {
			return new DefaultExecutionKey(getFunctionName(), getArgsCount());
		}
	}

	@Override
	protected int getOutputArgumentIndex() {
		return getInstructionSetManager().getOutputArgumentIndex(getExecutionKey());
	}

	/**
	 * Prepares argument mappings for kernel execution.
	 *
	 * <p>Delegates to both the superclass and the {@link ComputationScopeCompiler}
	 * to ensure all arguments (operation-level and computation-level) are properly
	 * mapped before execution.</p>
	 *
	 * @param map The argument map to populate
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		getCompiler().prepareArguments(map);
	}

	/**
	 * Prepares the scope for compilation by registering inputs.
	 *
	 * <p>Delegates to the {@link ComputationScopeCompiler} to prepare the scope's
	 * input variables.</p>
	 *
	 * @param manager The scope input manager
	 */
	@Override
	protected void prepareScope(ScopeInputManager manager) {
		getCompiler().prepareScope(manager);
	}

	/**
	 * Prepares the scope for compilation with kernel structure context.
	 *
	 * <p>Delegates to both the superclass and the {@link ComputationScopeCompiler}
	 * to prepare the scope with kernel structure information (memory layout, alignment, etc.).</p>
	 *
	 * @param manager The scope input manager
	 * @param context The kernel structure context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		getCompiler().prepareScope(manager, context);
	}

	/**
	 * Resets argument mappings to allow recompilation.
	 *
	 * <p>Clears argument state in both the operation and the compiler, allowing
	 * fresh argument preparation for a new compilation pass.</p>
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();
		getCompiler().resetArguments();
	}

	/**
	 * Loads the compiled kernel and prepares it for execution.
	 *
	 * <p>If using shared instructions via signature-based caching and the computation
	 * is a {@link Process}, sets up argument substitutions to map this operation's
	 * arguments to the shared kernel's parameters. Otherwise, compiles the operation
	 * from scratch.</p>
	 *
	 * <p>This method enables efficient instruction set reuse while allowing each
	 * operation instance to maintain its own argument bindings.</p>
	 *
	 * @return The loaded execution operator
	 */
	@Override
	public Execution load() {
		Execution operator = super.load();

		if (getArguments() == null) {
			if (getComputation() instanceof Process<?,?>) {
				// If the Computation is a Process, the structure of the Process
				// tree can be used to substitute arguments from this Computation
				// for those in the shared Execution
				ScopeInstructionsManager manager = (ScopeInstructionsManager) getInstructionSetManager();
				setupArguments(manager.getScopeInputs(), manager.getScopeArguments());

				ProcessArgumentMap map = new ProcessArgumentMap(manager.getArgumentMap());
				map.putSubstitutions((Process<?,?>) getComputation());
				setEvaluator(map);
			} else {
				warn("Unable to reuse instructions for " + getFunctionName() +
						" because " + getComputation() + " is not a Process");
				compile();
			}
		}

		return operator;
	}

	protected Scope<T> getScope() {
		if (getCompiler().getScope() == null) {
			compile();
		}

		return getCompiler().getScope();
	}

	/**
	 * Compiles the wrapped {@link Computation} into a hardware-executable {@link Scope}.
	 *
	 * <p>Compilation process:</p>
	 * <ol>
	 *   <li>Creates an {@link ExpressionCache} for optimizing repeated sub-expressions</li>
	 *   <li>Prepares the scope by registering inputs</li>
	 *   <li>Delegates to {@link ComputationScopeCompiler#compile()} to translate the computation</li>
	 *   <li>Performs post-compilation setup via {@link #postCompile()}</li>
	 * </ol>
	 *
	 * <p>This method is synchronized to prevent concurrent compilation of the same operation.</p>
	 *
	 * @return The compiled scope
	 */
	public synchronized Scope<T> compile() {
		new ExpressionCache().use(getMetadata(), () -> {
			prepareScope();
			getCompiler().compile();
			postCompile();
		});

		return getCompiler().getScope();
	}

	/**
	 * Deprecated compile method for manual instruction set management.
	 *
	 * <p>This method is deprecated and should not be used in new code. It exists
	 * for backward compatibility only.</p>
	 *
	 * @param instructions The instruction set manager to use
	 * @param executionKey The execution key to use
	 * @deprecated Manual instruction set management is no longer recommended
	 */
	@Deprecated
	public void compile(ComputableInstructionSetManager<?> instructions, ExecutionKey executionKey) {
		warn("Use of deprecated compile method");
		this.instructions = instructions;
		this.executionKey = executionKey;
	}

	protected void resetInstructions() {
		instructions = null;
		executionKey = null;

		if (compiler != null) {
			compiler.destroy();
			compiler = null;
		}
	}

	/**
	 * Performs post-compilation setup after the scope is compiled.
	 *
	 * <p>Sets up argument mappings from the compiled scope and delegates to
	 * {@link ComputationScopeCompiler#postCompile()} for additional compiler-specific
	 * post-processing.</p>
	 *
	 * <p>This method is synchronized to ensure thread-safe argument setup.</p>
	 */
	public synchronized void postCompile() {
		setupArguments(getCompiler().getScope());
		getCompiler().postCompile();
	}

	protected void setupArguments(Scope<?> scope) {
		setupArguments(scope.getInputs(), scope.getArguments());
	}

	protected void setupArguments(List<Supplier<Evaluable<? extends MemoryData>>> inputs,
								  List<Argument<? extends MemoryData>> arguments) {
		setInputs(inputs);
		setArguments(arguments);
	}

	@Override
	protected AcceleratedProcessDetails getProcessDetails(MemoryBank output, Object[] args) {
		AcceleratedProcessDetails process = super.getProcessDetails(output, args);

		if (!getCompiler().isValidKernelSize(process.getKernelSize())) {
			throw new UnsupportedOperationException();
		}

		return process;
	}

	/**
	 * Returns the output variable from the wrapped computation.
	 *
	 * <p>The output variable identifies which argument contains the result after
	 * kernel execution. Used by evaluables to extract results.</p>
	 *
	 * @return The output variable
	 */
	public Variable getOutputVariable() {
		return getComputation().getOutputVariable();
	}

	/**
	 * Returns whether this operation uses aggregated input memory.
	 *
	 * <p>Aggregated inputs combine multiple separate allocations into a single
	 * contiguous buffer, improving GPU performance. Always returns true for
	 * {@link AcceleratedComputationOperation}.</p>
	 *
	 * @return true (always uses aggregated inputs)
	 */
	@Override
	public boolean isAggregatedInput() { return true; }

	/**
	 * Returns the signature for instruction set caching.
	 *
	 * <p>The signature uniquely identifies the computation structure, enabling
	 * kernel sharing across operations with identical signatures. Delegates to
	 * {@link ComputationScopeCompiler#signature()}.</p>
	 *
	 * @return The signature string, or null if no signature is available
	 */
	@Override
	public String signature() { return getCompiler().signature(); }

	@Override
	protected void waitFor(Semaphore semaphore) {
		if (getComputeContext().isExecutorThread()) {
			throw new IllegalStateException("Attempting to block the ComputeContext executor");
		}

		super.waitFor(semaphore);
	}

	/**
	 * Returns a human-readable description of this operation.
	 *
	 * <p>If the wrapped computation implements {@link Describable}, delegates to
	 * its {@link Describable#describe()} method. Otherwise, returns the default
	 * description from {@link AcceleratedOperation#describe()}.</p>
	 *
	 * @return A description of this operation
	 */
	@Override
	public String describe() {
		if (getComputation() instanceof Describable) {
			return ((Describable) getComputation()).describe();
		} else {
			return super.describe();
		}
	}

	/**
	 * Destroys this operation and releases all associated resources.
	 *
	 * <p>Cleanup process:</p>
	 * <ol>
	 *   <li>Destroys superclass resources ({@link AcceleratedOperation#destroy()})</li>
	 *   <li>Clears input references</li>
	 *   <li>Destroys the wrapped computation if it's {@link Destroyable}</li>
	 *   <li>Destroys the {@link ComputationScopeCompiler}</li>
	 * </ol>
	 *
	 * <p>After calling this method, the operation should not be used again.</p>
	 */
	@Override
	public void destroy() {
		super.destroy();

		setInputs((List) null);

		if (getComputation() instanceof Destroyable) {
			((Destroyable) getComputation()).destroy();
		}

		if (compiler != null) {
			compiler.destroy();
			compiler = null;
		}
	}
}
