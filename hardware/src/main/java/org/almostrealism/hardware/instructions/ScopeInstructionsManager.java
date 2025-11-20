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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.arguments.ProcessArgumentMap;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Standard {@link InstructionSetManager} implementation that compiles {@link Scope} to {@link InstructionSet} and caches compiled operators.
 *
 * <p>{@link ScopeInstructionsManager} manages the full lifecycle of operation compilation and execution:</p>
 * <ol>
 *   <li><strong>Lazy compilation:</strong> Compile {@link Scope} on first {@code getOperator()} call</li>
 *   <li><strong>Instruction caching:</strong> Store compiled {@link InstructionSet} for reuse</li>
 *   <li><strong>Argument mapping:</strong> Track {@link ProcessArgumentMap} for Process trees</li>
 *   <li><strong>Output tracking:</strong> Maintain output argument indices and offsets per key</li>
 *   <li><strong>Destroy listeners:</strong> Notify dependents when resources are released</li>
 * </ol>
 *
 * <h2>Compilation Lifecycle</h2>
 *
 * <p>The manager uses a {@code Supplier<Scope<?>>} for lazy compilation:</p>
 *
 * <pre>{@code
 * // Create compiler
 * ComputationScopeCompiler<Matrix> compiler = new ComputationScopeCompiler<>(computation, nameProvider);
 *
 * // Create manager with scope supplier (NOT YET COMPILED!)
 * ScopeInstructionsManager<ScopeSignatureExecutionKey> manager =
 *     new ScopeInstructionsManager<>(
 *         computeContext,
 *         () -> compiler.compile(),  // Lazy compilation
 *         null
 *     );
 *
 * // First call triggers compilation
 * Execution op = manager.getOperator(key);  // Compiles here
 *
 * // Subsequent calls reuse cached instructions
 * Execution op2 = manager.getOperator(key);  // No compilation, uses cache
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The {@code getOperator()} method is {@code synchronized} to ensure only one compilation occurs,
 * even under concurrent access:</p>
 *
 * <pre>{@code
 * @Override
 * public synchronized Execution getOperator(K key) {
 *     if (operators == null || operators.isDestroyed()) {
 *         operators = getComputeContext().deliver(getScope());  // Compile once
 *     }
 *     return operators.get(scopeName, arguments.size());
 * }
 * }</pre>
 *
 * <h2>Output Argument Tracking</h2>
 *
 * <p>As a {@link ComputableInstructionSetManager}, tracks output locations for each operation:</p>
 *
 * <pre>{@code
 * ScopeInstructionsManager<K> manager = ...;
 *
 * // Set output location for this operation
 * manager.setOutputArgumentIndex(key, 2);   // Output is 3rd argument
 * manager.setOutputOffset(key, 1024);       // Offset 1024 bytes into buffer
 *
 * // Retrieve output location
 * int argIndex = manager.getOutputArgumentIndex(key);  // Returns 2
 * int offset = manager.getOutputOffset(key);           // Returns 1024
 * }</pre>
 *
 * <h2>Process Argument Mapping</h2>
 *
 * <p>When used with {@link Process} trees, populates a {@link ProcessArgumentMap} for argument routing:</p>
 *
 * <pre>{@code
 * ScopeInstructionsManager<K> manager = ...;
 * manager.setProcess(process);  // Associate with Process
 *
 * // After getScope() is called, argument map is populated
 * ProcessArgumentMap argMap = manager.getArgumentMap();
 * }</pre>
 *
 * <h2>Access Listeners</h2>
 *
 * <p>Supports notification when operations are accessed, useful for profiling or resource tracking:</p>
 *
 * <pre>{@code
 * ScopeInstructionsManager<K> manager = new ScopeInstructionsManager<>(
 *     computeContext,
 *     scopeSupplier,
 *     mgr -> System.out.println("Operation accessed: " + mgr.getScopeArguments())
 * );
 * }</pre>
 *
 * <h2>Destroy Listeners</h2>
 *
 * <p>Register callbacks to be notified when the manager is destroyed:</p>
 *
 * <pre>{@code
 * manager.addDestroyListener(() -> {
 *     System.out.println("Manager destroyed, cleaning up...");
 *     // Release dependent resources
 * });
 * }</pre>
 *
 * <h2>Resource Management</h2>
 *
 * <p>When destroyed, releases all resources and notifies listeners:</p>
 *
 * <pre>{@code
 * manager.destroy();
 * // - Destroys InstructionSet (releases native code)
 * // - Executes all destroy listeners
 * // - Clears cached operators
 * }</pre>
 *
 * @param <K> The {@link ExecutionKey} type used for operation lookup
 * @see InstructionSetManager
 * @see ComputableInstructionSetManager
 * @see ComputationScopeCompiler
 * @see ProcessArgumentMap
 */
public class ScopeInstructionsManager<K extends ExecutionKey>
		extends AbstractInstructionSetManager<K>
		implements ComputableInstructionSetManager<K>, ConsoleFeatures {

	private Supplier<Scope<?>> scope;
	private Consumer<ScopeInstructionsManager<K>> accessListener;
	private List<Runnable> destroyListeners;
	private Process<?, ?> process;

	private String scopeName;
	private List<Supplier<Evaluable<?>>> inputs;
	private List<Argument<?>> arguments;
	private ProcessArgumentMap argumentMap;

	private Map<K, Integer> outputArgIndices;
	private Map<K, Integer> outputOffsets;

	private InstructionSet operators;

	public ScopeInstructionsManager(ComputeContext<?> computeContext,
									Supplier<Scope<?>> scope,
									Consumer<ScopeInstructionsManager<K>> accessListener) {
		super(computeContext);
		this.scope = scope;
		this.accessListener = accessListener;
		this.destroyListeners = new ArrayList<>();
		this.outputArgIndices = new HashMap<>();
		this.outputOffsets = new HashMap<>();
	}

	public Process<?, ?> getProcess() { return process; }

	public void setProcess(Process<?, ?> process) { this.process = process; }

	public void populateArgumentMap(Process<?, ?> process) {
		this.argumentMap = new ProcessArgumentMap(process,
				arguments.stream().map(Argument::getVariable)
						.map(arg -> arg instanceof ArrayVariable<?> ? (ArrayVariable<?>) arg : null)
						.filter(Objects::nonNull)
						.collect(Collectors.toList()));
	}

	public ProcessArgumentMap getArgumentMap() { return argumentMap; }

	@Override
	public int getOutputArgumentIndex(K key) {
		Integer argIndex = outputArgIndices.get(key);
		if (argIndex == null) {
			return -1;
		}

		return argIndex;
	}

	public void setOutputArgumentIndex(K key, int outputArgIndex) {
		this.outputArgIndices.put(key, outputArgIndex);
	}

	@Override
	public int getOutputOffset(K key) {
		return outputOffsets.get(key);
	}

	public void setOutputOffset(K key, int outputOffset) {
		this.outputOffsets.put(key, outputOffset);
	}

	public List<Supplier<Evaluable<?>>> getScopeInputs() { return inputs; }

	public List<Argument<?>> getScopeArguments() { return arguments; }

	public void addDestroyListener(Runnable listener) {
		destroyListeners.add(listener);
	}

	protected Scope<?> getScope() {
		if (scopeName != null) {
			warn("Repeated attempt to retrieve Scope");
		}

		Scope<?> s = scope.get();
		scopeName = s.getName();
		inputs = s.getInputs();
		arguments = s.getArguments();

		if (process != null) {
			populateArgumentMap(process);
		}

		return s;
	}

	protected synchronized InstructionSet getInstructionSet() {
		if (operators == null || operators.isDestroyed()) {
			operators = getComputeContext().deliver(getScope());
			HardwareOperator.recordCompilation(!getComputeContext().isCPU());
		}

		return operators;
	}

	@Override
	public synchronized Execution getOperator(K key) {
		try {
			if (operators == null || operators.isDestroyed()) {
				operators = getComputeContext().deliver(getScope());
				HardwareOperator.recordCompilation(!getComputeContext().isCPU());
			}

			return operators.get(scopeName, arguments.size());
		} finally {
			if (accessListener != null) {
				accessListener.accept(this);
			}
		}
	}

	@Override
	public void destroy() {
		if (operators != null) {
			operators.destroy();
			operators = null;
		}

		destroyListeners.forEach(Runnable::run);
	}

	@Override
	public Console console() { return Hardware.console; }
}
