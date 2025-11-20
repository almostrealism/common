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

package org.almostrealism.hardware.arguments;

import io.almostrealism.code.Computation;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.instructions.ProcessTreePositionKey;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;
import org.almostrealism.hardware.mem.RootDelegateProviderSupplier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * {@link ProcessArgumentEvaluator} that maps {@link ArrayVariable} arguments to {@link Process} tree positions with substitution support.
 *
 * <p>{@link ProcessArgumentMap} maintains bidirectional mappings between {@link ArrayVariable} instances
 * and their positions in a {@link Process} tree hierarchy, enabling:</p>
 * <ul>
 *   <li><strong>Position-based lookup:</strong> Find arguments by tree position</li>
 *   <li><strong>Argument-based lookup:</strong> Find tree position from argument</li>
 *   <li><strong>Dynamic substitution:</strong> Replace producers at specific positions</li>
 *   <li><strong>Process matching:</strong> Identify which Process corresponds to which argument</li>
 * </ul>
 *
 * <h2>Tree Position Mapping</h2>
 *
 * <p>Each node in a {@link Process} tree is identified by a {@link ProcessTreePositionKey}:</p>
 *
 * <pre>{@code
 * // Process tree:
 * //   add (root: [])
 * //   - matmul (position: [0])
 * //   -  - a (position: [0, 0])
 * //   -  - b (position: [0, 1])
 * //   - c (position: [1])
 *
 * Process<?, Matrix> a = constant(matrixA);
 * Process<?, Matrix> b = constant(matrixB);
 * Process<?, Matrix> c = constant(matrixC);
 * Process<?, Matrix> matmul = matmul(a, b);
 * Process<?, Matrix> add = add(matmul, c);
 *
 * // Create map
 * List<ArrayVariable<?>> args = scope.getArguments();
 * ProcessArgumentMap map = new ProcessArgumentMap(add, args);
 *
 * // Lookup by position
 * ArrayVariable<?> matmulArg = map.getArgumentsByPosition().get(new ProcessTreePositionKey(0));
 *
 * // Lookup position by argument
 * ProcessTreePositionKey pos = map.getPositionsForArguments().get(matmulArg);
 * }</pre>
 *
 * <h2>Producer Substitution</h2>
 *
 * <p>Replace producers at specific tree positions for operation reuse:</p>
 *
 * <pre>{@code
 * ProcessArgumentMap map = new ProcessArgumentMap(process, args);
 *
 * // Original: add(matmul(a, b), c)
 * // Substitute position [0] with different matmul
 * Producer<Matrix> newMatmul = matmul(x, y);
 * map.put(new ProcessTreePositionKey(0), newMatmul);
 *
 * // Now resolves to: add(matmul(x, y), c)
 * }</pre>
 *
 * <h2>Batch Substitution</h2>
 *
 * <p>Populate substitutions from an entire {@link Process} tree:</p>
 *
 * <pre>{@code
 * // New process tree with same structure
 * Process<?, Matrix> newTree = add(matmul(x, y), z);
 *
 * // Copy all producers from new tree to map
 * map.putSubstitutions(newTree);
 *
 * // All positions now use producers from newTree
 * }</pre>
 *
 * <h2>Process Matching</h2>
 *
 * <p>The {@code match()} method determines if a {@link Process} corresponds to an argument's producer:</p>
 *
 * <pre>{@code
 * // Direct equality match
 * Process<?, Matrix> process = matmul(a, b);
 * Supplier producer = argumentProducer;
 * boolean matches = ProcessArgumentMap.match(process, producer);
 *
 * // Special handling for:
 * // - RootDelegateProviderSupplier: Compares root delegates
 * // - MemoryDataDestinationProducer: Unwraps delegates
 * }</pre>
 *
 * <h2>Evaluable Resolution</h2>
 *
 * <p>Implements {@link ProcessArgumentEvaluator} to resolve arguments at runtime:</p>
 *
 * <pre>{@code
 * ArrayVariable<Matrix> arg = ...;
 *
 * // With substitution
 * Evaluable<? extends Multiple<Matrix>> eval = map.getEvaluable(arg);
 * // Returns substituted producer if available
 *
 * // Without substitution
 * // Returns original producer from argument
 * }</pre>
 *
 * <h2>Substitution Fallback</h2>
 *
 * <p>Control fallback behavior when substitutions are missing:</p>
 *
 * <pre>{@code
 * // Disable fallback (default): Throws exception if substitution missing
 * ProcessArgumentMap.enableSubstitutionFallback = false;
 *
 * // Enable fallback: Uses original producer if substitution missing
 * ProcessArgumentMap.enableSubstitutionFallback = true;
 * }</pre>
 *
 * <h2>RootDelegateProviderSupplier Handling</h2>
 *
 * <p>Special case for root delegate matching:</p>
 *
 * <pre>{@code
 * // When argument producer is RootDelegateProviderSupplier:
 * // 1. Evaluate substituted producer
 * // 2. Extract MemoryData
 * // 3. Return root delegate as Provider
 *
 * Evaluable ev = producer.get();
 * MemoryData data = (MemoryData) ev.evaluate();
 * return new Provider(data.getRootDelegate());
 * }</pre>
 *
 * <h2>Copy Constructor</h2>
 *
 * <p>Create independent copy with fresh substitution map:</p>
 *
 * <pre>{@code
 * ProcessArgumentMap original = ...;
 * ProcessArgumentMap copy = new ProcessArgumentMap(original);
 *
 * // Copy has same arguments and positions
 * // But independent substitutions map (empty)
 * }</pre>
 *
 * @see ProcessArgumentEvaluator
 * @see ProcessTreePositionKey
 * @see io.almostrealism.compute.Process
 */
