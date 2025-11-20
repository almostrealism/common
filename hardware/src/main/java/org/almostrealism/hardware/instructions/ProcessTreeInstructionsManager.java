/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.Execution;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArgumentList;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.arguments.AcceleratedOperationContainer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tree-based {@link InstructionSetManager} that traverses {@link Process} hierarchies to extract and manage operations.
 *
 * <p>{@link ProcessTreeInstructionsManager} provides a depth-first traversal approach for compiling
 * {@link Process} trees, where each operation is identified by its {@link ProcessTreePositionKey} position
 * in the tree hierarchy.</p>
 *
 * <h2>Deprecation Notice</h2>
 *
 * <p><strong>WARNING:</strong> This class is deprecated in favor of {@link ScopeInstructionsManager}
 * with {@link ScopeSignatureExecutionKey}. The tree-based approach has been superseded by
 * signature-based caching for better performance and maintainability.</p>
 *
 * <h2>Tree Traversal Workflow</h2>
 *
 * <pre>{@code
 * // Create manager
 * ProcessTreeInstructionsManager manager = new ProcessTreeInstructionsManager();
 *
 * // Build computation graph
 * Process<?, Matrix> a = constant(matrixA);
 * Process<?, Matrix> b = constant(matrixB);
 * Process<?, Matrix> ab = matmul(a, b);
 * Process<?, Matrix> result = add(ab, c);
 *
 * // Extract all operations from tree
 * Process<?, Matrix> compiled = manager.extractAll(result);
 *
 * // Access individual operations by position
 * ProcessTreePositionKey rootKey = new ProcessTreePositionKey();      // []
 * ProcessTreePositionKey matmulKey = new ProcessTreePositionKey(0);   // [0]
 *
 * Execution addOp = manager.getOperator(rootKey);
 * Execution matmulOp = manager.getOperator(matmulKey);
 * }</pre>
 *
 * <h2>Key Methods</h2>
 *
 * <ul>
 *   <li><strong>extractAll(Process):</strong> Traverse tree and extract all {@link AcceleratedOperation} instances</li>
 *   <li><strong>replaceAll(Process):</strong> Traverse tree and recompile all operations with new manager</li>
 *   <li><strong>applyContainer(Process):</strong> Wrap compiled tree in {@link AcceleratedOperationContainer}</li>
 *   <li><strong>traverseAll(ArgumentList, BiConsumer):</strong> Generic depth-first traversal with callback</li>
 * </ul>
 *
 * <h2>Operation Extraction</h2>
 *
 * <p>The {@code extractAll()} method performs depth-first traversal to extract compiled operations:</p>
 *
 * <pre>{@code
 * ProcessTreeInstructionsManager manager = new ProcessTreeInstructionsManager();
 *
 * // Extract operations from process tree
 * Process<?, Matrix> compiled = manager.extractAll(process);
 *
 * // Now manager contains all operations:
 * // - Position []: Root add operation
 * // - Position [0]: Left matmul operation
 * // - Position [1]: Right constant operation
 * }</pre>
 *
 * <h2>Container Application</h2>
 *
 * <p>{@code applyContainer()} wraps the compiled tree in a container for batch execution:</p>
 *
 * <pre>{@code
 * AcceleratedOperationContainer<MemoryData> container = manager.applyContainer(process);
 *
 * // All operations in tree now use this container as their evaluator
 * MemoryData result = container.evaluate();
 * }</pre>
 *
 * <h2>Verbose Logging</h2>
 *
 * <p>Set {@code ProcessTreeInstructionsManager.verboseLogs = true} for detailed traversal logs:</p>
 *
 * <pre>{@code
 * ProcessTreeInstructionsManager.verboseLogs = true;
 *
 * // Logs:
 * // Retrieving Execution for [0, 1]
 * // Extracted instructions from MatMul_f64_3_2
 * // Replacing instructions for Add_f64_3_2
 * }</pre>
 *
 * <h2>Output Argument Tracking</h2>
 *
 * <p>Implements {@link ComputableInstructionSetManager} to track output locations:</p>
 *
 * <pre>{@code
 * ProcessTreePositionKey key = new ProcessTreePositionKey(0);
 * int outputIndex = manager.getOutputArgumentIndex(key);
 * int outputOffset = manager.getOutputOffset(key);
 * }</pre>
 *
 * <h2>Migration to ScopeInstructionsManager</h2>
 *
 * <p>Modern code should use {@link ScopeInstructionsManager} instead:</p>
 *
 * <pre>{@code
 * // OLD (deprecated):
 * ProcessTreeInstructionsManager manager = new ProcessTreeInstructionsManager();
 * manager.extractAll(process);
 * Execution op = manager.getOperator(new ProcessTreePositionKey(0));
 *
 * // NEW (recommended):
 * ComputationScopeCompiler<T> compiler = new ComputationScopeCompiler<>(computation, nameProvider);
 * ScopeInstructionsManager<ScopeSignatureExecutionKey> manager =
 *     new ScopeInstructionsManager<>(computeContext, () -> compiler.compile(), null);
 * Execution op = manager.getOperator(new ScopeSignatureExecutionKey(signature));
 * }</pre>
 *
 * @deprecated Use {@link ScopeInstructionsManager} with {@link ScopeSignatureExecutionKey} instead.
 *             This tree-based approach is no longer maintained and will be removed in a future version.
 * @see InstructionSetManager
 * @see ScopeInstructionsManager
 * @see ProcessTreePositionKey
 * @see AcceleratedOperationContainer
 */
