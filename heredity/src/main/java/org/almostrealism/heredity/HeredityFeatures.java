/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface HeredityFeatures extends CollectionFeatures {
	default Chromosome<PackedCollection<?>> c(Gene<PackedCollection<?>>... genes) {
		ArrayListChromosome<PackedCollection<?>> c = new ArrayListChromosome<>();
		Stream.of(genes).forEach(c::add);
		return c;
	}

	default Gene<PackedCollection<?>> g(double... factors) {
		return g(IntStream.range(0, factors.length).mapToObj(i -> new ScaleFactor(factors[i])).toArray(Factor[]::new));
	}

	default Gene<PackedCollection<?>> g(Scalar... factors) {
		return g(IntStream.range(0, factors.length).mapToObj(i -> new ScaleFactor(factors[i])).toArray(Factor[]::new));
	}

	default Gene<PackedCollection<?>> g(Producer<Scalar>... factors) {
		return g(Stream.of(factors).map(f -> (Factor) protein -> f).toArray(Factor[]::new));
	}

	default Gene<PackedCollection<?>> g(Factor<PackedCollection<?>>... factors) {
		ArrayListGene<PackedCollection<?>> gene = new ArrayListGene<>();
		IntStream.range(0, factors.length).mapToObj(i -> factors[i]).forEach(gene::add);
		return gene;
	}

	default double invertOneToInfinity(double target, double multiplier, double exp) {
		return Math.pow(1 - (1 / ((target / multiplier) + 1)), 1.0 / exp);
	}

	default double oneToInfinity(double f, double exp) {
		return 1.0 / (1.0 - Math.pow(f, exp)) - 1.0;
	}

	default CollectionProducer<PackedCollection<?>> oneToInfinity(Factor<PackedCollection<?>> f, double exp) {
		return oneToInfinity(f.getResultant(c(1.0)), exp);
	}

	default CollectionProducer<PackedCollection<?>> oneToInfinity(Producer<PackedCollection<?>> arg, double exp) {
		return oneToInfinity(arg, c(exp));
	}

	default CollectionProducer<PackedCollection<?>> oneToInfinity(Producer<PackedCollection<?>> arg, Producer<PackedCollection<?>> exp) {
		CollectionProducer<PackedCollection<?>> pow = pow(arg, exp);
		CollectionProducer<PackedCollection<?>> out = minus(pow);
		out = add(out, c(1.0));
		out = pow(out, c(-1.0));
		out = add(out, c(-1.0));
		return out;
	}

	static HeredityFeatures getInstance() {
		return new HeredityFeatures() { };
	}
}
