/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.NamedFunction;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ComputableParallelProcess;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.profile.OperationTimingListener;
import io.almostrealism.relation.Countable;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.Computation;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import org.almostrealism.hardware.computations.Abort;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class OperationList extends ArrayList<Supplier<Runnable>>
		implements OperationComputation<Void>,
					ComputableParallelProcess<Process<?, ?>, Runnable>,
					NamedFunction, Destroyable, ComputerFeatures {
	public static boolean enableRunLogging = SystemUtils.isEnabled("AR_HARDWARE_RUN_LOGGING").orElse(false);
	public static boolean enableAutomaticOptimization = false;
	public static boolean enableSegmenting = false;
	public static boolean enableNonUniformCompilation = false;

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
	private String description;
	private Long count;

	private OperationMetadata metadata;
	private OperationProfile profile;
	private List<ComputeRequirement> requirements;

	public OperationList() { this(null); }

	public OperationList(String description) { this(description, true); }

	public OperationList(String description, boolean enableCompilation) {
		this.enableCompilation = enableCompilation;
		this.functionName = "operations_" + functionCount++;
		this.description = description;
	}

	@Override
	public void setFunctionName(String name) { this.functionName = name; }

	@Override
	public String getFunctionName() { return this.functionName; }

	@Override
	public OperationMetadata getMetadata() {
		if (metadata == null) {
			metadata = OperationInfo.metadataForProcess(this, new OperationMetadata(functionName, description));
		}

		return metadata;
	}

	public OperationProfile getProfile() { return profile; }
	public void setProfile(OperationProfile profile) { this.profile = profile; }

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
	public long getCountLong() {
		if (count == null) {
			if (isEmpty()) {
				count = 0L;
			} else if (isUniform() && get(0) instanceof Countable) {
				count = ((Countable) get(0)).getCountLong();
			} else {
				count = 1L;
			}
		}

		return count;
	}

	@Override
	public Runnable get() {
		return get(getProfile());
	}

	public Runnable get(OperationProfile profile) {
		if (isFunctionallyEmpty()) return () -> { };

		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			if (profile instanceof OperationProfileNode) {
				((OperationProfileNode) profile).addChild(getMetadata());
			}

			if (enableAutomaticOptimization && !isUniform()) {
				return optimize().get();
			} else if (isComputation() && (enableNonUniformCompilation || isUniform())) {
				AcceleratedOperation op = (AcceleratedOperation) compileRunnable(this);
				op.setFunctionName(functionName);
				op.load();
				return op;
			} else {
				if (isComputation()) {
					warn("OperationList was not compiled (uniform = " + isUniform() + ")");
				}

				List<Runnable> run = stream().map(Supplier::get).collect(Collectors.toList());
				if (run.size() == 1) {
					return run.get(0);
				}

				return new Runner(getMetadata(), run, getComputeRequirements(),
						profile == null ? null : profile.getTimingListener());
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
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		ScopeLifecycle.prepareScope(stream(), manager, context);
		if (!abortScope) {
			if (abort == null) abort = new Abort(abortFlag::get);
			abortScope = true;
			abort.prepareScope(manager, context);
		}
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		if (!isComputation()) {
			throw new IllegalArgumentException(
					"OperationList cannot be compiled to a Scope " +
					"unless all embedded Operations are Computations");
		}

		Scope<Void> scope = new Scope<>(functionName, getMetadata());
		scope.setComputeRequirements(getComputeRequirements());

		if (getDepth() > abortableDepth) {
			stream().flatMap(c -> Stream.of(c, abort))
					.map(o -> ((Computation) o).getScope(context)).forEach(scope::add);
		} else {
			stream().map(o -> ((Computation) o).getScope(context)).forEach(scope::add);
		}

		return scope;
	}

	@Override
	public OperationList generate(List<Process<?, ?>> children) {
		OperationList list = new OperationList();
		list.enableCompilation = enableCompilation;
		list.setComputeRequirements(getComputeRequirements());
		children.forEach(c -> list.add((Supplier) c));
		return list;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return stream()
				.filter(o -> !(o instanceof OperationList) || !((OperationList) o).isFunctionallyEmpty())
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
		flat.enableCompilation = enableCompilation;
		flat.setComputeRequirements(getComputeRequirements());
		return flat;
	}

	public void run() { get().run(); }

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> optimize(ProcessContext context) {
		if (!enableSegmenting || size() <= 1 || isUniform()) return ComputableParallelProcess.super.optimize(context);

		boolean match = IntStream.range(1, size()).anyMatch(i -> Countable.countLong(get(i - 1)) == Countable.countLong(get(i)));
		if (!match) return ComputableParallelProcess.super.optimize(context);

		OperationList op = new OperationList();
		OperationList current = new OperationList();
		long currentCount = -1;

		for (int i = 0; i < size(); i++) {
			Supplier<Runnable> o = get(i);
			long count = Countable.countLong(o);

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

		return op.optimize(context);
	}

	@Override
	public void destroy() {
		stream().map(o -> o instanceof Destroyable ? (Destroyable) o : null)
				.filter(Objects::nonNull)
				.forEach(Destroyable::destroy);
	}

	@Override
	public Supplier<Runnable> set(int index, Supplier<Runnable> element) {
		count = null;
		metadata = null;
		return super.set(index, element);
	}

	@Override
	public boolean add(Supplier<Runnable> runnableSupplier) {
		count = null;
		metadata = null;
		return super.add(runnableSupplier);
	}

	@Override
	public void add(int index, Supplier<Runnable> element) {
		count = null;
		metadata = null;
		super.add(index, element);
	}

	@Override
	public Supplier<Runnable> remove(int index) {
		count = null;
		metadata = null;
		return super.remove(index);
	}

	@Override
	public boolean remove(Object o) {
		count = null;
		metadata = null;
		return super.remove(o);
	}

	@Override
	public void clear() {
		count = null;
		metadata = null;
		super.clear();
	}

	@Override
	public boolean addAll(Collection<? extends Supplier<Runnable>> c) {
		count = null;
		metadata = null;
		return super.addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends Supplier<Runnable>> c) {
		count = null;
		metadata = null;
		return super.addAll(index, c);
	}

	@Override
	protected void removeRange(int fromIndex, int toIndex) {
		count = null;
		metadata = null;
		super.removeRange(fromIndex, toIndex);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		if (super.removeAll(c)) {
			count = null;
			metadata = null;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		if (super.retainAll(c)) {
			count = null;
			metadata = null;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public String describe() {
		return Optional.ofNullable(getMetadata().getShortDescription()).orElse("") +
				" " + getCount() + "x " +
				(getComputeRequirements() == null ? "" : Arrays.toString(getComputeRequirements().toArray()));
	}

	public static Collector<Supplier<Runnable>, ?, OperationList> collector() {
		return Collectors.toCollection(OperationList::new);
	}

	public static void setAbortFlag(MemoryData flag) { abortFlag.set(flag); }

	public static MemoryData getAbortFlag() { return abortFlag.get(); }

	public static void removeAbortFlag() { abortFlag.remove(); }

	protected static void setMaxDepth(int depth) { maxDepth = depth; }

	protected static void setAbortableDepth(int depth) { abortableDepth = depth; }

	public static class Runner implements Runnable, OperationInfo, ConsoleFeatures {
		private OperationMetadata metadata;
		private List<Runnable> run;
		private List<ComputeRequirement> requirements;
		private OperationTimingListener timingListener;

		public Runner(OperationMetadata metadata, List<Runnable> run,
					  List<ComputeRequirement> requirements,
					  OperationTimingListener timingListener) {
			this.metadata = metadata;
			this.run = run;
			this.requirements = requirements;
			this.timingListener = timingListener;
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

				if (timingListener == null) {
					for (int i = 0; i < run.size(); i++) {
						run.get(i).run();
					}
				} else {
					for (int i = 0; i < run.size(); i++) {
						if (enableRunLogging)
							log("Running " + OperationInfo.display(run.get(i)));
						timingListener.recordDuration(getMetadata(), run.get(i));
					}
				}
			} finally {
				if (requirements != null) {
					Hardware.getLocalHardware().getComputer().popRequirements();
				}
			}
		}

		@Override
		public String describe() {
			return getMetadata().getShortDescription();
		}

		@Override
		public Console console() { return Hardware.console; }
	}
}
