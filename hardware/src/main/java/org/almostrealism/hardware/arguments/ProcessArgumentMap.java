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
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.instructions.ProcessTreePositionKey;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;
import org.almostrealism.hardware.mem.RootDelegateProviderSupplier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

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
		} else {
			// The argument isn't associated with a position,
			// so no substitution should be expected
			producer = argument.getProducer();
		}

		if (producer == null) {
			throw new IllegalArgumentException();
		}

		return ProducerCache.getEvaluableForSupplier(producer);
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
