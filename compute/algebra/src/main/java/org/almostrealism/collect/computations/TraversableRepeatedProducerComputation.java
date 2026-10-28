/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A {@link ConstantRepeatedProducerComputation} that is also a {@link TraversableExpression},
 * so that its value at any position can be embedded in the expression of a consumer rather
 * than always computed in a kernel of its own.
 *
 * <p>A subclass defines the computation through two paths that must agree:</p>
 * <ul>
 *   <li>{@link #getValueAt(Expression)}, the value of one output element, with every
 *       iteration unrolled into the expression. This is the path used when the
 *       computation is embedded in the kernel of a consumer, where the kernel index
 *       identifies an element of that kernel rather than of this computation, so the
 *       element must be located by the index given.</li>
 *   <li>{@link #getExpression(TraversableExpression[], Expression, Expression)}, one
 *       iteration of the loop, used when the computation is compiled into a kernel of its
 *       own, where the accumulated value of each output element is held in the output.</li>
 * </ul>
 *
 * <p>Whether the computation is embedded or isolated depends on its iteration count (see
 * {@link #isolationCountThreshold}) and on the size of its output.</p>
 *
 * <p>This class is abstract so that the behaviour of every instance is fixed by its class,
 * which, together with its name and inputs, is what its signature identifies.</p>
 *
 * @see IndexOfMaxComputation
 * @see AggregatedProducerComputation
 * @see PrefixAccumulationComputation
 *
 * @author Michael Murray
 */
public abstract class TraversableRepeatedProducerComputation
		extends ConstantRepeatedProducerComputation implements TraversableExpression<Double> {

	/**
	 * The threshold for the number of iterations above which this computation
	 * becomes a candidate for isolation during execution.
	 *
	 * <p>When the iteration count exceeds this threshold, the computation may be
	 * isolated into a separate kernel or execution context for better performance
	 * and memory management. This helps prevent excessive resource consumption
	 * from computations with very high iteration counts.</p>
	 *
	 * <p>The default value of 16 provides a balance between overhead of isolation
	 * and benefits of separate execution. Set to {@link Integer#MAX_VALUE} to
	 * effectively disable isolation based on iteration count.</p>
	 */
	public static int isolationCountThreshold = 16; // Integer.MAX_VALUE;

	/**
	 * Constructs a repeated computation with a fixed number of iterations.
	 *
	 * @param name      the name of the operation, which participates in its signature
	 * @param shape     the {@link TraversalPolicy} of the output
	 * @param count     the number of iterations, which may trigger isolation as
	 *                  {@link #isolationCountThreshold} describes
	 * @param arguments the inputs of the computation
	 */
	@SafeVarargs
	protected TraversableRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
													 Producer<PackedCollection>... arguments) {
		super(name, shape, count, arguments);
		this.count = count;
	}

	/**
	 * Retrieves the computed value at the specified multi-dimensional position, by
	 * converting the coordinates into a linear index with the output shape and
	 * delegating to {@link #getValueAt(Expression)}.
	 *
	 * @param pos the coordinates of the position, one for each dimension of the output
	 * @return an {@link Expression} for the value at the position
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	/**
	 * Returns the value of the output element at the given index, with every iteration
	 * unrolled into the expression, for use when this computation is embedded in the
	 * expression of a consumer.
	 *
	 * @param index the index of the output element
	 * @return an {@link Expression} for the value of that element after every iteration
	 */
	@Override
	public abstract Expression<Double> getValueAt(Expression index);

	/**
	 * Returns the expression for one iteration of the loop, for use when this computation
	 * is compiled into a kernel of its own.
	 *
	 * @param args        the traversable arguments, where {@code args[0]} is the output
	 * @param globalIndex the global index in the overall computation space
	 * @param localIndex  the index of the iteration
	 * @return an {@link Expression} for the next value of the output element
	 */
	@Override
	protected abstract Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex);

	/**
	 * Determines whether this computation should be isolated: never when its output exceeds
	 * {@link io.almostrealism.code.MemoryProvider#MAX_RESERVATION}, and otherwise when the
	 * parent class requires it or the iteration count exceeds {@link #isolationCountThreshold}.
	 *
	 * @param context the {@link ProcessContext} in which the computation will execute
	 * @return {@code true} if this computation should be isolated
	 *
	 * @see ConstantRepeatedProducerComputation#isIsolationTarget(ProcessContext)
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		if (getOutputSize() > MemoryProvider.MAX_RESERVATION) return false;
		return super.isIsolationTarget(context) || count > isolationCountThreshold;
	}

	/**
	 * Creates a new instance of this computation for the given children, where the first
	 * child is the output destination and the remaining children are the inputs.
	 *
	 * @param children the child processes of the new computation
	 * @return a new computation of the same class with those inputs
	 */
	@Override
	public abstract TraversableRepeatedProducerComputation generate(List<Process<?, ?>> children);
}
