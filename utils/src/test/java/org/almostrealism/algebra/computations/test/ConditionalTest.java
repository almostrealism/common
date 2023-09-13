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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.List;
import java.util.function.Function;

public class ConditionalTest implements TestFeatures {
	@Test
	public void positive() {
		CollectionProducerBase a = c(2);
		CollectionProducerBase b = c(2);
		CollectionProducerBase c = c(3);
		CollectionProducerBase d = c(5);

		Function<List<ArrayVariable<Double>>, Expression<Double>> expression =
				args -> conditional(equals(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
						args.get(3).getValueRelative(0), args.get(4).getValueRelative(0));
		Evaluable<PackedCollection<?>> ev = new ExpressionComputation<>(List.of(expression), a, b, c, d).get();

		PackedCollection<?> result = ev.evaluate();
		System.out.println(result.toDouble(0));
		assertEquals(3, result.toDouble(0));
	}

	@Test
	public void negative() {
		CollectionProducerBase a = c(2);
		CollectionProducerBase b = c(1);
		CollectionProducerBase c = c(3);
		CollectionProducerBase d = c(5);

		Function<List<ArrayVariable<Double>>, Expression<Double>> expression =
				args -> conditional(equals(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
						args.get(3).getValueRelative(0), args.get(4).getValueRelative(0));
		Evaluable<PackedCollection<?>> ev = new ExpressionComputation<>(List.of(expression), a, b, c, d).get();

		PackedCollection<?> result = ev.evaluate();
		System.out.println(result.toDouble(0));
		assertEquals(5, result.toDouble(0));
	}
}