public class ProcessArgumentMap implements ProcessArgumentEvaluator {
	public static boolean enableSubstitutionFallback = false;

	private List<ArrayVariable<?>> arguments;
	private Map<ProcessTreePositionKey, ArrayVariable<?>> argumentsByPosition;
	private Map<ArrayVariable<?>, ProcessTreePositionKey> positionsForArguments;
	private Map<ProcessTreePositionKey, Producer> substitutions;

	public ProcessArgumentMap(ProcessArgumentMap existing) {
		this.arguments = new ArrayList<>(existing.getArguments());
		this.argumentsByPosition = new HashMap<>(existing.getArgumentsByPosition());
		this.positionsForArguments = new HashMap<>(existing.getPositionsForArguments());
		this.substitutions = new HashMap<>();
	}

	public ProcessArgumentMap(Process<?, ?> process, List<ArrayVariable<?>> arguments) {
		this.arguments = arguments;
		this.argumentsByPosition = new HashMap<>();
		this.positionsForArguments = new HashMap<>();
		this.substitutions = new HashMap<>();

		addChildren(new ProcessTreePositionKey(), process);
	}

	public List<ArrayVariable<?>> getArguments() { return arguments; }

	public Map<ProcessTreePositionKey, ArrayVariable<?>> getArgumentsByPosition() {
		return argumentsByPosition;
	}

	public Map<ArrayVariable<?>, ProcessTreePositionKey> getPositionsForArguments() {
		return positionsForArguments;
	}

	protected void addChildren(ProcessTreePositionKey key, Process<?, ?> process) {
		ArrayVariable<?> argument = getArgumentForProcess(process);

		if (argument != null) {
			argumentsByPosition.put(key, argument);
			positionsForArguments.put(argument, key);
		}

		List<Process<?, ?>> children = children(process);
		IntStream.range(0, children.size()).forEach(i ->
				addChildren(key.append(i), children.get(i)));
	}

	protected List<Process<?, ?>> children(Process<?, ?> process) {
		return new ArrayList<>(process.getChildren());
	}

	public ArrayVariable<?> getArgumentForProcess(Process<?, ?> process) {
		return arguments.stream()
				.filter(arg -> match(process, arg.getProducer()))
				.findFirst().orElse(null);
	}

	public Supplier<Evaluable<?>> getProducerForPosition(ProcessTreePositionKey key, boolean allowFallback) {
		if (substitutions.containsKey(key)) {
			return substitutions.get(key);
		} else if (allowFallback && argumentsByPosition.containsKey(key)) {
			return (Supplier) argumentsByPosition.get(key).getProducer();
		}

		return null;
	}

	public void put(ProcessTreePositionKey key, Producer producer) {
		substitutions.put(key, producer);
	}

	public void putSubstitutions(Process<?, ?> process) {
		addProducers(new ProcessTreePositionKey(), process);
	}

	protected void addProducers(ProcessTreePositionKey key, Process<?, ?> process) {
		if (process instanceof Producer) {
			put(key, (Producer) process);
		}

		List<Process<?, ?>> children = children(process);
		IntStream.range(0, children.size()).forEach(i ->
				addProducers(key.append(i), children.get(i)));
	}

	@Override
	public <T> Evaluable<? extends Multiple<T>> getEvaluable(ArrayVariable<T> argument) {
		Supplier producer;

		if (positionsForArguments.containsKey(argument)) {
			producer = getProducerForPosition(positionsForArguments.get(argument),
						enableSubstitutionFallback || substitutions.isEmpty());

			Supplier original = argument.getProducer();

			if (original instanceof RootDelegateProviderSupplier) {
				// When matching with a RootDelegateProviderSupplier,
				// the assumption is that the correct substitution is
				// the root delegate of the MemoryData
				Evaluable ev = (Evaluable) producer.get();
				return new Provider(((MemoryData) ev.evaluate()).getRootDelegate());
			}
		} else {
			// The argument isn't associated with a position,
			// so no substitution should be expected
			producer = argument.getProducer();
		}

		if (producer == null) {
			throw new IllegalArgumentException();
		}

		return (Evaluable) producer.get();
	}

	public static boolean match(Supplier<?> process, Supplier<?> argumentProducer) {
		if (argumentProducer instanceof RootDelegateProviderSupplier) {
			if (process instanceof Computation) {
				// A Computation will never produce a Provider
				return false;
			}

			Provider p = ((RootDelegateProviderSupplier) argumentProducer).getDelegate();

			Evaluable<?> ev = (Evaluable) process.get();
			if (!(ev instanceof Provider<?>)) return false;

			MemoryData arg = (MemoryData) p.get();
			MemoryData proc = (MemoryData) ((Provider<?>) ev).get();
			return arg.getMem().equals(proc.getMem());
		}

		if (process instanceof MemoryDataDestinationProducer<?>) {
			process = (Supplier<?>) ((MemoryDataDestinationProducer<?>) process).getDelegate();
		}

		if (argumentProducer instanceof MemoryDataDestinationProducer<?>) {
			argumentProducer = (Supplier<?>) ((MemoryDataDestinationProducer<?>) argumentProducer).getDelegate();
		}

		if (process == null || argumentProducer == null) return false;
		return process.equals(argumentProducer);
	}
}
