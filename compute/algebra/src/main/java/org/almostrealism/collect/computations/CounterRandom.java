/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Cast;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.computations.Assignment;

import java.util.List;

/**
 * The {@link RandomProducer} implementation whose values are computed inside the compiled kernel, as
 * a counter-based generator. It is selected for every {@code rand}/{@code randn} when the
 * {@code AR_RANDOM} setting is {@code counter} (see {@link RandomProducer}).
 *
 * <p>Each output element is a pure function of {@code (seed, counter, element index)}:</p>
 * <pre>
 * stream = pcg(pcg(seed) ^ counter)
 * word   = pcg(stream ^ index)
 * </pre>
 * <p>where {@code pcg} is the 32-bit PCG hash of Jarzynski and Olano, "Hash Functions for GPU
 * Rendering", Journal of Computer Graphics Techniques, 2020 (see
 * {@link Expression#pcgHash()}), and all arithmetic is unsigned 32-bit. The hash is a
 * bijection on 32-bit values, so within one {@code (seed, counter)} stream distinct element
 * indices never produce the same word.</p>
 *
 * <h2>Distributions</h2>
 * <ul>
 *   <li><b>Uniform</b> — the top 24 bits of the word scaled by 2^-24, giving values in
 *       {@code [0, 1)}. A 24-bit value and its product with a power of two are exact in both
 *       single and double precision, so the uniform output is bit-identical on every backend
 *       (all 32 bits would round to exactly {@code 1.0} in single precision for about one key
 *       in 2^25).</li>
 *   <li><b>Normal</b> — a standard Gaussian by the Box–Muller transform,
 *       {@code sqrt(-2 ln u1) cos(2 pi u2)}, where {@code u1} and {@code u2} are drawn from the
 *       words for indices {@code 2i} and {@code 2i + 1}. {@code u1} is mapped to {@code (0, 1]}
 *       so the logarithm is always finite. The transcendental functions are evaluated at the
 *       backend's floating-point precision, so normal output agrees across backends only to
 *       within that precision.</li>
 * </ul>
 *
 * <h2>Seed and counter</h2>
 * <p>The seed and the counter are each held in a single-element {@link PackedCollection} and
 * passed to the kernel as arguments, so generators that differ only in their seed or counter
 * share one compiled kernel. The same {@code (seed, counter)} always reproduces the same
 * output. {@link #refresh()} advances the counter with a device-side assignment, which selects
 * a new independent stream without host-side generation, transfer or recompilation. The
 * counter collection may also be shared with, and advanced by, other device-side operations
 * (for example a simulation step counter).</p>
 *
 * <p>Both values are read from floating-point memory and converted to integers in the kernel.
 * On a single-precision backend a collection holds integers exactly only up to 2^24, so the
 * seed and the counter must stay below 2^24 (about 16.7 million steps); beyond that
 * consecutive counters round to the same value and their streams repeat. Only the low 32
 * bits of each take part in the key.</p>
 *
 * @see RandomProducer
 * @see Random
 *
 * @author Michael Murray
 */
public class CounterRandom extends TraversableExpressionComputation implements RandomProducer {
	/** The scale 2^-24 that maps a 24-bit integer onto {@code [0, 1)}. */
	public static final double UNIT_SCALE = 1.0 / (1 << 24);

	/** The exclusive upper bound of a seed, below which every integer is exact in FP32. */
	public static final int SEED_LIMIT = 1 << 24;

	/** Whether the output is standard normal ({@code true}) or uniform ({@code false}). */
	private final boolean normal;

	/** The single-element collection holding the seed. */
	private final PackedCollection seed;

	/** The single-element collection holding the counter, advanced by {@link #refresh()}. */
	private final PackedCollection counter;

	/** Whether this generator created the seed and counter collections and so destroys them. */
	private final boolean owned;

	/** The compiled device-side increment of the counter, created on first refresh. */
	private Runnable compiledAdvance;

	/**
	 * Creates a counter-based generator with its own seed and counter, the counter starting
	 * at zero.
	 *
	 * @param shape  the shape of the output
	 * @param normal {@code true} for standard normal values, {@code false} for uniform values
	 *               in {@code [0, 1)}
	 * @param source the source the seed is drawn from, so that a seeded source reproduces the
	 *               same values; {@code null} for an unseeded generator
	 */
	public CounterRandom(TraversalPolicy shape, boolean normal, java.util.Random source) {
		this(shape, normal, (source == null ? new java.util.Random() : source).nextInt(SEED_LIMIT));
	}