@Deprecated
public class ProcessTreeInstructionsManager implements
		ComputableInstructionSetManager<ProcessTreePositionKey>, ConsoleFeatures {
	public static boolean verboseLogs = false;

	private Map<ProcessTreePositionKey, ProcessInstructions> instructions;

	public ProcessTreeInstructionsManager() {
		instructions = new HashMap<>();
	}

	@Override
	public Execution getOperator(ProcessTreePositionKey key) {
		if (verboseLogs)
			log("Retrieving Execution for " + key.describe());
		return instructions.get(key).getOperator();
	}

	@Override
	public int getOutputArgumentIndex(ProcessTreePositionKey key) {
		return instructions.get(key).getOutputArgumentIndex();
	}

	@Override
	public int getOutputOffset(ProcessTreePositionKey key) {
		return instructions.get(key).getOutputOffset();
	}

	public AcceleratedOperation<?> replaceInstructions(ProcessTreePositionKey key,
													   ArgumentList<?> compiled) {
		AcceleratedOperation<?> operation = extract(compiled);

		if (operation instanceof AcceleratedComputationOperation<?> op) {
			if (verboseLogs)
				log("Replacing instructions for " + Describable.describe(op.getComputation()));
			op.compile(this, key);
		}

		return compiled instanceof AcceleratedOperation<?> ? (AcceleratedOperation<?>) compiled : null;
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>> Process<P, T> replaceAll(V process) {
		T compiled = process.get();

		if (compiled instanceof ArgumentList<?>) {
			traverseAll((ArgumentList<?>) compiled, this::replaceInstructions);
		}

		return process;
	}

	protected <T> AcceleratedOperation<?> extract(T compiled) {
		if (compiled instanceof AcceleratedOperation<?> op) {
			return op;
		} else if (compiled instanceof HardwareEvaluable<?> ev) {
			return extract(ev.getKernel().getValue());
		} else {
			return null;
		}
	}

	public AcceleratedOperation<?> extractCompiled(ProcessTreePositionKey key, ArgumentList<?> compiled) {
		AcceleratedOperation<?> operation = extract(compiled);
		if (operation == null) return null;

		InstructionSetManager<?> mgr = operation.getInstructionSetManager();

		if (mgr == null) {
			throw new IllegalArgumentException();
		} else if (!(mgr instanceof ComputableInstructionSetManager)) {
			return operation;
		}

		instructions.put(key,
				new ProcessInstructions(operation.getExecutionKey(),
							(ComputableInstructionSetManager) mgr));
		if (operation instanceof AcceleratedComputationOperation<?> op) {
			if (verboseLogs)
				log("Extracted instructions from " + Describable.describe(op.getComputation()));
			op.compile(this, key);
		} else {
			if (verboseLogs)
				log("Extracted instructions from " + Describable.describe(operation));
		}

		return operation;
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>> Process<P, T> extractAll(V process) {
		T compiled = process.get();

		if (compiled instanceof ArgumentList<?>) {
			traverseAll((ArgumentList<?>) compiled, this::extractCompiled);
		}

		return process;
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>, M extends MemoryData>
			AcceleratedOperationContainer<M> applyContainer(V process) {
		AcceleratedOperation<M> compiled = (AcceleratedOperation) extract(process.get());

		if (compiled == null) {
			if (verboseLogs)
				warn("Cannot create container for " + Describable.describe(process));
			return null;
		}

		AcceleratedOperationContainer<M> container = new AcceleratedOperationContainer<>(compiled);

		traverseAll(compiled, (key, op) -> {
			if (op instanceof AcceleratedOperation<?> o) {
				o.setEvaluator(container);
			}
		});

		return container;
	}

	public void traverseAll(ArgumentList<?> compiled,
							BiConsumer<ProcessTreePositionKey, ArgumentList<?>> consumer) {
		traverseAll(new ProcessTreePositionKey(), compiled, consumer);
	}

	public void traverseAll(ProcessTreePositionKey key, ArgumentList<?> compiled,
							BiConsumer<ProcessTreePositionKey, ArgumentList<?>> consumer) {
		List<ArgumentList<?>> children = children(compiled);
		IntStream.range(0, children.size()).forEach(i -> traverseAll(key.append(i), children.get(i), consumer));
		consumer.accept(key, compiled);
	}

	@Override
	public Console console() {
		return Hardware.console;
	}

	protected List<ArgumentList<?>> children(ArgumentList<?> operation) {
		return operation.getChildren().stream()
				.map(Argument::getProducer)
				.map(Supplier::get)
				.filter(ArgumentList.class::isInstance)
				.map(op -> (ArgumentList<?>) op)
				.collect(Collectors.toUnmodifiableList());
	}

	protected class ProcessInstructions<K extends ExecutionKey> {
		private K key;
		private ComputableInstructionSetManager<K> instructions;

		public ProcessInstructions(K key,
								   ComputableInstructionSetManager<K> instructions) {
			this.key = key;
			this.instructions = instructions;
		}

		public K getKey() {
			return key;
		}

		public ComputableInstructionSetManager<K> getInstructions() {
			return instructions;
		}

		public Execution getOperator() {
			return getInstructions().getOperator(getKey());
		}

		public int getOutputArgumentIndex() {
			return getInstructions().getOutputArgumentIndex(getKey());
		}

		public int getOutputOffset() {
			return getInstructions().getOutputOffset(getKey());
		}
	}
}
