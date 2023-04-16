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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Sum;
import org.almostrealism.algebra.computations.*;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface PairFeatures extends HardwareFeatures {

	static PairExpressionComputation of(double l, double r) { return of(new Pair<>(l, r)); }

	static PairExpressionComputation of(Pair<?> value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> HardwareFeatures.ops().expressionForDouble(value.toDouble(i))));
		return new PairExpressionComputation(comp);
	}

	default PairProducer pair(double x, double y) { return value(new Pair(x, y)); }

	default PairProducer pair(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y) {
		return new PairFromScalars(x, y);
	}

	default PairProducer v(Pair value) { return value(value); }

	default PairProducer value(Pair value) {
		return new StaticPairComputation(value);
	}

	default ScalarExpressionComputation l(Supplier<Evaluable<? extends Pair<?>>> p) {
		return new ScalarExpressionComputation(List.of(
				args -> args.get(1).getValue(0),
				args -> new Expression<>(Double.class, stringForDouble(1.0))), (Supplier) p);
	}

	default ScalarExpressionComputation r(Supplier<Evaluable<? extends Pair<?>>> p) {
		return new ScalarExpressionComputation(List.of(
				args -> args.get(1).getValue(1),
				args -> new Expression<>(Double.class, stringForDouble(1.0))), (Supplier) p);
	}

	@Deprecated
	default PairExpressionComputation pairAdd(Supplier<Evaluable<? extends Pair<?>>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Sum(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> new Sum(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(1)).toArray(Expression[]::new)));
		return new PairExpressionComputation(comp, (Supplier[]) values);
	}

	default PairProducer multiplyComplex(Supplier<Evaluable<? extends Pair<?>>> a, Supplier<Evaluable<? extends Pair<?>>> b) {
		return new ComplexProduct(a, b);
	}

	default PairProducer fromScalars(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y) {
		return new PairFromScalars(x, y);
	}

	static PairFeatures getInstance() {
		return new PairFeatures() { };
	}
}
