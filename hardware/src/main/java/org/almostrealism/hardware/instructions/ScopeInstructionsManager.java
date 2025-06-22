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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ScopeInstructionsManager<K extends ExecutionKey>
		extends AbstractInstructionSetManager<K>
		implements ComputableInstructionSetManager<K>, ConsoleFeatures {

	private Supplier<Scope<?>> scope;
	private Consumer<ScopeInstructionsManager<K>> accessListener;
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
	}

	@Override
	public Console console() { return Hardware.console; }
}
