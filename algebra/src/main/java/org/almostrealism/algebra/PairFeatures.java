/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.computations.ComplexProduct;
import org.almostrealism.algebra.computations.DefaultPairEvaluable;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.PairFromScalars;
import org.almostrealism.algebra.computations.PairProduct;
import org.almostrealism.algebra.computations.PairSum;
import org.almostrealism.algebra.computations.RandomPair;
import org.almostrealism.algebra.computations.ScalarFromPair;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.algebra.computations.StaticPairComputation;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.StaticScalarComputation;

import java.util.function.Supplier;

public interface PairFeatures {

	default PairProducer pair(double x, double y) { return value(new Pair(x, y)); }

	default PairProducer pair(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y) {
		return new PairFromScalars(x, y);
	}

	default PairProducer v(Pair value) { return value(value); }

	default PairProducer value(Pair value) {
		return new StaticPairComputation(value);
	}

	default ScalarEvaluable l(Evaluable<Pair> p) {
		return new DefaultScalarEvaluable(l(() -> p));
	}

	default ScalarProducer l(Supplier<Evaluable<? extends Pair>> p) {
		return new ScalarFromPair(p, ScalarFromPair.X);
	}

	default ScalarEvaluable r(Evaluable<Pair> p) {
		return new DefaultScalarEvaluable(r(() -> p));
	}

	default ScalarProducer r(Supplier<Evaluable<? extends Pair>> p) {
		return new ScalarFromPair(p, ScalarFromPair.Y);
	}

	default PairEvaluable pairAdd(Evaluable<Pair> a, Evaluable<Pair> b) {
		return new DefaultPairEvaluable(pairAdd(() -> a, () -> b));
	}

	default PairProducer pairAdd(Supplier<Evaluable<? extends Pair>> a, Supplier<Evaluable<? extends Pair>> b) {
		return new PairSum(a, b);
	}

	default PairEvaluable pairSubtract(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return new DefaultPairEvaluable(pairSubtract(() -> a, () -> b));
	}

	default PairProducer pairSubtract(Supplier<Evaluable<? extends Pair>> a, Supplier<Evaluable<? extends Pair>> b) {
		return new PairSum(a, pairMinus(b));
	}

	default PairEvaluable pairsMultiply(Evaluable<Pair> a, Evaluable<Pair> b) {
		return new DefaultPairEvaluable(pairsMultiply(() -> a, () -> b));
	}

	default PairProducer pairsMultiply(Supplier<Evaluable<? extends Pair>> a, Supplier<Evaluable<? extends Pair>> b) {
		return new PairProduct(a, b);
	}

	default PairEvaluable multiplyComplex(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return new DefaultPairEvaluable(multiplyComplex(() -> a, () -> b));
	}

	default PairProducer multiplyComplex(Supplier<Evaluable<? extends Pair>> a, Supplier<Evaluable<? extends Pair>> b) {
		return new ComplexProduct(a, b);
	}

	default PairEvaluable pairDivide(Evaluable<Pair> a, Evaluable<Scalar> b) {
		return new DefaultPairEvaluable(pairDivide(() -> a, () -> b));
	}

	default PairProducer pairDivide(Supplier<Evaluable<? extends Pair>> a, Supplier<Evaluable<? extends Scalar>> b) {
		ScalarProducer v = new ScalarPow(b, new StaticScalarComputation(new Scalar(-1.0)));
		return new PairProduct(a, pair(v, v));
	}

	default PairEvaluable pairsDivide(Evaluable<Pair> a, Evaluable<Pair> b) {
		return new DefaultPairEvaluable(pairsDivide(() -> a, () -> b));
	}

	default PairProducer pairsDivide(Supplier<Evaluable<? extends Pair>> a, Supplier<Evaluable<? extends Pair>> b) {
		return new PairProduct(a, pair(r(b).pow(-1.0), l(b).pow(-1.0)));
	}

	default PairEvaluable pairMinus(Evaluable<Scalar> v) {
		return new DefaultPairEvaluable(pairMinus(() -> v));
	}

	default PairProducer pairMinus(Supplier<Evaluable<? extends Pair>> v) {
		return new PairProduct(v(new Pair(-1.0, -1.0)), v);
	}

	default Supplier<Evaluable<? extends Pair>> rand() {
		return () -> new RandomPair();
	}

	default PairEvaluable fromScalars(Evaluable<Scalar> x, Evaluable<Scalar> y) {
		return new DefaultPairEvaluable(fromScalars(() -> x, () -> y));
	}

	default PairProducer fromScalars(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y) {
		return new PairFromScalars(x, y);
	}

	static PairFeatures getInstance() {
		return new PairFeatures() { };
	}
}
