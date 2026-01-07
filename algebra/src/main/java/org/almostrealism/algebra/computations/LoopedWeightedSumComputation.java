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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.AggregatedProducerComputation;

import java.util.List;

/**
 * A computation that performs a weighted sum with a loop over one dimension
 * (outer loop, using native Repeated scope) and an unrolled weighted sum
 * over another dimension (inner sum, unrolled expressions).
 *
 * <p>This is designed for operations like ConvTranspose1d where we need to sum
 * over inputChannels * kernelSize elements. Instead of unrolling all 32K operations,
 * we loop over inputChannels (native loop) and unroll only kernelSize elements
 * (small expression tree).</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For each output element, computes:</p>
 * <pre>
 * output[o] = sum(outer=0..outerCount-1) sum(inner=0..innerCount-1)
 *             input[outer, o+inner] * weights[outer, inner]
 * </pre>
 *
 * <p>The outer sum becomes a native for-loop, and the inner sum is unrolled
 * into a small expression tree.</p>
 *
 * <h2>Generated Code Structure</h2>
 * <pre>
 * double result = 0.0;
 * for (int ic = 0; ic &lt; outerCount; ic++) {
 *     // Unrolled inner sum (small)
 *     double partial = input[...] * weight[...];  // k=0
 *     partial += input[...] * weight[...];        // k=1
 *     ...
 *     partial += input[...] * weight[...];        // k=innerCount-1
 *     result += partial;
 * }
 * </pre>
 *
 * @author Michael Murray
 */
public class LoopedWeightedSumComputation extends AggregatedProducerComputation {

	private final int innerCount;
	private final TraversalPolicy inputShape;
	private final TraversalPolicy weightShape;
	private final InputIndexer inputIndexer;
	private final WeightIndexer weightIndexer;

	/**
	 * Functional interface for computing input array indices.
	 */
	@FunctionalInterface
	public interface InputIndexer {
		/**
		 * Computes the linear index into the input array.
		 *
		 * @param outputIndex the current output element index (global)
		 * @param outerIndex the outer loop index (e.g., inputChannel)
		 * @param innerIndex the inner loop index (e.g., kernel position)
		 * @return the linear index into the input array
		 */
		Expression<?> index(Expression<?> outputIndex, Expression<?> outerIndex, Expression<?> innerIndex);
	}

	/**
	 * Functional interface for computing weight array indices.
	 */
	@FunctionalInterface
	public interface WeightIndexer {
		/**
		 * Computes the linear index into the weight array.
		 *
		 * @param outputIndex the current output element index (global)
		 * @param outerIndex the outer loop index (e.g., inputChannel)
		 * @param innerIndex the inner loop index (e.g., kernel position)
		 * @return the linear index into the weight array
		 */
		Expression<?> index(Expression<?> outputIndex, Expression<?> outerIndex, Expression<?> innerIndex);
	}

	/**
	 * Creates a new LoopedWeightedSumComputation.
	 *
	 * @param name the computation name
	 * @param outputShape the shape of the output
	 * @param outerCount the number of iterations in the outer loop (becomes native loop)
	 * @param innerCount the number of elements in the inner sum (becomes unrolled)
	 * @param inputShape the shape of the input tensor
	 * @param weightShape the shape of the weight tensor
	 * @param inputIndexer function to compute input indices
	 * @param weightIndexer function to compute weight indices
	 * @param input the input producer
	 * @param weights the weights producer
	 */
	public LoopedWeightedSumComputation(String name,
										TraversalPolicy outputShape,
										int outerCount,
										int innerCount,
										TraversalPolicy inputShape,
										TraversalPolicy weightShape,
										InputIndexer inputIndexer,
										WeightIndexer weightIndexer,
										Producer<PackedCollection> input,
										Producer<PackedCollection> weights) {
		super(name, outputShape, outerCount,
				(args, index) -> new DoubleConstant(0.0),
				(accumulator, element) -> accumulator.add(element),
				input, weights);
		this.innerCount = innerCount;
		this.inputShape = inputShape;
		this.weightShape = weightShape;
		this.inputIndexer = inputIndexer;
		this.weightIndexer = weightIndexer;
	}

