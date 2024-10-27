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
import io.almostrealism.relation.Process;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArgumentList;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ProcessTreeInstructionsManager implements
		ComputableInstructionSetManager<ProcessTreePositionKey>, ConsoleFeatures {
	private Map<ProcessTreePositionKey, ProcessInstructions> instructions;

	public ProcessTreeInstructionsManager() {
		instructions = new HashMap<>();
	}

	@Override
	public Execution getOperator(ProcessTreePositionKey key) {
		// log("Retrieving Execution for " + key.describe());
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
			// log("Replacing instructions for " + Describable.describe(op.getComputation()));
			op.compile(this, key);
		}

		return compiled instanceof AcceleratedOperation<?> ? (AcceleratedOperation<?>) compiled : null;
	}

	public AcceleratedOperation<?> replaceAll(ProcessTreePositionKey key, ArgumentList<?> compiled) {
		List<ArgumentList<?>> children = children(compiled);
		IntStream.range(0, children.size()).forEach(i -> replaceAll(key.append(i), children.get(i)));
		return replaceInstructions(key, compiled);
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>> Process<P, T> replaceAll(V process) {
		T compiled = process.get();

		if (compiled instanceof ArgumentList<?>) {
			replaceAll(new ProcessTreePositionKey(), (ArgumentList<?>) compiled);
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
			log("Extracted instructions from " + Describable.describe(op.getComputation()));
			op.compile(this, key);
		} else {
			log("Extracted instructions from " + Describable.describe(operation));
		}

		return operation;
	}

	public AcceleratedOperation<?> extractAll(ProcessTreePositionKey key, ArgumentList<?> compiled) {
		List<ArgumentList<?>> children = children(compiled);
		IntStream.range(0, children.size()).forEach(i -> extractAll(key.append(i), children.get(i)));
		return extractCompiled(key, compiled);
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>> Process<P, T> extractAll(V process) {
		T compiled = process.get();

		if (compiled instanceof ArgumentList<?>) {
			extractAll(new ProcessTreePositionKey(), (ArgumentList<?>) compiled);
		}

		return process;
	}

	@Override
	public Console console() {
		return Hardware.console;
	}

	protected List<ArgumentList<?>> children(ArgumentList<?> operation) {
		return operation.getChildren().stream()
				.map(Argument::getProducer)
				.map(ProducerCache::getEvaluableForSupplier)
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
