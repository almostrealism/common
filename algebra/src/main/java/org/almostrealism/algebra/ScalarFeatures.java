/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import org.almostrealism.algebra.computations.Floor;
import org.almostrealism.algebra.computations.Max;
import org.almostrealism.algebra.computations.Min;
import org.almostrealism.algebra.computations.Mod;
import org.almostrealism.algebra.computations.ScalarChoice;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.algebra.computations.ScalarFromScalarBank;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ScalarFromPackedCollection;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryBank;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface ScalarFeatures extends HardwareFeatures {

	static Supplier<Evaluable<? extends Scalar>> minusOne() { return of(-1.0); }

	static ScalarExpressionComputation of(double value) { return of(new Scalar(value)); }

	static ScalarExpressionComputation of(Scalar value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> HardwareFeatures.ops().expressionForDouble(value.getMem().toArray(value.getOffset() + i, 1)[0])));
		return new ScalarExpressionComputation(comp);
	}

	default ScalarExpressionComputation v(double value) { return value(new Scalar(value)); }

	default ScalarExpressionComputation v(Scalar value) { return value(value); }

	default ScalarExpressionComputation scalar(double value) { return value(new Scalar(value)); }

	default ScalarExpressionComputation toScalar(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> args.get(1).getValue(0));
		comp.add(args -> expressionForDouble(1.0));
		return new ScalarExpressionComputation(comp, value);
	}

	default ScalarExpressionComputation value(Scalar value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> expressionForDouble(value.getMem().toArray(value.getOffset() + i, 1)[0])));
		return new ScalarExpressionComputation(comp);
	}

	default ScalarProducer scalar(Supplier<Evaluable<? extends MemoryBank<Scalar>>> bank, int index) {
		return new ScalarFromScalarBank(bank, scalar((double) index));
	}

	default ScalarProducer scalar(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection>> collection, int index) {
		return scalar(shape, collection, v((double) index));
	}

	default ScalarProducer scalar(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection>> collection, Supplier<Evaluable<? extends Scalar>> index) {
		return new ScalarFromPackedCollection(shape, collection, index);
	}

	default ScalarProducer scalar() {
		return Scalar.blank();
	}

	default ScalarEvaluable scalarAdd(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return (ScalarEvaluable) scalarAdd(() -> a, () -> b).get();
	}

	default ScalarExpressionComputation scalarAdd(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Sum(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> expressionForDouble(1.0));
		return new ScalarExpressionComputation(comp, (Supplier[]) values);
	}

	default ScalarEvaluable scalarSubtract(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return (ScalarEvaluable) scalarSubtract(() -> a, () -> b).get();
	}

	default ScalarExpressionComputation scalarSubtract(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return scalarAdd(a, scalarMinus(b));
	}

	default ScalarEvaluable scalarsMultiply(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return (ScalarEvaluable) scalarsMultiply(() -> a, () -> b).get();
	}

	default ScalarExpressionComputation scalarsMultiply(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(1)).toArray(Expression[]::new)));
		return new ScalarExpressionComputation(comp, (Supplier[]) values);
	}

	default ScalarExpressionComputation scalarsDivide(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return scalarsMultiply(a, pow(b, v(-1.0)));
	}

	default ScalarEvaluable scalarMinus(Evaluable<Scalar> v) {
		return (ScalarEvaluable) scalarMinus(() -> v).get();
	}

	default ScalarProducerBase scalarMinus(Supplier<Evaluable<? extends Scalar>> v) {
		return scalarsMultiply(ScalarFeatures.minusOne(), v);
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, Evaluable<Scalar> exponent) {
		return (ScalarEvaluable) pow(() -> base, () -> exponent).get();
	}

	default ScalarProducerBase pow(Supplier<Evaluable<? extends Scalar>> base, Supplier<Evaluable<? extends Scalar>> exponent) {
		// TODO  Certainty of exponent is ignored
		return new ScalarExpressionComputation(List.of(
				args -> new Exponent(args.get(1).getValue(0), args.get(2).getValue(0)),
				args -> new Exponent(args.get(1).getValue(1), args.get(2).getValue(0))),
				(Supplier) base, (Supplier) exponent);
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, Scalar exp) {
		return pow(base, of(exp).get());
	}

	default ScalarProducerBase pow(Supplier<Evaluable<? extends Scalar>> base, Scalar exp) {
		return pow(base, of(exp));
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, double value) {
		return pow(base, new Scalar(value));
	}

	default ScalarProducerBase pow(Supplier<Evaluable<? extends Scalar>> base, double value) {
		return pow(base, new Scalar(value));
	}

	default ScalarProducer floor(Supplier<Evaluable<? extends Scalar>> value) {
		return new Floor(value);
	}

	default ScalarProducer min(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new Min(a, b);
	}

	default ScalarProducer max(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new Max(a, b);
	}

	default ScalarProducer mod(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new Mod(a, b);
	}

	default ScalarProducer bound(Supplier<Evaluable<? extends Scalar>> a, double min, double max) {
		return min(max(a, v(min)), v(max));
	}

	default ScalarChoice choice(int choiceCount, Supplier<Evaluable<? extends Scalar>> decision, Supplier<Evaluable<? extends MemoryBank<Scalar>>> choices) {
		return new ScalarChoice(choiceCount, decision, choices);
	}

	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
