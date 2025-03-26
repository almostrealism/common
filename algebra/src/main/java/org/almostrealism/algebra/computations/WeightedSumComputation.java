package org.almostrealism.algebra.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.SubsetTraversalWeightedSumExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

import java.util.List;
import java.util.function.Supplier;

public class WeightedSumComputation <T extends PackedCollection<?>>
		extends TraversableExpressionComputation<T> {
	private TraversalPolicy resultShape;
	private TraversalPolicy inputPositions, weightPositions;
	private TraversalPolicy inputGroupShape, weightGroupShape;

	private TraversalPolicy inShape, weightShape;

	public WeightedSumComputation(TraversalPolicy resultShape,
								  TraversalPolicy inputPositions,
								  TraversalPolicy weightPositions,
								  TraversalPolicy inputGroupShape,
								  TraversalPolicy weightGroupShape,
								  Supplier<Evaluable<? extends PackedCollection<?>>> input,
								  Supplier<Evaluable<? extends PackedCollection<?>>> weights) {
		super("weightedSum", resultShape.traverseEach(), input, weights);
		this.resultShape = resultShape;
		this.inputPositions = inputPositions;
		this.weightPositions = weightPositions;
		this.inputGroupShape = inputGroupShape;
		this.weightGroupShape = weightGroupShape;
		this.inShape = shape(input);
		this.weightShape = shape(weights);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new SubsetTraversalWeightedSumExpression(
				resultShape,
				inputPositions, weightPositions,
				inShape, weightShape,
				inputGroupShape, weightGroupShape,
				args[1], args[2]);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new WeightedSumComputation<>(resultShape,
				inputPositions, weightPositions,
				inputGroupShape, weightGroupShape,
				(Producer) children.get(1),
				(Producer) children.get(2));
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (!AlgebraFeatures.match(getInputs().get(1), target)) {
			return super.delta(target);
		}

		return super.delta(target);
	}
}
