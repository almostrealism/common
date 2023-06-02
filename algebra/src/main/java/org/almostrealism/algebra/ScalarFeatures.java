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

package org.almostrealism.algebra;

import io.almostrealism.expression.*;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.ScalarChoice;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.bool.LessThanVector;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.DynamicCollectionProducerComputationAdapter;
import org.almostrealism.collect.computations.DynamicExpressionComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryBank;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface ScalarFeatures extends CollectionFeatures, HardwareFeatures {

	static ExpressionComputation<Scalar> minusOne() { return of(-1.0); }

	static ExpressionComputation<Scalar> of(double value) { return of(new Scalar(value)); }

	static ExpressionComputation<Scalar> of(Scalar value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> HardwareFeatures.ops().expressionForDouble(value.getMem().toArray(value.getOffset() + i, 1)[0])));
		return new ExpressionComputation(comp).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> v(double value) { return value(new Scalar(value)); }

	default ExpressionComputation<Scalar> v(Scalar value) { return value(value); }

	default ExpressionComputation<Scalar> scalar(double value) { return value(new Scalar(value)); }

	default ExpressionComputation<Scalar> scalar(Producer<?> x) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> args.get(1).getValue(i)));
		return new ExpressionComputation<>(comp, (Supplier) x).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalar(DynamicCollectionProducerComputationAdapter<?, ?> value) {
		if (value instanceof ExpressionComputation) {
			int size = ((ExpressionComputation) value).expression().size();

			if (size == 1) {
				List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
				comp.add(((ExpressionComputation<?>) value).expression().get(0));
				comp.add(args -> new DoubleConstant(1.0));
				return new ExpressionComputation(comp,
							value.getInputs().subList(1, value.getInputs().size()).toArray(Supplier[]::new))
						.setPostprocessor(Scalar.postprocessor());
			} else if (size == 2) {
				return new ExpressionComputation(((ExpressionComputation) value).expression(),
							value.getInputs().subList(1, value.getInputs().size()).toArray(Supplier[]::new))
						.setPostprocessor(Scalar.postprocessor());
			} else {
				throw new IllegalArgumentException();
			}
		} else if (value instanceof Shape) {
			TraversalPolicy shape = ((Shape) value).getShape();

			List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
					IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
									np -> np.get(1).getValue(i))
							.collect(Collectors.toList());
			return new ExpressionComputation<>(expressions, (Supplier) value)
					.setPostprocessor(Scalar.postprocessor());
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default ExpressionComputation<Scalar> toScalar(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		if (value == null) return null;

		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> args.get(1).getValue(0));
		comp.add(args -> expressionForDouble(1.0));
		return new ExpressionComputation(comp, value).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> value(Scalar value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> expressionForDouble(value.getMem().toArray(value.getOffset() + i, 1)[0])));
		return new ExpressionComputation(comp).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalar(Supplier<Evaluable<? extends MemoryBank<Scalar>>> bank, int index) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> args.get(1).getValue(2 * index + i)));
		return new ExpressionComputation(comp, (Supplier) bank).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalar(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection<?>>> collection, int index) {
		// return scalar(shape, collection, v((double) index));
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> args.get(1).getValue(shape.getSize() * index));
		comp.add(args -> expressionForDouble(1.0));
		return new ExpressionComputation(shape(2), comp, collection)
				.setPostprocessor(Scalar.postprocessor());
	}

	default DynamicExpressionComputation<Scalar> scalar(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection<?>>> collection, Supplier<Evaluable<? extends Scalar>> index) {
		DynamicExpressionComputation c =  new DynamicExpressionComputation<Scalar>(shape,
				(args, i) ->
						conditional(i.eq(e(0.0)), args[1].getValueAt(args[2].getValueAt(e(0)).multiply(shape.getSize())), e(1.0)),
				collection, (Supplier) index);
		c.setPostprocessor(Scalar.postprocessor());
		return c;
	}

	default Producer<Scalar> scalar() {
		return Scalar.blank();
	}

	default ExpressionComputation<Scalar> scalarAdd(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Sum(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> expressionForDouble(1.0));
		return new ExpressionComputation(comp, values).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarSubtract(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return scalarAdd(a, scalarMinus(b));
	}

	default ExpressionComputation<Scalar> scalarsMultiply(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(1)).toArray(Expression[]::new)));
		return new ExpressionComputation<>(comp, (Supplier[]) values).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarsDivide(Producer<Scalar> a, Producer<Scalar> b) {
		return scalarsMultiply(a, scalarPow(b, v(-1.0)));
	}

	default ExpressionComputation<Scalar> scalarMinus(Supplier<Evaluable<? extends Scalar>> v) {
		return scalarsMultiply(ScalarFeatures.minusOne(), v);
	}

	default ExpressionComputation<Scalar> scalarPow(Producer<Scalar> base, Producer<Scalar> exponent) {
		// TODO  Certainty of exponent is ignored
		return new ExpressionComputation<>(List.of(
				args -> new Exponent(args.get(1).getValue(0), args.get(2).getValue(0)),
				args -> new Exponent(args.get(1).getValue(1), args.get(2).getValue(0))),
				(Supplier) base, (Supplier) exponent)
				.setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarPow(Producer<Scalar> base, Scalar exp) {
		return scalarPow(base, of(exp));
	}

	default ExpressionComputation<Scalar> scalarPow(Producer<Scalar>  base, double value) {
		return scalarPow(base, new Scalar(value));
	}

	default ExpressionComputation<Scalar> min(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ExpressionComputation<>(List.of(
				args -> new Min(args.get(1).getValue(0), args.get(2).getValue(0)),
				args -> new Min(args.get(1).getValue(1), args.get(2).getValue(1))),
				(Supplier) a, (Supplier) b).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> mod(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ExpressionComputation<>(List.of(
				args -> new Mod(args.get(1).getValue(0), args.get(2).getValue(0)),
				args -> args.get(1).getValue(1)),
				(Supplier) a, (Supplier) b).setPostprocessor(Scalar.postprocessor());
	}

	default ScalarChoice choice(int choiceCount, Supplier<Evaluable<? extends Scalar>> decision, Supplier<Evaluable<? extends MemoryBank<Scalar>>> choices) {
		return new ScalarChoice(choiceCount, decision, choices);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> left,
															  Supplier<Evaluable<? extends Scalar>> right,
															  boolean includeEqual) {
		return greaterThan(left, right, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> left,
															  Supplier<Evaluable<? extends Scalar>> right,
															  Supplier<Evaluable<? extends Scalar>> trueValue,
															  Supplier<Evaluable<? extends Scalar>> falseValue,
															  boolean includeEqual) {
		return new GreaterThanScalar(left, right, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Supplier<Evaluable<? extends Scalar>> left,
															  Supplier<Evaluable<? extends Scalar>> right,
															  boolean includeEqual) {
		return lessThan(left, right, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Supplier<Evaluable<? extends Scalar>> left,
															  Supplier<Evaluable<? extends Scalar>> right,
															  Supplier<Evaluable<? extends Scalar>> trueValue,
															  Supplier<Evaluable<? extends Scalar>> falseValue,
															  boolean includeEqual) {
		return new LessThanScalar(left, right, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementVector lessThanv(Producer<Scalar> left,
															Producer<Scalar>  right) {
		return new LessThanVector(left, right, null, null);
	}

	default AcceleratedConditionalStatementVector lessThanv(Producer<Scalar>  left,
															Producer<Scalar> right,
															Producer<Vector> trueValue,
															Producer<Vector> falseValue) {
		return new LessThanVector(left, right, (Supplier) trueValue, (Supplier) falseValue);
	}

	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
