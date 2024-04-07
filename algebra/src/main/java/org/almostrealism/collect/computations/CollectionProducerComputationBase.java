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

package org.almostrealism.collect.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.CollectionUtils;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class CollectionProducerComputationBase<I extends PackedCollection<?>, O extends PackedCollection<?>>
												extends ProducerComputationBase<I, O>
												implements CollectionProducerComputation<O>, MemoryDataComputation<O>,
														ComputerFeatures {
	public static boolean enableDestinationLogging = false;

	private String name;
	private TraversalPolicy shape;
	private BiFunction<MemoryData, Integer, O> postprocessor;
	private Evaluable<O> shortCircuit;
	private List<ScopeLifecycle> dependentLifecycles;

	protected CollectionProducerComputationBase() {
	}

	@SafeVarargs
	public CollectionProducerComputationBase(String name, TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		this();

		if (outputShape.getTotalSizeLong() <= 0) {
			throw new IllegalArgumentException("Output shape must have a total size greater than 0");
		}

		this.name = name;
		this.shape = outputShape.withOrder(null);
		this.setInputs((Supplier[]) CollectionUtils.include(new Supplier[0], new MemoryDataDestination<>(this, this::adjustDestination), arguments));
		init();
	}

	@Override
	public String getName() {
		return name == null ? super.getName() : name;
	}

	protected List<ArrayVariable<Double>> getInputArguments() {
		return (List) getInputs().stream().map(this::getArgumentForInput).collect(Collectors.toList());
	}

	public CollectionProducerComputationBase<I, O> addDependentLifecycle(ScopeLifecycle lifecycle) {
		if (dependentLifecycles == null) {
			dependentLifecycles = new ArrayList<>();
		}

		dependentLifecycles.add(lifecycle);
		return this;
	}

	public CollectionProducerComputationBase<I, O> addAllDependentLifecycles(Iterable<ScopeLifecycle> lifecycles) {
		lifecycles.forEach(this::addDependentLifecycle);
		return this;
	}

	public List<ScopeLifecycle> getDependentLifecycles() {
		return dependentLifecycles == null ? Collections.emptyList() : dependentLifecycles;
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		if (dependentLifecycles != null) ScopeLifecycle.prepareScope(dependentLifecycles.stream(), manager);

		// Result should always be first
		// TODO  This causes cascading issues, as the output variable is reused by the referring
		// TODO  producer and then multiple arguments are sorted to be "first"
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		if (dependentLifecycles != null) ScopeLifecycle.prepareArguments(dependentLifecycles.stream(), map);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		if (dependentLifecycles != null) ScopeLifecycle.resetArguments(dependentLifecycles.stream());
	}

	protected void setShape(TraversalPolicy shape) {
		this.shape = shape;
	}

	protected TraversalPolicy shapeForLength(int len) {
		TraversalPolicy shape;

		if (isFixedCount()) {
			shape = getShape();
		} else {
			int count = len / getShape().getCount();

			// When kernel length is less than, or identical to the output count, an
			// assumption is made that the intended shape is the original shape.
			// This is a bit of a hack, but it's by far the simplest solution
			// available
			if (count == 0 || len == getShape().getCount()) {
				// It is not necessary to prepend a (usually) unnecessary dimension
				shape = getShape();
			} else {
				shape = getShape().prependDimension(count);
			}

			if (enableDestinationLogging) {
				log("shapeForLength(" + len +
						"): " + shape + "[" + shape.getTraversalAxis() + "]");
			}
		}

		return shape;
	}

	protected MemoryBank<?> adjustDestination(MemoryBank<?> existing, Integer len) {
		if (len == null) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy shape = shapeForLength(len);

		if (!(existing instanceof PackedCollection) || existing.getMem() == null ||
				((PackedCollection) existing).getShape().getTotalSize() < shape.getTotalSize()) {
			if (existing != null) existing.destroy();
			return new PackedCollection<>(shape);
		}

		if (((PackedCollection<?>) existing).getShape().equals(shape))
			return existing;

		return ((PackedCollection) existing).range(shape);
	}

	protected MemoryBank<?> createDestination(int len) {
		return new PackedCollection<>(shapeForLength(len));
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public int getMemLength() {
		return getShape().getSize();
	}

	@Override
	public long getCountLong() {
		return getShape().getCountLong();
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends O>> isolate() {
		if (getShape().getTotalSizeLong() > MemoryProvider.MAX_RESERVATION) {
			warn("Cannot isolate a process with a total size greater than " + MemoryProvider.MAX_RESERVATION);
			return this;
		}

		return new CollectionProducerComputation.IsolatedProcess<>(this);
	}

	public BiFunction<MemoryData, Integer, O> getPostprocessor() {
		return postprocessor;
	}

	public CollectionProducerComputationBase<I, O> setPostprocessor(BiFunction<MemoryData, Integer, O> postprocessor) {
		this.postprocessor = postprocessor;
		return this;
	}

	public Evaluable<O> getShortCircuit() { return shortCircuit; }

	public CollectionProducerComputationBase<I, O> setShortCircuit(Evaluable<O> shortCircuit) {
		this.shortCircuit = shortCircuit;
		return this;
	}

	/**
	 * @return  PhysicalScope#GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	protected TraversableExpression[] getTraversableArguments(Expression<?> index) {
		TraversableExpression vars[] = new TraversableExpression[getInputs().size()];
		for (int i = 0; i < vars.length; i++) {
			vars[i] = CollectionExpression.traverse(getArgumentForInput(getInputs().get(i)),
					size -> index.toInt().divide(e(getMemLength())).multiply(size));
		}
		return vars;
	}

	public CollectionVariable getCollectionArgumentVariable(int argIndex) {
		ArrayVariable<?> arg = getArgumentForInput(getInputs().get(argIndex));

		if (arg instanceof CollectionVariable) {
			return (CollectionVariable) arg;
		} else {
			return null;
		}
	}

	@Override
	public Evaluable<O> get() {
		HardwareEvaluable ev = new HardwareEvaluable<>(() -> CollectionProducerComputation.super.get(), null, shortCircuit, true);
		ev.setDestinationProcessor(destination -> {
			if (destination instanceof Shape) {
				Shape out = (Shape) destination;

				if (getCountLong() > 1 || isFixedCount() || (out.getShape().getCountLong() > 1 && getCountLong() == 1)) {
					for (int axis = out.getShape().getDimensions(); axis >= 0; axis--) {
						if (out.getShape().traverse(axis).getSize() == getShape().getSize()) {
							return axis == out.getShape().getTraversalAxis() ? out : out.traverse(axis);
						}
					}
				}

				if (getShape().getSize() > 1 && ((Shape) destination).getShape().getSize() != getShape().getSize()) {
					throw new IllegalArgumentException();
				}
			}

			return destination;
		});
		return ev;
	}

	@Override
	public O postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? CollectionProducerComputation.super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	public RepeatedProducerComputationAdapter<O> toRepeated() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void destroy() {
		super.destroy();
		((MemoryDataDestination) getInputs().get(0)).destroy();
		ProducerCache.purgeEvaluableCache(this);
	}

	public static Supplier[] validateArgs(Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	public static void destinationLog(Runnable r) {
		boolean log = enableDestinationLogging;

		try {
			enableDestinationLogging = true;
			r.run();
		} finally {
			enableDestinationLogging = log;
		}
	}
}
