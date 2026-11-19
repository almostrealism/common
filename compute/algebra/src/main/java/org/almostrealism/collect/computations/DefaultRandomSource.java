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

import org.almostrealism.io.SystemUtils;

import java.util.OptionalLong;

/**
 * The one process-wide random source that {@link RandomProducer#create(io.almostrealism.collect.TraversalPolicy,
 * boolean, java.util.Random)} draws from when a caller supplies no explicit
 * {@link java.util.Random} source. Every {@code rand}/{@code randn} call without a source, and
 * so {@link org.almostrealism.collect.PackedCollection#randFill()}/{@code randnFill()} and
 * layer weight initialization (whose source is {@code null} by default), ultimately resolve
 * their {@code null} source here.
 *
 * <p>The source is <em>unseeded</em> until something seeds it. While unseeded, {@link #current()}
 * returns {@code null}, which is exactly the value a caller would have passed, so the behaviour is
 * byte-identical to supplying no source at all: each generator receives its own fresh
 * {@link java.util.Random}. Production therefore stays unchanged unless it is configured. Seeding
 * the source makes every later seedless draw reproducible, because a seeded source is consumed in
 * construction order: each generator construction takes one draw from it to seed a
 * {@link #generator() generator-owned source}, so the same sequence of generator constructions
 * yields the same values on every run, while no generator retains the shared source itself.</p>
 *
 * <p>At class initialization the source is seeded from the {@code AR_RANDOM_SEED} setting (a system
 * property or environment variable, read through {@link SystemUtils#getProperty(String, String)})
 * when that setting is present; otherwise it remains unseeded. Tests reseed it before every method
 * through {@code TestSuiteBase}, so unseeded random inputs become identical from run to run and
 * independent of test order.</p>
 *
 * <p><strong>Thread safety.</strong> The held source is a single {@link java.util.Random}, which is
 * itself thread-safe; {@link #seed(long)} and {@link #clear()} publish the new source through a
 * {@code volatile} field. The <em>order</em> of draws is only deterministic when generators are
 * constructed on a single thread, which is the case for graph construction in tests.</p>
 *
 * @author Michael Murray
 * @see RandomProducer
 */
public final class DefaultRandomSource {
	/** The active source, or {@code null} when unseeded. */
	private static volatile java.util.Random source;

	/** The seed behind {@link #source}, or {@code null} when unseeded. */
	private static volatile Long seed;

	static {
		String configured = SystemUtils.getProperty("AR_RANDOM_SEED", "");
		if (configured != null && !configured.trim().isEmpty()) {
			seed(Long.parseLong(configured.trim()));
		}
	}

	/** Not instantiable: the default source is process-wide state accessed through the static API. */
	private DefaultRandomSource() { }

	/**
	 * Replaces the default source with a new {@link java.util.Random} seeded with the given value.
	 * Every later seedless {@code rand}/{@code randn} draws from it, so the values become
	 * reproducible for the same sequence of generator constructions.
	 *
	 * @param seed the seed for the new default source
	 */
	public static void seed(long seed) {
		DefaultRandomSource.source = new java.util.Random(seed);
		DefaultRandomSource.seed = seed;
	}

	/**
	 * Returns the default source to the unseeded state. {@link #current()} then returns
	 * {@code null} again, restoring the behaviour of each generator drawing from its own fresh
	 * {@link java.util.Random}.
	 */
	public static void clear() {
		source = null;
		seed = null;
	}

	/**
	 * Returns the seeded default source, or {@code null} when the source is unseeded. A
	 * {@code null} result is the signal that a generator should fall back to its own fresh
	 * {@link java.util.Random}, leaving unseeded behaviour unchanged.
	 *
	 * @return the seeded default source, or {@code null} when unseeded
	 */
	// TODO(review): javadoc predates generator(); generators now resolve through generator(), not current()
	public static java.util.Random current() {
		return source;
	}

	/**
	 * Returns a new {@link java.util.Random} for one generator, seeded with the next value drawn
	 * from the default source, or {@code null} when the source is unseeded. Each generator thereby
	 * owns its own {@link java.util.Random}, exactly as it does when the source is unseeded: the
	 * shared source is consumed once per generator construction (so the same construction order
	 * still reproduces the same values), but it is never retained by a generator. A generator's
	 * later {@link RandomProducer#refresh() refreshes} therefore draw only from its own source and
	 * are independent of other generators, of other threads, and of later {@link #seed(long)} or
	 * {@link #clear()} calls.
	 *
	 * @return a generator-owned source derived from the default source, or {@code null} when unseeded
	 */
	public static java.util.Random generator() {
		java.util.Random active = source;
		return active == null ? null : new java.util.Random(active.nextLong());
	}

	/**
	 * Returns the seed currently behind the default source, or an empty {@link OptionalLong} when
	 * the source is unseeded.
	 *
	 * @return the active seed, or empty when unseeded
	 */
	public static OptionalLong getSeed() {
		Long active = seed;
		return active == null ? OptionalLong.empty() : OptionalLong.of(active);
	}
}
