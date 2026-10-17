/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.MultiTermDeltaStrategy;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that divides one {@link PackedCollection} by another element by element:
 * <pre>
 * result[i] = a[i] / b[i]
 * </pre>
 * where an operand of a single element is broadcast to every position.
 *
 * <p>The quotient is element-wise in both operands, with partial derivatives
 * {@code 1 / b} with respect to the numerator and {@code -a / b²} with respect to the
 * denominator, so its {@link ReduceAxis reduced} delta passes the reduction on to both
 * operands without forming a Jacobian, including when both depend on the target (as in
 * dividing a collection by its own mean).</p>
 *
 * @see CollectionProductComputation
 * @see org.almostrealism.collect.CollectionFeatures#divide(Producer, Producer)
 *
 * @author Michael Murray
 */
public class CollectionQuotientComputation extends TraversableExpressionComputation {

	/**
	 * Constructs a quotient computation.
	 *
	 * @param shape       the shape of the result
	 * @param numerator   the collection to divide
	 * @param denominator the collection to divide by
	 */
	public CollectionQuotientComputation(TraversalPolicy shape,
										 Producer<PackedCollection> numerator,
										 Producer<PackedCollection> denominator) {
		super("divide", shape, MultiTermDeltaStrategy.NONE, numerator, denominator);
	}

	/**
	 * Generates the expression that divides the numerator by the denominator.
	 *
	 * @param args the destination followed by the numerator and the denominator
	 * @return the element-wise quotient
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return quotient(getShape(), args[1], args[2]);
	}

	/**
	 * Generates a new quotient computation of the given children, preserving this
	 * computation's configuration.
	 *
	 * @param children the destination followed by the numerator and the denominator
	 * @return the regenerated quotient
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CollectionQuotientComputation(getShape(),
				(Producer<PackedCollection>) children.get(1),
				(Producer<PackedCollection>) children.get(2))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * The quotient rule: {@code 1 / b} with respect to the numerator and {@code -a / b²}
	 * with respect to the denominator.
	 *
	 * @param index the position of the operand among {@link #getInputs()}
	 * @return the partial derivative with respect to that operand, or null if an operand
	 *         has no shape
	 */
	@Override
	protected CollectionProducer elementwisePartial(int index) {
		List<Producer<PackedCollection>> inputs = getInputs();
		if (!(inputs.get(1) instanceof Shape) || !(inputs.get(2) instanceof Shape)) return null;

		CollectionProducer numerator = c(inputs.get(1));
		CollectionProducer denominator = c(inputs.get(2));

		if (index == 1) {
			return divide(c(1.0), denominator);
		}

		return minus(divide(numerator, multiply(denominator, denominator)));
	}
}
