/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.code.KernelIndex;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PassThroughProducer<T extends MemoryData>
		extends ProducerComputationBase<T, T>
		implements ProducerArgumentReference,
		MemoryDataComputation<T>, KernelizedProducer<T>,
		DestinationSupport<T>,
		TraversableExpression<Double>,
		Shape<PassThroughProducer<T>>, KernelIndex,
		ComputerFeatures  {
	private TraversalPolicy shape;
	private int argIndex;
	private int kernelIndex;

	private Supplier<T> destination;

	public PassThroughProducer(TraversalPolicy shape, int argIndex) {
		this();
		this.shape = shape;
		this.argIndex = argIndex;
	}

	public PassThroughProducer(int size, int argIndex) {
		this();
		this.shape = new TraversalPolicy(size).traverse(0);
		this.argIndex = argIndex;
		this.kernelIndex = 0;
	}

	@Deprecated
	public PassThroughProducer(TraversalPolicy shape, int argIndex, int kernelIndex) {
		this();
		this.shape = shape;
		this.argIndex = argIndex;
		this.kernelIndex = kernelIndex;
		System.out.println("WARN: Specifying kernel index before compilation is deprecated");
	}

	@Deprecated
	public PassThroughProducer(int memLength, int argIndex, int kernelIndex) {
		this();
		this.shape = new TraversalPolicy(memLength).traverse(0);
		this.argIndex = argIndex;
		this.kernelIndex = kernelIndex;
		System.out.println("WARN: Specifying kernel index before compilation is deprecated");
	}

	private PassThroughProducer() {
		this.destination = () -> null;
		this.setInputs(Arrays.asList(new MemoryDataDestination(this, null)));
		init();
	}

	protected IntFunction<Variable<Double, ?>> variableForIndex(IntFunction<Expression<Double>> valueFunction) {
		return i -> new Variable(getVariableName(i), true, valueFunction.apply(i), this);
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public TraversalPolicy getShape() { return shape; }

	@Override
	public int getMemLength() { return shape.getSize(); }

	@Override
	public void setDestination(Supplier<T> destination) { this.destination = destination; }

	@Override
	public Supplier<T> getDestination() { return destination; }

	@Override
	public PassThroughProducer<T> reshape(TraversalPolicy shape) {
		if (shape.getTotalSize() != getShape().getTotalSize()) {
			throw new UnsupportedOperationException();
		}

		return new PassThroughProducer<>(shape, argIndex);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		map.add(this);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		// TODO  This causes cascading issues, as the output variable is reused by the referring
		// TODO  producer and then multiple arguments are sorted to be "first"
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);

		List<Argument<? extends T>> args = new ArrayList<>();
		args.add(new Argument<>(manager.argumentForInput(this).apply((Supplier) this), Expectation.NOT_ALTERED));
		setArguments(args);
	}

	@Override
	public Scope<T> getScope() {
		Scope<T> scope = super.getScope();
		IntStream.range(0, getMemLength())
				.mapToObj(getKernelAssignmentFunction(getOutputVariable()))
				.forEach(v -> scope.getVariables().add((Variable) v));
		return scope;
	}

	/**
	 * This overrides the parent method to prevent recursion,
	 * since the argument is a reference back to this producer.
	 */
	@Override
	public void postCompile() {
		// Do nothing
	}

	@Override
	public KernelizedEvaluable<T> get() {
		// return compileProducer(this);

		return new KernelizedEvaluable<T>() {
			@Override
			public MemoryBank<T> createKernelDestination(int size) {
				throw new UnsupportedOperationException();
			}

			@Override
			public T evaluate(Object... args) {
				return (T) args[argIndex];
			}
		};
	}

	/**
	 * To avoid infinite regress (since pass through has itself as
	 * an argument), this method does nothing.
	 */
	@Override
	public void compact() {
		// Avoid recursion, do not compact children
	}

	/**
	 * Since the normal {@link #getArgument(int)} method returns
	 * the {@link ArrayVariable} for the specified input index,
	 * and this {@link io.almostrealism.relation.Producer} does
	 * not use inputs in the conventional way, this method returns
	 * the indexed {@link ArrayVariable} directly from the list
	 * of arguments.
	 */
	@Override
	public ArrayVariable getArgument(int index) {
		return getArgumentVariables().get(index);
	}


	@Deprecated
	public Expression<Double> getValue(int pos) { return getValueFunction().apply(pos); }

	@Deprecated
	public IntFunction<Expression<Double>> getValueFunction() {
		// return pos -> new Expression<>(Double.class, getArgumentValueName(0, pos, kernelIndex), Collections.emptyList(), getArgument(0));
		return pos -> getArgument(0).valueAt(pos);
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(shape.index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return getArgument(0).get(index);
	}

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }

	@Override
	public int getKernelIndex() { return kernelIndex; }

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
		if (destination instanceof DestinationConsolidationArgumentMap.DestinationThreadLocal) {
			((DestinationConsolidationArgumentMap.DestinationThreadLocal) destination).destroy();
		}
	}
}
