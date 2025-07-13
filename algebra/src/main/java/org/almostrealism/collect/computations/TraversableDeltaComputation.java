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
import io.almostrealism.code.ComputationBase;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

// TODO  Should extend TraversableExpressionComputation
public class TraversableDeltaComputation<T extends PackedCollection<?>>
		extends CollectionProducerComputationAdapter<T, T>
		implements HardwareFeatures {
	public static boolean enableOptimization = true;
	public static boolean enableStubOptimization = false;
	public static boolean enableAtomicScope = false;
	public static boolean enableIsolate = false;

	private Function<TraversableExpression[], CollectionExpression> expression;
	private Producer<?> target;
	private CollectionVariable<?> targetVariable;

	@SafeVarargs
	protected TraversableDeltaComputation(String name, TraversalPolicy shape,
										  Function<TraversableExpression[], CollectionExpression> expression,
										  Producer<?> target,
										  Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, validateArgs(args));
		this.expression = expression;
		this.target = target;
		if (target instanceof ScopeLifecycle) addDependentLifecycle((ScopeLifecycle) target);
	}

	@Override
	public int getMemLength() { return enableAtomicScope ? 1 : super.getMemLength(); }

	@Override
	public long getCountLong() {
		return enableAtomicScope ? getShape().traverseEach().getCountLong() : super.getCountLong();
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		targetVariable = (CollectionVariable<?>) manager.argumentForInput(getNameProvider()).apply((Supplier) target);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		targetVariable = null;
	}

	protected CollectionExpression getExpression(Expression index) {
		CollectionExpression exp = expression.apply(getTraversableArguments(index)).delta(targetVariable);
		if (isFixedCount()) exp.setTotalShape(getShape());
		return exp;
	}

	protected boolean permitOptimization(Process<Process<?, ?>, Evaluable<? extends T>> process) {
		if (enableStubOptimization && !(process instanceof TraversableExpression)) {
			// There is no harm in optimizing a process which will not reveal an Expression
			// because there is no information being hidden from the delta Expression due
			// to isolation if there is no Expression in the first place
			return true;
		}

		return !AlgebraFeatures.matchingInputs(this, target).contains(process);
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> optimize(ProcessContext ctx, Process<Process<?, ?>, Evaluable<? extends T>> process) {
		if (!permitOptimization(process))
			return process;
		return super.optimize(ctx, process);
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate(Process<Process<?, ?>, Evaluable<? extends T>> process) {
		if (!permitOptimization(process)) return process;
		return super.isolate(process);
	}

	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return enableIsolate || super.isIsolationTarget(context);
	}

	@Override
	public ComputationBase<T, T, Evaluable<? extends T>> optimize(ProcessContext ctx) {
		if (!enableOptimization) return this;
		return super.optimize(ctx);
	}

	@Override
	public TraversableDeltaComputation<T> generate(List<Process<?, ?>> children) {
		TraversableDeltaComputation<T> result =
				(TraversableDeltaComputation<T>) new TraversableDeltaComputation(getName(), getShape(), expression, target,
					children.stream().skip(1).toArray(Supplier[]::new))
					.setPostprocessor(getPostprocessor()).setShortCircuit(getShortCircuit());
		getDependentLifecycles().forEach(result::addDependentLifecycle);
		return result;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(index).getValueAt(index);
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(new IntegerConstant(0)).getValueRelative(index);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getExpression(targetIndex).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	public Expression uniqueNonZeroIndexRelative(Index localIndex, Expression<?> targetIndex) {
		return getExpression(new IntegerConstant(0)).uniqueNonZeroIndexRelative(localIndex, targetIndex);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		throw new UnsupportedOperationException();
	}

	public static <T extends PackedCollection<?>> TraversableDeltaComputation<T> create(
			String name, TraversalPolicy deltaShape, TraversalPolicy targetShape,
			Function<TraversableExpression[], CollectionExpression> expression,
			Producer<?> target,
			Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		return new TraversableDeltaComputation<>(name, deltaShape.append(targetShape), expression, target, args);
	}
}
