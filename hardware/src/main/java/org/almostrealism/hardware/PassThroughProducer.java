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

package org.almostrealism.hardware;

import io.almostrealism.collect.Algebraic;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class PassThroughProducer<T extends MemoryData> extends ProducerComputationBase<T, T>
		implements ProducerArgumentReference, MemoryDataComputation<T>,
					CollectionExpression<PassThroughProducer<T>>,
					DescribableParent<Process<?, ?>> {

	private TraversalPolicy shape;
	private int argIndex;
	private boolean absolute;

	public PassThroughProducer(TraversalPolicy shape, int argIndex) {
		this(shape, argIndex, !ScopeSettings.enableRelativePassThrough);
	}

	public PassThroughProducer(TraversalPolicy shape, int argIndex, boolean absolute) {
		this();
		this.shape = shape;
		this.argIndex = argIndex;
		this.absolute = absolute;
		init();
	}

	@Deprecated
	public PassThroughProducer(int size, int argIndex) {
		this();
		this.shape = new TraversalPolicy(size).traverse(0);
		this.argIndex = argIndex;
		this.absolute = false;
		init();
	}

	private PassThroughProducer() {
		this.setInputs(Arrays.asList(new MemoryDataDestinationProducer(this, null, false)));
	}

	@Override
	public TraversalPolicy getShape() { return shape; }

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }

	@Override
	public int getMemLength() { return getShape().getSize(); }

	@Override
	public long getCountLong() { return getShape().getCountLong(); }

	@Override
	public boolean isFixedCount() {
		if (absolute) {
			return getShape().isFixedCount();
		}

		return false;
	}

	@Override
	public PassThroughProducer<T> traverse(int axis) {
		return reshape(getShape().traverse(axis));
	}

	@Override
	public PassThroughProducer<T> reshape(TraversalPolicy shape) {
		if (shape.getTotalSize() != getShape().getTotalSize()) {
			throw new UnsupportedOperationException();
		}

		return new PassThroughProducer<>(shape, argIndex, absolute);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		map.add(this);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		List<Argument<? extends T>> args = new ArrayList<>();
		args.add(new Argument<>(manager.argumentForInput(getNameProvider()).apply((Supplier) this), Expectation.NOT_ALTERED));
		setArguments(args);
	}

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Evaluable<T> get() {
		return args -> (T) args[argIndex];
	}

	/**
	 * Since the normal {@link #getArgument(int)}
	 * method returns the {@link ArrayVariable} for the specified input
	 * index, and this {@link io.almostrealism.relation.Producer} does
	 * not use inputs in the conventional way, this method returns
	 * the indexed {@link ArrayVariable} directly from the list
	 * of arguments.
	 */
	@Override
	public ArrayVariable getArgument(int index) {
		return getArgumentVariables().get(index);
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (absolute) {
			return (Expression) getArgumentVariables().get(0).referenceAbsolute(index);
		} else if (ScopeSettings.enableDynamicReferences) {
			return (Expression) getArgumentVariables().get(0).referenceDynamic(index);
		} else {
			return (Expression) getArgumentVariables().get(0).referenceRelative(index);
		}
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return (Expression) getArgumentVariables().get(0).referenceRelative(index);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
	}

	@Override
	public PassThroughProducer<T> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public <A extends Algebraic> boolean matches(A other) {
		if ((other instanceof ProducerArgumentReference)) {
			if (!(other instanceof PassThroughProducer)) {
				// This should not be an issue, but it is something that might be
				// worth knowing if there is ever a future system which matches
				// across different types of the argument references
				warn(other.getClass().getSimpleName() + " is not a PassThroughProducer");
			}

			return ((ProducerArgumentReference) other).getReferencedArgumentIndex() == getReferencedArgumentIndex();
		}

		return false;
	}

	@Override
	public String signature() {
		return "param(" + getReferencedArgumentIndex() + "{" + getShape().toStringDetail() + "})";
	}

	@Override
	public int hashCode() {
		return getReferencedArgumentIndex();
	}

	@Override
	public boolean equals(Object obj) {
		if (!Objects.equals(getClass(), obj.getClass()) || !(obj instanceof Algebraic)) {
			return false;
		}

		return matches((Algebraic) obj);
	}

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
	}

	@Override
	public String describe() {
		return getMetadata().getShortDescription() + " " +
				getShape().toStringDetail() +
				(isFixedCount() ? " (fixed)" : " (variable)");
	}

	@Override
	public String description(List<String> children) {
		if (argIndex == 0) {
			return "x";
		} else if (argIndex == 1) {
			return "y";
		} else if (argIndex == 2) {
			return "z";
		} else {
			return "input" + argIndex;
		}
	}
}
