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
import io.almostrealism.code.NamedFunction;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.OperationMetadata;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class OperationList extends ArrayList<Supplier<Runnable>>
		implements OperationComputation<Void>, ParallelProcess<Process<?, ?>, Runnable>,
					NamedFunction, HardwareFeatures {
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

	public OperationMetadata getMetadata() { return metadata; }

	public void addCompiled(Supplier<Runnable> op) {
		add(() -> op.get());
	}

	@Deprecated
	public <T extends MemoryData> KernelOperation<T> add(Producer<T> producer, MemoryBank destination, MemoryData... arguments) {
		KernelOperation<T> operation = new KernelOperation<>(producer, destination, arguments);
		add(operation);
		return operation;
	}

	@Override
	public int getCount() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Runnable get() {
		if (isFunctionallyEmpty()) return () -> { };

		if (isComputation()) {
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
			return new Runner(getMetadata(), run);
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

		if (getDepth() > abortableDepth) {
			stream().flatMap(c -> Stream.of(c, abort))
					.map(o -> ((Computation) o).getScope()).forEach(scope::add);
		} else {
			stream().map(o -> ((Computation) o).getScope()).forEach(scope::add);
		}

		return scope;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return stream()
				.map(o -> o instanceof Process ? (Process<?, ?>) o : null)
				.filter(Objects::nonNull)
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

	public static class Runner implements Runnable {
		private OperationMetadata metadata;
		private List<Runnable> run;

		public Runner(OperationMetadata metadata, List<Runnable> run) {
			this.metadata = metadata;
			this.run = run;
		}

		public OperationMetadata getMetadata() { return metadata; }

		public List<Runnable> getOperations() { return run; }

		@Override
		public void run() {
			for (int i = 0; i < run.size(); i++) {
				run.get(i).run();
			}
		}
	}
}