	/**
	 * Creates a counter-based generator with the given seed and its own counter, the counter
	 * starting at zero.
	 *
	 * @param shape  the shape of the output
	 * @param normal {@code true} for standard normal values, {@code false} for uniform values
	 *               in {@code [0, 1)}
	 * @param seed   the seed, an integer in {@code [0, }{@link #SEED_LIMIT}{@code )}
	 * @throws IllegalArgumentException if the seed is outside that range, where it could not
	 *                                  be held exactly in single-precision memory
	 */
	public CounterRandom(TraversalPolicy shape, boolean normal, int seed) {
		this(shape, normal, PackedCollection.of(seed), new PackedCollection(1), true);

		if (seed < 0 || seed >= SEED_LIMIT) {
			this.seed.destroy();
			this.counter.destroy();
			throw new IllegalArgumentException("Seed " + seed + " is outside [0, " + SEED_LIMIT + ")");
		}
	}

	/**
	 * Creates a counter-based generator over the given seed and counter collections.
	 *
	 * @param shape   the shape of the output
	 * @param normal  {@code true} for standard normal values, {@code false} for uniform values
	 *                in {@code [0, 1)}
	 * @param seed    a single-element collection holding an integer seed below
	 *                {@link #SEED_LIMIT}
	 * @param counter a single-element collection holding the integer counter, below 2^24
	 */
	public CounterRandom(TraversalPolicy shape, boolean normal,
						 PackedCollection seed, PackedCollection counter) {
		this(shape, normal, seed, counter, false);
	}

	/**
	 * Creates a counter-based generator over the given seed and counter collections, passing
	 * them to the kernel through collection producers.
	 *
	 * @param shape   the shape of the output
	 * @param normal  whether the output is standard normal
	 * @param seed    the seed collection
	 * @param counter the counter collection
	 * @param owned   whether this generator created the collections and destroys them in
	 *                {@link #destroy()}
	 */
	private CounterRandom(TraversalPolicy shape, boolean normal,
						  PackedCollection seed, PackedCollection counter, boolean owned) {
		this(shape, normal, seed, counter, owned, CollectionFeatures.getInstance().p(seed),
				CollectionFeatures.getInstance().p(counter));
	}

	/**
	 * Creates a counter-based generator whose kernel arguments are the given producers of the
	 * seed and counter collections.
	 *
	 * @param shape        the shape of the output
	 * @param normal       whether the output is standard normal
	 * @param seed         the seed collection
	 * @param counter      the counter collection
	 * @param owned        whether this generator created the collections and destroys them in
	 *                     {@link #destroy()}
	 * @param seedInput    the producer of the seed passed to the kernel
	 * @param counterInput the producer of the counter passed to the kernel
	 */
	protected CounterRandom(TraversalPolicy shape, boolean normal,
							PackedCollection seed, PackedCollection counter, boolean owned,
							Producer<PackedCollection> seedInput,
							Producer<PackedCollection> counterInput) {
		super(normal ? "randn" : "rand", shape, seedInput, counterInput);
		this.normal = normal;
		this.seed = seed;
		this.counter = counter;
		this.owned = owned;
	}

	/**
	 * Returns whether this computation produces standard normal values.
	 *
	 * @return {@code true} for normal values, {@code false} for uniform values
	 */
	@Override
	public boolean isNormal() { return normal; }

	/**
	 * Returns the single-element collection holding the seed.
	 *
	 * @return the seed collection
	 */
	public PackedCollection getSeed() { return seed; }

	/**
	 * Returns the single-element collection holding the counter.
	 *
	 * @return the counter collection
	 */
	public PackedCollection getCounter() { return counter; }

	/**
	 * Returns the device-side increment of the counter as an {@link Assignment} operation, so a
	 * caller composing one larger compiled operation (for example a single simulation step) can
	 * advance this generator's stream in order with its other state updates. Unlike
	 * {@link #refresh()}, this neither compiles nor runs the assignment &mdash; the caller owns
	 * when it is compiled and when it runs.
	 *
	 * @return the counter increment assignment
	 */
	public Assignment<PackedCollection> advance() {
		return a(p(counter), add(p(counter), c(1.0)));
	}

