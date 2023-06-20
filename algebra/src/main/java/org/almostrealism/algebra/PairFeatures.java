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

import io.almostrealism.expression.Cosine;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sine;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface PairFeatures extends HardwareFeatures {

	static ExpressionComputation<Pair<?>> of(double l, double r) { return of(new Pair<>(l, r)); }

	static ExpressionComputation<Pair<?>> of(Pair<?> value) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> HardwareFeatures.ops().expressionForDouble(value.toDouble(i))));
		return new ExpressionComputation<Pair<?>>(comp)
				.setPostprocessor(Pair.postprocessor());
	}

	default ExpressionComputation<Pair<?>> pair(double x, double y) { return value(new Pair(x, y)); }

	default ExpressionComputation<Pair<?>> pair(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> args.get(1 + i).getValueRelative(0)));
		return new ExpressionComputation<Pair<?>>(comp, (Supplier) x, (Supplier) y)
				.setPostprocessor(Pair.postprocessor());
	}

	default ExpressionComputation<Pair<?>> pair(Supplier<Evaluable<? extends PackedCollection<?>>> x) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> args.get(1).getValueRelative(i)));
		return new ExpressionComputation<Pair<?>>(comp, x)
				.setPostprocessor(Pair.postprocessor());
	}

	default ExpressionComputation<Pair<?>> v(Pair value) { return value(value); }

	default ExpressionComputation<Pair<?>> value(Pair value) {
		return ExpressionComputation.fixed((Pair<?>) value, Pair.postprocessor());
	}

	default ExpressionComputation<Scalar> l(Supplier<Evaluable<? extends Pair<?>>> p) {
		return new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(0),
				args -> new DoubleConstant(1.0)), (Supplier) p).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> r(Supplier<Evaluable<? extends Pair<?>>> p) {
		return new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(1),
				args -> new DoubleConstant(1.0)), (Supplier) p).setPostprocessor(Scalar.postprocessor());
	}

	@Deprecated
	default ExpressionComputation<Pair<?>> pairAdd(Supplier<Evaluable<? extends Pair<?>>>... values) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Sum(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValueRelative(0)).toArray(Expression[]::new)));
		comp.add(args -> new Sum(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValueRelative(1)).toArray(Expression[]::new)));
		return new ExpressionComputation<Pair<?>>(comp, (Supplier[]) values)
				.setPostprocessor(Pair.postprocessor());
	}

	default ExpressionComputation<Pair<?>> multiplyComplex(Supplier<Evaluable<? extends Pair<?>>> a, Supplier<Evaluable<? extends Pair<?>>> b) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2).forEach(i -> comp.add(args -> {
			Expression p = args.get(1).getValueRelative(0);
			Expression q = args.get(1).getValueRelative(1);
			Expression r = args.get(2).getValueRelative(0);
			Expression s = args.get(2).getValueRelative(1);

			if (i == 0) {
				return new Sum(new Product(p, r), new Minus(new Product(q, s)));
			} else if (i == 1) {
				return new Sum(new Product(p, s), new Product(q, r));
			} else {
				throw new IllegalArgumentException();
			}
		}));
		return Pair.postprocess(new ExpressionComputation<>(comp, (Supplier) a, (Supplier) b));
	}

	default ExpressionComputation<Pair<?>> complexFromAngle(Supplier<Evaluable<? extends Scalar>> angle) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Cosine(args.get(1).getValueRelative(0)));
		comp.add(args -> new Sine(args.get(1).getValueRelative(0)));
		return Pair.postprocess(new ExpressionComputation<>(comp, (Supplier) angle));
	}

	static PairFeatures getInstance() {
		return new PairFeatures() { };
	}
}
