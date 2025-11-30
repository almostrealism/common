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

package org.almostrealism.audio.computations.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class DefaultEnvelopeComputationTest implements TestFeatures {
	private static final PackedCollection input = new PackedCollection(1);

	static {
		input.setMem(0, 0.5);
	}

	public DefaultEnvelopeComputation computation() {
		return new DefaultEnvelopeComputation(p(input));
	}

	@Test
	public void evaluate() {
		Evaluable<PackedCollection> s = computation().get();

		input.setMem(0, 0.5);
		assertEquals(0.7071067811865, s.evaluate().toDouble(0));
		input.setMem(0, 1.0);
		assertEquals(0.0, s.evaluate().toDouble(0));
	}
}