	/**
	 * Returns the reset of the counter to its initial value of zero as an {@link Assignment}
	 * operation, for a caller that composes the counter's state into a larger reset operation of
	 * its own. This neither compiles nor runs the assignment.
	 *
	 * @return the counter reset assignment
	 */
	public Assignment<PackedCollection> resetCounter() {
		return a(p(counter), c(0.0));
	}

	/**
	 * Advances the counter by one with a compiled device-side assignment, so the next
	 * evaluation produces the next stream. The assignment is {@link #advance() the same}
	 * increment used when composing a larger operation, compiled on the first call and reused
	 * afterwards.
	 */
	@Override
	public void refresh() {
		if (compiledAdvance == null) {
			compiledAdvance = advance().get();
		}

		compiledAdvance.run();
	}

	/**
	 * Destroys this computation and the compiled counter increment, and the seed and counter
	 * collections when this generator created them. Collections supplied by the caller remain
	 * the caller's to release.
	 */
	@Override
	public void destroy() {
		super.destroy();
		Destroyable.destroy(compiledAdvance);
		compiledAdvance = null;

		if (owned) {
			seed.destroy();
			counter.destroy();
		}
	}

	/**
	 * Builds the expression computing each element from the key.
	 *
	 * @param args the traversable arguments, where {@code args[1]} is the seed and
	 *             {@code args[2]} is the counter
	 * @return the expression for the random values
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		Expression<?> seed = args[1].getValueAt(new IntegerConstant(0)).toLong();
		Expression<?> counter = args[2].getValueAt(new IntegerConstant(0)).toLong();
		Expression<?> stream = seed.pcgHash().xor(counter.toUnsigned(Precision.FP32)).pcgHash();
		return DefaultCollectionExpression.create(getShape(),
				index -> normal ? normal(stream, index) : uniform(stream, index));
	}
	/**
	 * Returns the uniform value in {@code [0, 1)} for the given element of a stream.
	 *
	 * @param stream the hashed {@code (seed, counter)} stream key
	 * @param index  the element index
	 * @return the uniform value expression
	 */
	protected Expression<?> uniform(Expression<?> stream, Expression<?> index) {
		return unit(word(stream, index).shiftRight(8));
	}

	/**
	 * Returns the standard normal value for the given element of a stream, by the Box–Muller
	 * transform of the words for indices {@code 2 * index} and {@code 2 * index + 1}.
	 *
	 * @param stream the hashed {@code (seed, counter)} stream key
	 * @param index  the element index
	 * @return the normal value expression
	 */
	protected Expression<?> normal(Expression<?> stream, Expression<?> index) {
		Expression<?> first = index.toLong(true).multiply(2);
		Expression<?> u1 = unit(word(stream, first).shiftRight(8).add(1));
		Expression<?> u2 = uniform(stream, first.add(1));
		Expression<Double> radius = u1.log().multiply(-2.0).pow(new DoubleConstant(0.5));
		Expression<Double> angle = u2.multiply(2 * Math.PI).cos();
		return radius.multiply(angle);
	}

	/**
	 * Converts an integer in {@code [0, 2^24]} to floating point and scales it by 2^-24. Both
	 * steps are exact at single and double precision.
	 *
	 * @param bits the integer expression
	 * @return the scaled floating-point expression
	 */
	protected Expression<?> unit(Expression<?> bits) {
		return Cast.of(Double.class, Cast.FP_NAME, bits).multiply(UNIT_SCALE);
	}

	/**
	 * Returns the 32-bit random word for the given element of a stream.
	 *
	 * @param stream the hashed {@code (seed, counter)} stream key
	 * @param index  the element index
	 * @return the word expression, in {@code [0, 2^32)}
	 */
	protected Expression<?> word(Expression<?> stream, Expression<?> index) {
		return stream.xor(index.toUnsigned(Precision.FP32)).pcgHash();
	}

	/**
	 * Generates a counter-based random computation over the given child processes.
	 *
	 * @param children the child processes, where {@code children.get(1)} is the seed and
	 *                 {@code children.get(2)} is the counter
	 * @return a new {@link CounterRandom} with the same configuration
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CounterRandom(getShape(), normal, seed, counter, false,
				(Producer<PackedCollection>) children.get(1),
				(Producer<PackedCollection>) children.get(2))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
