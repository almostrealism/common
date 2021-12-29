/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.StaticScalarComputation;

import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface HeredityFeatures {
	default Chromosome<Scalar> c(Gene<Scalar>... genes) {
		ArrayListChromosome<Scalar> c = new ArrayListChromosome<>();
		Stream.of(genes).forEach(c::add);
		return c;
	}

	default Gene<Scalar> g(double... factors) {
		return g(IntStream.range(0, factors.length).mapToObj(i -> new ScaleFactor(factors[i])).toArray(Factor[]::new));
	}

	default Gene<Scalar> g(Scalar... factors) {
		return g(IntStream.range(0, factors.length).mapToObj(i -> new ScaleFactor(factors[i])).toArray(Factor[]::new));
	}

	default Gene<Scalar> g(Producer<Scalar>... factors) {
		return g(Stream.of(factors).map(f -> (Factor) protein -> f).toArray(Factor[]::new));
	}

	default Gene<Scalar> g(Factor<Scalar>... factors) {
		ArrayListGene<Scalar> gene = new ArrayListGene<>();
		IntStream.range(0, factors.length).mapToObj(i -> factors[i]).forEach(gene::add);
		return gene;
	}

	default double invertOneToInfinity(double target, double multiplier, double exp) {
		return Math.pow(1 - (1 / ((target / multiplier) + 1)), 1.0 / exp);
	}

	default ScalarProducer oneToInfinity(Factor<Scalar> f, double exp) {
		return oneToInfinity(f.getResultant(new StaticScalarComputation(new Scalar(1.0))), exp);
	}

	default ScalarProducer oneToInfinity(Producer<Scalar> arg, double exp) {
		return oneToInfinity(arg, new StaticScalarComputation(new Scalar(exp)));
	}

	default ScalarProducer oneToInfinity(Producer<Scalar> arg, Producer<Scalar> exp) {
		ScalarProducer pow = new ScalarPow(arg, exp);
		return pow.minus().add(1.0).pow(-1.0).subtract(1.0);
	}

	static HeredityFeatures getInstance() {
		return new HeredityFeatures() { };
	}
}
