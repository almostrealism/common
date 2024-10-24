/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.Computer;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import org.almostrealism.hardware.instructions.ProcessTreeInstructionsManager;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;

public class DefaultComputer implements Computer<MemoryData>, ConsoleFeatures {
	private Hardware hardware;

	private ThreadLocal<Stack<List<ComputeRequirement>>> requirements;

	private Map<String, ProcessTreeInstructionsManager> instructionsCache;

	public DefaultComputer(Hardware hardware) {
		this.hardware = hardware;
		this.requirements = ThreadLocal.withInitial(Stack::new);
		this.instructionsCache = new HashMap<>();
	}

	@Override
	public ComputeContext<MemoryData> getContext(Computation<?> c) {
		long count = Countable.countLong(c);
		boolean fixed = Countable.isFixedCount(c);
		boolean sequential = fixed && count == 1;
		boolean accelerator = !fixed || count > 128;
		List<ComputeContext<MemoryData>> contexts = hardware
				.getComputeContexts(sequential, accelerator,
					getActiveRequirements().toArray(ComputeRequirement[]::new));
		if (contexts.isEmpty()) throw new RuntimeException("No compute contexts available");
		if (contexts.size() == 1) return contexts.get(0);

		if (!fixed || count > 1) {
			return contexts.stream()
					.filter(cc -> !cc.isCPU())
					.findFirst()
					.orElse(contexts.get(0));
		} else {
			return contexts.stream()
					.filter(cc -> cc.isCPU())
					.findFirst()
					.orElse(contexts.get(0));
		}
	}

	public List<ComputeRequirement> getActiveRequirements() {
		return requirements.get().isEmpty() ? Collections.emptyList() : requirements.get().peek();
	}

	public void pushRequirements(List<ComputeRequirement> requirements) {
		this.requirements.get().push(requirements);
	}

	public void popRequirements() {
		this.requirements.get().pop();
	}

	public ProcessTreeInstructionsManager getInstructionsManager(String key) {
		return instructionsCache.computeIfAbsent(key, k -> new ProcessTreeInstructionsManager());
	}

	public <P extends Process<?, ?>, T, V extends io.almostrealism.relation.Process<P, T>> Process<P, T>
			applyInstructionsManager(String key, V process) {
		if (instructionsCache.containsKey(key)) {
			return getInstructionsManager(key).replaceAll(process);
		} else {
			return getInstructionsManager(key).extractAll(process);
		}
	}

	@Override
	public Runnable compileRunnable(Computation<Void> c) {
		return Heap.addCompiled(new AcceleratedComputationOperation<>(getContext(c), c, true));
	}

	@Override
	public Runnable compileRunnable(Computation<Void> c, boolean kernel) {
		return new AcceleratedComputationOperation<>(getContext(c), c, kernel);
	}

	// TODO  The Computation may have a postProcessOutput method that will not be called
	// TODO  when using this method of creating an Evaluable from it. Ideally, that feature
	// TODO  of the Computation would be recognized, and applied after evaluation, so that
	// TODO  the correct type is returned.
	@Override
	public <T extends MemoryData> Evaluable<T> compileProducer(Computation<T> c) {
		return new AcceleratedComputationEvaluable<>(getContext(c), c);
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Runnable r) {
		if (r instanceof AcceleratedComputationOperation) {
			return Optional.of(((AcceleratedComputationOperation) r).getComputation());
		} else {
			return Optional.empty();
		}
	}

	@Override
	public <T> Optional<Computation<T>> decompile(Evaluable<T> p) {
		if (p instanceof AcceleratedComputationEvaluable) {
			return Optional.of(((AcceleratedComputationEvaluable) p).getComputation());
		} else {
			return Optional.empty();
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
