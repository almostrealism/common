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

package org.almostrealism.algebra;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Min;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.computations.Choice;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.bool.AcceleratedConditionalStatement;
import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.bool.LessThanVector;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.hardware.MemoryBank;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface ScalarFeatures extends CollectionFeatures {

	/**
	 * Creates an {@link ExpressionComputation} that produces a constant {@link Scalar} value of -1.0.
	 * This is a commonly used constant in mathematical operations.
	 * 
	 * @return An {@link ExpressionComputation} that evaluates to a {@link Scalar} containing -1.0
	 * @see #of(double)
	 */
	static ExpressionComputation<Scalar> minusOne() { return of(-1.0); }

	/**
	 * Creates an {@link ExpressionComputation} that produces a constant {@link Scalar} value.
	 * This is a convenience method for creating scalar constants from primitive double values.
	 * 
	 * @param value The double value to be wrapped in a {@link Scalar}
	 * @return An {@link ExpressionComputation} that evaluates to a {@link Scalar} containing the specified value
	 * @see #of(Scalar)
	 */
	static ExpressionComputation<Scalar> of(double value) { return of(new Scalar(value)); }

	/**
	 * Creates an {@link ExpressionComputation} that produces a constant {@link Scalar} value.
	 * This method creates a computation that returns the values from the provided {@link Scalar},
	 * effectively creating a constant computation that always returns the same values.
	 * 
	 * @param value The {@link Scalar} containing the constant values
	 * @return An {@link ExpressionComputation} that evaluates to the specified {@link Scalar}
	 */
	static ExpressionComputation<Scalar> of(Scalar value) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> ExpressionFeatures.getInstance().e(value.getMem().toArray(value.getOffset() + i, 1)[0])));
		return (ExpressionComputation<Scalar>) new ExpressionComputation(comp).setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducer<Scalar> v(Scalar value) { return value(value); }

	default CollectionProducer<Scalar> scalar(double value) { return value(new Scalar(value)); }

	/**
	 * Creates a {@link Scalar} producer from a generic {@link Producer}.
	 * This method handles conversion of various producer types to {@link Scalar} computations,
	 * with special handling for {@link ExpressionComputation} instances.
	 * 
	 * <p>For {@link ExpressionComputation} inputs:</p>
	 * <ul>
	 *   <li>If the expression has 1 element: creates a 2-element scalar with the expression and a constant 1.0</li>
	 *   <li>If the expression has 2 elements: uses the expressions directly for the scalar components</li>
	 *   <li>Other sizes: throws IllegalArgumentException</li>
	 * </ul>
	 * 
	 * <p>For {@link Shape} inputs: extracts values up to 2 elements, padding with 1.0 if needed</p>
	 * 
	 * @param value The producer to convert to a {@link Scalar}
	 * @return A {@link CollectionProducer} that produces {@link Scalar} values
	 * @throws IllegalArgumentException if ExpressionComputation has unsupported size
	 * @throws UnsupportedOperationException if the producer type is not supported
	 */
	default CollectionProducer<Scalar> scalar(Producer<?> value) {
		if (value instanceof Shape) {
			TraversalPolicy shape = ((Shape) value).getShape();

			List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions =
					IntStream.range(0, 2).mapToObj(i -> {
								if (i < shape.getSize()) {
									return (Function<List<ArrayVariable<Double>>, Expression<Double>>)
											np -> np.get(1).getValueRelative(i);
								} else {
									return (Function<List<ArrayVariable<Double>>, Expression<Double>>)
											np -> new DoubleConstant(1.0);
								}
							})
							.collect(Collectors.toList());
			return (ExpressionComputation<Scalar>) new ExpressionComputation<>(expressions, (Supplier) value)
					.setPostprocessor(Scalar.postprocessor());
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default ExpressionComputation<Scalar> toScalar(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		if (value == null) return null;

		Function<List<ArrayVariable<Double>>, Expression<Double>> comp[] = new Function[2];
		comp[0] = args -> args.get(1).getValueRelative(0);
		comp[1] = args -> expressionForDouble(1.0);
		return (ExpressionComputation<Scalar>) new ExpressionComputation(List.of(comp), value).setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducer<Scalar> value(Scalar value) {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(value, Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalar(Supplier<Evaluable<? extends MemoryBank<Scalar>>> bank, int index) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> args.get(1).getValueRelative(2 * index + i)));
		return (ExpressionComputation<Scalar>) new ExpressionComputation(comp, (Supplier) bank).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalar(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection<?>>> collection, int index) {
		// return scalar(shape, collection, v((double) index));
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> args.get(1).getValueRelative(shape.getSize() * index));
		comp.add(args -> expressionForDouble(1.0));
		return (ExpressionComputation<Scalar>) new ExpressionComputation(shape(2), comp, collection)
				.setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducerComputation<Scalar> scalar(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection<?>>> collection, Supplier<Evaluable<? extends Scalar>> index) {
		DefaultTraversableExpressionComputation c = new DefaultTraversableExpressionComputation<Scalar>("scalar", shape,
				args -> CollectionExpression.create(shape, i ->
						conditional(i.toInt().eq(e(0)),
								args[1].getValueAt(args[2].getValueAt(e(0)).multiply(shape.getSize())), e(1.0))),
				collection, (Supplier) index);
		c.setPostprocessor(Scalar.postprocessor());
		return c;
	}

	default Producer<Scalar> scalar() {
		return Scalar.blank();
	}

	default ExpressionComputation<Scalar> scalarAdd(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> Sum.of(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValueRelative(0)).toArray(Expression[]::new)));
		comp.add(args -> expressionForDouble(1.0));
		return (ExpressionComputation<Scalar>) new ExpressionComputation(comp, values).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarSubtract(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return scalarAdd(a, scalarMinus(b));
	}

	default ExpressionComputation<Scalar> scalarsMultiply(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> (Expression<Double>) Product.of(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValueRelative(0)).toArray(Expression[]::new)));
		comp.add(args -> (Expression<Double>) Product.of(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValueRelative(1)).toArray(Expression[]::new)));
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(comp, (Supplier[]) values).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarMinus(Supplier<Evaluable<? extends Scalar>> v) {
		return scalarsMultiply(ScalarFeatures.minusOne(), v);
	}

	default ExpressionComputation<Scalar> scalarPow(Producer<Scalar> base, Producer<Scalar> exponent) {
		// TODO  Certainty of exponent is ignored
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> Exponent.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
				args -> Exponent.of(args.get(1).getValueRelative(1), args.get(2).getValueRelative(0))),
				(Supplier) base, (Supplier) exponent)
				.setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarPow(Producer<Scalar> base, Scalar exp) {
		return scalarPow(base, of(exp));
	}

	default ExpressionComputation<Scalar> scalarMin(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> Min.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
				args -> Min.of(args.get(1).getValueRelative(1), args.get(2).getValueRelative(1))),
				(Supplier) a, (Supplier) b).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> scalarMod(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> Mod.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
				args -> args.get(1).getValueRelative(1)),
				(Supplier) a, (Supplier) b).setPostprocessor(Scalar.postprocessor());
	}

	default Choice choice(int choiceCount, TraversalPolicy resultShape,
						  Producer<PackedCollection<?>> decision,
						  Producer<PackedCollection<?>> choices) {
		return new Choice(resultShape, choiceCount, decision, choices);
	}

	default AcceleratedConditionalStatement<Scalar> scalarGreaterThan(Supplier<Evaluable<? extends Scalar>> left,
																	  Supplier<Evaluable<? extends Scalar>> right,
																	  boolean includeEqual) {
		return scalarGreaterThan(left, right, null, null, includeEqual);
	}

	default AcceleratedConditionalStatement<Scalar> scalarGreaterThan(Supplier<Evaluable<? extends Scalar>> left,
																	  Supplier<Evaluable<? extends Scalar>> right,
																	  Supplier<Evaluable<? extends Scalar>> trueValue,
																	  Supplier<Evaluable<? extends Scalar>> falseValue,
																	  boolean includeEqual) {
		return new GreaterThanScalar(left, right, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatement<Scalar> scalarLessThan(Supplier<Evaluable<? extends Scalar>> left,
																   Supplier<Evaluable<? extends Scalar>> right,
																   boolean includeEqual) {
		return scalarLessThan(left, right, null, null, includeEqual);
	}

	default AcceleratedConditionalStatement<Scalar> scalarLessThan(Supplier<Evaluable<? extends Scalar>> left,
																   Supplier<Evaluable<? extends Scalar>> right,
																   Supplier<Evaluable<? extends Scalar>> trueValue,
																   Supplier<Evaluable<? extends Scalar>> falseValue,
																   boolean includeEqual) {
		return new LessThanScalar(left, right, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementVector scalarLessThan(Producer<Scalar> left,
																 Producer<Scalar> right) {
		// TODO  This should not be Vector-specific
		return new LessThanVector(left, right, null, null);
	}

	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