	/**
	 * Computes the inner weighted sum for a given output index and outer loop index.
	 * This method is shared by both getValueAt() and getExpression().
	 *
	 * @param args the traversable arguments
	 * @param outputIndex the output element index
	 * @param outerIndex the outer loop index
	 * @return expression computing the inner weighted sum
	 */
	private Expression<?> computeInnerSum(TraversableExpression[] args, Expression<?> outputIndex, Expression<?> outerIndex) {
		Expression<?> innerSum = new DoubleConstant(0.0);
		for (int i = 0; i < innerCount; i++) {
			Expression<Integer> innerIdx = new IntegerConstant(i);
			Expression<?> inputIdx = inputIndexer.index(outputIndex, outerIndex, innerIdx);
			Expression<?> weightIdx = weightIndexer.index(outputIndex, outerIndex, innerIdx);

			Expression<?> inputVal = args[1].getValueAt(inputIdx);
			Expression<?> weightVal = args[2].getValueAt(weightIdx);

			innerSum = innerSum.add(inputVal.multiply(weightVal));
			innerSum = innerSum.generate(innerSum.flatten());
		}
		return innerSum;
	}

	/**
	 * Computes the value at a given index when embedded in another computation.
	 *
	 * <p>This override ensures that even when the computation is embedded (e.g., via reshape),
	 * the inner loop over kernelSize is unrolled but the outer loop over inputChannels
	 * is ALSO unrolled (since we can't generate native loops in this context).
	 * However, this still produces outerCount * innerCount operations.</p>
	 *
	 * <p>For truly efficient computation with native loops, the computation must be
	 * isolated so that getScope() is called instead.</p>
	 *
	 * @param index the output index
	 * @return expression computing the weighted sum at this index
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		// TODO: The framework needs "inline isolation" support. When a wrapper's child
		// is an isolation target, the wrapper should reference the child's output buffer
		// instead of calling getValueAt(). Without this, we have to fallback to unrolling.
		// See: ReshapeProducer.getValueAt() which calls getValueAt() on its child.
		TraversableExpression[] args = getTraversableArguments(index);

		// Compute the full sum: iterate over outerCount, each computing innerCount products
		Expression<?> result = new DoubleConstant(0.0);
		for (int outer = 0; outer < count; outer++) {
			Expression<Integer> outerIdx = new IntegerConstant(outer);
			Expression<?> innerSum = computeInnerSum(args, index, outerIdx);
			result = result.add(innerSum);
			result = result.generate(result.flatten());
		}

		return (Expression<Double>) result;
	}

	/**
	 * Computes the expression for one iteration of the outer loop.
	 *
	 * <p>This method generates the inner weighted sum (unrolled over innerCount)
	 * for the current outer loop index. This is called by getScope() when
	 * generating a native loop.</p>
	 *
	 * @param args the traversable arguments [output, input, weights]
	 * @param globalIndex the global output element index
	 * @param localIndex the outer loop index (0 to outerCount-1)
	 * @return expression computing the inner weighted sum for this outer index
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		ArrayVariable<?> out = (ArrayVariable<?>) getOutputVariable();
		Expression k = globalIndex instanceof KernelIndex ? globalIndex : new KernelIndex();
		Expression currentValue = out.reference(k.multiply(out.length()));
		Expression<?> innerSum = computeInnerSum(args, globalIndex, localIndex);
		return currentValue.add(innerSum);
	}

	/**
	 * Always returns true to force isolation of this computation.
	 *
	 * <p>This ensures that getScope() is called (producing a native loop)
	 * rather than getValueAt() (which would unroll all iterations).</p>
	 *
	 * @param context the process context
	 * @return always true
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		// Always isolate to ensure native loop generation via getScope()
		return true;
	}

	@Override
	public LoopedWeightedSumComputation generate(List<Process<?, ?>> children) {
		return new LoopedWeightedSumComputation(
				getName(), getShape(), count, innerCount,
				inputShape, weightShape,
				inputIndexer, weightIndexer,
				(Producer<PackedCollection>) children.get(1),
				(Producer<PackedCollection>) children.get(2));
	}
}
