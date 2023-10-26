/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.NamedFunction;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.OperationProfile;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.Computation;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.relation.Compactable;
import org.almostrealism.hardware.computations.Abort;
import org.almostrealism.hardware.computations.Assignment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class OperationList extends ArrayList<Supplier<Runnable>>
		implements OperationComputation<Void>, ParallelProcess<Process<?, ?>, Runnable>,
					NamedFunction, OperationInfo, HardwareFeatures {
	public static boolean enableAutomaticOptimization = false;
	public static boolean enableSegmenting = false;

	private static ThreadLocal<MemoryData> abortFlag;
	private static boolean abortArgs, abortScope;
	private static Abort abort;

	static {
		abortFlag = new ThreadLocal<>();
	}

	private static int maxDepth = 500;
	private static int abortableDepth = 1000;
	private static long functionCount = 0;

	private boolean enableCompilation;
	private String functionName;

	private OperationMetadata metadata;
	private List<ComputeRequirement> requirements;

	public OperationList() { this(null); }

	public OperationList(String description) { this(description, true); }

	public OperationList(String description, boolean enableCompilation) {
		this.enableCompilation = enableCompilation;
		this.functionName = "operations_" + functionCount++;
		this.metadata = new OperationMetadata(functionName, description);
	}

	@Override
	public void setFunctionName(String name) { this.functionName = name; }

	@Override
	public String getFunctionName() { return this.functionName; }

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	public void setComputeRequirements(List<ComputeRequirement> requirements) { this.requirements = requirements; }

	@Override
	public List<ComputeRequirement> getComputeRequirements() { return requirements; }

	public void addCompiled(Supplier<Runnable> op) {
		add(() -> op.get());
	}

	public <T extends MemoryData> void add(int memLength, Producer<T> producer, Producer<T> destination) {
		add(new Assignment<>(memLength, destination, producer));
	}

	@Deprecated
	public <T extends MemoryData> KernelOperation<T> add(Producer<T> producer, MemoryBank destination, MemoryData... arguments) {
		KernelOperation<T> operation = new KernelOperation<>(producer, destination, arguments);
		add(operation);
		return operation;
	}

	@Override
	public int getCount() {
		if (isEmpty()) return 0;

		if (isUniform() && get(0) instanceof Countable) {
			return ((Countable) get(0)).getCount();
		}

		return 1;
	}

	@Override
	public Runnable get() {
		return get(null);
	}

	public Runnable get(OperationProfile profiles) {
		if (isFunctionallyEmpty()) return () -> { };

		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			if (enableAutomaticOptimization && !isUniform()) {
				return optimize().get();
			} else if (isComputation()) {
				OperationAdapter op = (OperationAdapter) compileRunnable(this);
				op.setFunctionName(functionName);
				op.compile();
				return (Runnable) op;
			} else {
				List<Runnable> run = stream().map(Supplier::get).collect(Collectors.toList());
				run.stream()
						.map(r -> r instanceof OperationAdapter ? (OperationAdapter) r : null)
						.filter(Objects::nonNull)
						.filter(Predicate.not(OperationAdapter::isCompiled))
						.forEach(OperationAdapter::compile);
				return new Runner(getMetadata(), run, getComputeRequirements(), profiles);
			}
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	public boolean isComputation() {
		if (!enableCompilation) return false;
		if (getDepth() > maxDepth) return false;

		int nonComputations = stream().mapToInt(o -> {
			if (o instanceof OperationList) {
				return ((OperationList) o).isComputation() ? 0 : 1;
			} else {
				return o instanceof Computation ? 0 : 1;
			}
		}).sum();

		return nonComputations == 0;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(stream(), map);
		if (abortFlag != null & !abortArgs) {
			if (abort == null) abort = new Abort(abortFlag::get);
			abortArgs = true;
			abort.prepareArguments(map);
		}
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		ScopeLifecycle.prepareScope(stream(), manager);
		if (!abortScope) {
			if (abort == null) abort = new Abort(abortFlag::get);
			abortScope = true;
			abort.prepareScope(manager);
		}
	}

	@Override
	public Scope<Void> getScope() {
		if (!isComputation()) {
			throw new IllegalArgumentException("OperationList cannot be compiled to a Scope unless all embedded Operations are Computations");
		}

		Scope scope = new Scope(functionName, getMetadata());
		scope.setComputeRequirements(getComputeRequirements());

		if (getDepth() > abortableDepth) {
			stream().flatMap(c -> Stream.of(c, abort))
					.map(o -> ((Computation) o).getScope()).forEach(scope::add);
		} else {
			stream().map(o -> ((Computation) o).getScope()).forEach(scope::add);
		}

		return scope;
	}

	@Override
	public OperationList generate(List<Process<?, ?>> children) {
		OperationList list = new OperationList();
		list.metadata = metadata;
		list.enableCompilation = enableCompilation;
		list.setComputeRequirements(getComputeRequirements());
		children.forEach(c -> list.add((Supplier) c));
		return list;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return stream()
				.map(o -> o instanceof Process ? (Process<?, ?>) o : Process.of(o))
				.collect(Collectors.toList());
	}

	public boolean isFunctionallyEmpty() {
		if (isEmpty()) return true;
		return stream().noneMatch(o -> !(o instanceof OperationList) || !((OperationList) o).isFunctionallyEmpty());
	}

	public int getDepth() {
		if (isFunctionallyEmpty()) return 0;

		return stream().map(c -> c instanceof OperationList ? (OperationList) c : null).filter(Objects::nonNull)
				.mapToInt(OperationList::getDepth).max().orElse(0) + 1;
	}

	public OperationList flatten() {
		OperationList flat = stream()
				.flatMap(o -> {
					if (o instanceof OperationList) {
						OperationList op = ((OperationList) o).flatten();

						if (op.getComputeRequirements() == null) {
							return op.stream();
						} else {
							return Stream.of(op);
						}
					} else {
						return Stream.of(o);
					}
				})
				.collect(OperationList.collector());
		flat.setComputeRequirements(getComputeRequirements());
		return flat;
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> optimize() {
		if (!enableSegmenting || size() <= 1 || isUniform()) return ParallelProcess.super.optimize();

		boolean match = IntStream.range(1, size()).anyMatch(i -> ParallelProcess.count(get(i - 1)) == ParallelProcess.count(get(i)));
		if (!match) return ParallelProcess.super.optimize();

		OperationList op = new OperationList();
		OperationList current = new OperationList();
		int currentCount = -1;

		for (int i = 0; i < size(); i++) {
			Supplier<Runnable> o = get(i);
			int count = ParallelProcess.count(o);

			if (currentCount == -1 || currentCount == count) {
				current.add(o);
			} else {
				op.add(current.size() == 1 ? current.get(0) : current);
				current = new OperationList();
				current.add(o);
			}

			currentCount = count;
		}

		if (current.size() > 0) op.add(current.size() == 1 ? current.get(0) : current);

		return op.optimize();
	}

	@Override
	public void compact() {
		stream().map(o -> o instanceof Compactable ? (Compactable) o : null)
				.filter(Objects::nonNull).forEach(Compactable::compact);
	}

	public void destroy() {
		stream().map(o -> o instanceof OperationAdapter ? (OperationAdapter) o : null)
				.filter(Objects::nonNull)
				.forEach(OperationAdapter::destroy);
		stream().map(o -> o instanceof OperationList ? (OperationList) o : null)
				.filter(Objects::nonNull)
				.forEach(OperationList::destroy);
	}

	public static Collector<Supplier<Runnable>, ?, OperationList> collector() {
		return Collectors.toCollection(OperationList::new);
	}

	public static void setAbortFlag(MemoryData flag) { abortFlag.set(flag); }

	public static MemoryData getAbortFlag() { return abortFlag.get(); }

	public static void removeAbortFlag() { abortFlag.remove(); }

	protected static void setMaxDepth(int depth) { maxDepth = depth; }

	protected static void setAbortableDepth(int depth) { abortableDepth = depth; }

	public static class Runner implements Runnable, OperationInfo {
		private OperationMetadata metadata;
		private List<Runnable> run;
		private List<ComputeRequirement> requirements;
		private OperationProfile profiles;

		public Runner(OperationMetadata metadata, List<Runnable> run,
					  List<ComputeRequirement> requirements, OperationProfile profiles) {
			this.metadata = metadata;
			this.run = run;
			this.requirements = requirements;
			this.profiles = profiles;
		}

		@Override
		public OperationMetadata getMetadata() { return metadata; }

		public List<Runnable> getOperations() { return run; }

		@Override
		public void run() {
			try {
				if (requirements != null) {
					Hardware.getLocalHardware().getComputer().pushRequirements(requirements);
				}

				if (profiles == null) {
					for (int i = 0; i < run.size(); i++) {
						run.get(i).run();
					}
				} else {
					for (int i = 0; i < run.size(); i++) {
						profiles.recordDuration(run.get(i));
					}
				}
			} finally {
				if (requirements != null) {
					Hardware.getLocalHardware().getComputer().popRequirements();
				}
			}
		}
	}
}
