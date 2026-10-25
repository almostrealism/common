/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.io.SystemUtils;

import java.util.Arrays;
import java.util.Locale;

/**
 * A producer of random values with a given {@link TraversalPolicy shape}, either uniformly
 * distributed in {@code [0, 1)} or standard normal (mean 0, standard deviation 1).
 *
 * <p>Random generation is one concept with substitutable implementations, and the
 * implementation is a decision the application makes once, when it starts, through the
 * {@code AR_RANDOM} setting:</p>
 * <ul>
 *   <li>{@code java} (the default) — {@link Random}, which draws values on the host with
 *       {@link java.util.Random} and delivers them to the device as data.</li>
 *   <li>{@code counter} — {@link CounterRandom}, which computes every value inside the
 *       compiled kernel as a hash of a seed, a counter and the element index, so no values
 *       are generated on the host or transferred.</li>
 * </ul>
 *
 * <p>Every generator obtained from {@link #create(TraversalPolicy, boolean, java.util.Random)},
 * and so from {@code rand}/{@code randn} in
 * {@link org.almostrealism.collect.CollectionCreationFeatures}, uses the selected
 * implementation. Both keep their values fixed between calls to {@link #refresh()}, which
 * moves the generator on to new values.</p>
 *
 * <pre>{@code
 * RandomProducer noise = randn(shape(1024));
 * CollectionProducer noisy = signal.add(noise);
 * // at each step boundary, move on to new noise
 * noise.refresh();
 * }</pre>
 *
 * @author Michael Murray
 * @see Random
 * @see CounterRandom
 */
public interface RandomProducer extends CollectionProducer {
	/**
	 * The implementation that {@link #create(TraversalPolicy, boolean, java.util.Random)}
	 * produces, read once from the {@code AR_RANDOM} setting ({@code java} or
	 * {@code counter}).
	 */
	Implementation IMPLEMENTATION = Implementation.of(SystemUtils.getProperty("AR_RANDOM", "java"));

	/**
	 * Returns whether this generator produces standard normal values.
	 *
	 * @return {@code true} for normal values, {@code false} for uniform values
	 */
	boolean isNormal();

	/**
	 * Moves this generator on to new values: the next evaluation produces values independent
	 * of those produced before. Until this is called, every evaluation produces the same
	 * values.
	 */
	void refresh();

	/**
	 * Creates a generator with the implementation selected by the {@code AR_RANDOM} setting.
	 *
	 * @param shape  the shape of the values
	 * @param normal {@code true} for standard normal values, {@code false} for uniform values
	 * @param source the source that seeds the generator, so that a seeded source reproduces
	 *               the same values; {@code null} for an unseeded generator
	 * @return the generator
	 */
	static RandomProducer create(TraversalPolicy shape, boolean normal, java.util.Random source) {
		return IMPLEMENTATION.create(shape, normal, source);
	}

	/** The implementations of {@link RandomProducer} that the {@code AR_RANDOM} setting can select. */
	enum Implementation {
		/** Values drawn on the host by {@link Random}. */
		JAVA {
			@Override
			public RandomProducer create(TraversalPolicy shape, boolean normal, java.util.Random source) {
				return source == null ? new Random(shape, normal) : new Random(shape, normal, source);
			}
		},

		/** Values computed inside the kernel by {@link CounterRandom}. */
		COUNTER {
			@Override
			public RandomProducer create(TraversalPolicy shape, boolean normal, java.util.Random source) {
				return new CounterRandom(shape, normal, source);
			}
		};

		/**
		 * Creates a generator of this implementation.
		 *
		 * @param shape  the shape of the values
		 * @param normal {@code true} for standard normal values, {@code false} for uniform
		 *               values
		 * @param source the source that seeds the generator, or {@code null} for an unseeded
		 *               generator
		 * @return the generator
		 */
		public abstract RandomProducer create(TraversalPolicy shape, boolean normal, java.util.Random source);

		/**
		 * Returns the implementation named by a value of the {@code AR_RANDOM} setting, ignoring
		 * case.
		 *
		 * @param name the setting value, {@code java} or {@code counter}
		 * @return the named implementation
		 * @throws IllegalArgumentException if the name is not one of the implementations
		 */
		public static Implementation of(String name) {
			for (Implementation implementation : values()) {
				if (implementation.name().equalsIgnoreCase(name.trim())) return implementation;
			}

			throw new IllegalArgumentException("AR_RANDOM must be one of " +
					Arrays.toString(values()).toLowerCase(Locale.ROOT) + ", not " + name);
		}
	}
}
