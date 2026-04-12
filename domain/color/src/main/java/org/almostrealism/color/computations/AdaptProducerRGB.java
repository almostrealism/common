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

import io.almostrealism.code.AdaptProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.hardware.MemoryBank;

/**
 * An {@link AdaptProducer} specialization that produces {@link RGB}-compatible memory banks.
 *
 * <p>This class wraps an existing {@link Producer} and overrides the destination memory
 * type so that results are stored in an {@link RGB} bank rather than a generic
 * {@link PackedCollection} bank. It is used when a general computation pipeline
 * needs to be adapted to produce typed RGB output.</p>
 *
 * @see AdaptProducer
 * @see RGB#bank(int)
 * @author Michael Murray
 */
public class AdaptProducerRGB extends AdaptProducer<PackedCollection> {
	/**
	 * Constructs an {@link AdaptProducerRGB} that wraps the given producer
	 * with additional argument producers.
	 *
	 * @param p    the underlying producer whose output is adapted to RGB memory
	 * @param args additional argument producers passed to the adapted computation
	 */
	public AdaptProducerRGB(Producer<PackedCollection> p, Producer... args) {
		super(p, args);
	}

	/**
	 * Returns an {@link Evaluable} that delegates evaluation to the wrapped producer
	 * but creates destinations using an {@link RGB} memory bank.
	 *
	 * @return an {@link Evaluable} whose {@code createDestination} returns an RGB bank
	 */
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
