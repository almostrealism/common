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

package org.almostrealism.algebra.computations;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.TraversalPolicy;

import java.util.Optional;
import java.util.function.Supplier;

public class Dither extends ScalarBankAdd {

	public Dither(int count, Supplier<Evaluable<? extends ScalarBank>> input,
				  Supplier<Evaluable<? extends Scalar>> ditherValue) {
		super(count, input, gaussRand(ditherValue));
	}

	private static ScalarProducerBase gaussRand(Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ScalarFeatures ops = ScalarFeatures.getInstance();
		return ScalarFeatures.getInstance().scalarsMultiply(ditherValue, ops.scalar(ops.shape(1), ops.randn(ops.shape(1)), 0));
	}
}
