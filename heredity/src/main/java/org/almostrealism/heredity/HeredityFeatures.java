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

import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface HeredityFeatures {
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
}
