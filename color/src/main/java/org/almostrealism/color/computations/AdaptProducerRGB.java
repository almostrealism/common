/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.color.computations;
import org.almostrealism.collect.PackedCollection;

import org.almostrealism.color.RGB;
import io.almostrealism.code.AdaptProducer;
import org.almostrealism.hardware.MemoryBank;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

public class AdaptProducerRGB extends AdaptProducer<PackedCollection> {
	public AdaptProducerRGB(Producer<PackedCollection> p, Producer... args) {
		super(p, args);
	}

	@Override
	public Evaluable<PackedCollection> get() {
		Evaluable<PackedCollection> e = super.get();

		return new Evaluable<>() {
			@Override
			public MemoryBank<PackedCollection> createDestination(int size) { return (MemoryBank) RGB.bank(size); }

			@Override
			public PackedCollection evaluate(Object... arguments) {
				return e.evaluate(arguments);
			}
		};
	}
}
