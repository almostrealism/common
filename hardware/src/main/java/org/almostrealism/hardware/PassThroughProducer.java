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

import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.code.KernelIndex;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PassThroughProducer<T extends MemoryData>
		extends DynamicProducerComputationAdapter<T, T>
		implements ProducerArgumentReference, Shape<PassThroughProducer<T>>, KernelIndex {
	private TraversalPolicy shape;
	private int argIndex;
	private int kernelIndex;

	public PassThroughProducer(TraversalPolicy shape, int argIndex) {
		super(shape.getSize(), null, null);
		this.shape = shape;
		this.argIndex = argIndex;
	}

	public PassThroughProducer(int size, int argIndex) {
		super(size, null, null);
		this.shape = new TraversalPolicy(size).traverse(0);
		this.argIndex = argIndex;
		this.kernelIndex = 0;
	}

	@Deprecated
	public PassThroughProducer(TraversalPolicy shape, int argIndex, int kernelIndex) {
		super(shape.getSize(), null, null);
		this.shape = shape;
		this.argIndex = argIndex;
		this.kernelIndex = kernelIndex;
		System.out.println("WARN: Specifying kernel index before compilation is deprecated");
	}

	@Deprecated
	public PassThroughProducer(int memLength, int argIndex, int kernelIndex) {
		super(memLength, null, null);
		this.shape = new TraversalPolicy(memLength).traverse(0);
		this.argIndex = argIndex;
		this.kernelIndex = kernelIndex;
		System.out.println("WARN: Specifying kernel index before compilation is deprecated");
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

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

		List<Argument<? extends T>> args = new ArrayList<>();
		args.add(new Argument<>(manager.argumentForInput(this).apply((Supplier) this), Expectation.NOT_ALTERED));
		setArguments(args);
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

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> new Expression<>(Double.class, getArgumentValueName(0, pos, kernelIndex), getArgument(0));
	}

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }

	@Override
	public int getKernelIndex() { return kernelIndex; }
}
