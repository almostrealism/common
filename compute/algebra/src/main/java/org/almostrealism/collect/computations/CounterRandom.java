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
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Cast;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A stateless, counter-based random number generator whose values are computed inside the
 * compiled kernel.
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
 * <h2>Variants</h2>
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
 * <h2>Difference from {@link Random}</h2>
 * <p>{@link Random} draws its values on the host with {@link java.util.Random} and caches them
 * in a {@link PackedCollection} until {@link Random#refresh()} is called, so fresh noise
 * requires host work and a transfer. {@code CounterRandom} has no host cache and no
 * {@code refresh()}: the generated kernel computes every value from the key. The same
 * {@code (seed, counter)} always reproduces the same output, and advancing the counter,
 * which is an ordinary producer (for example a single-element {@link PackedCollection}
 * advanced by a device-side assignment), selects a new independent stream without
 * recompiling the kernel.</p>
 *
 * <h2>Counter range</h2>
 * <p>The counter is read from a floating-point collection and converted to an integer in the
 * kernel. On a single-precision backend a collection holds integers exactly only up to 2^24,
 * so the counter must stay below 2^24 (about 16.7 million steps); beyond that consecutive
 * counters round to the same value and their streams repeat. Only the low 32 bits of the
 * counter take part in the key.</p>
 *
 * <pre>{@code
 * PackedCollection step = new PackedCollection(1);
 * CollectionProducer noise = counterRandn(shape(1024), 42, p(step));
 * }</pre>
 *
 * @see Random
 * @see org.almostrealism.collect.CollectionCreationFeatures#counterRand(TraversalPolicy, long, Producer)
 * @see org.almostrealism.collect.CollectionCreationFeatures#counterRandn(TraversalPolicy, long, Producer)
 *
 * @author Michael Murray
 */
public class CounterRandom extends TraversableExpressionComputation {
	/** The scale 2^-24 that maps a 24-bit integer onto {@code [0, 1)}. */
	public static final double UNIT_SCALE = 1.0 / (1 << 24);

	/** Whether the output is standard normal ({@code true}) or uniform ({@code false}). */
	private final boolean normal;

	/** The seed that, with the counter, selects the stream; only its low 32 bits are used. */
	private final long seed;

	/**
	 * Creates a counter-based random computation.
	 *
	 * @param shape   the shape of the output
	 * @param normal  {@code true} for standard normal values, {@code false} for uniform values
	 *                in {@code [0, 1)}
	 * @param seed    the seed, folded into the kernel as a constant
	 * @param counter a producer of a single value whose integer part selects the stream
	 */
	public CounterRandom(TraversalPolicy shape, boolean normal, long seed,
						 Producer<PackedCollection> counter) {
		super(normal ? "counterRandn" : "counterRand", shape, counter);
		this.normal = normal;
		this.seed = seed;
	}

	/**
	 * Returns whether this computation produces standard normal values.
	 *
	 * @return {@code true} for normal values, {@code false} for uniform values
	 */
	public boolean isNormal() { return normal; }

	/**
	 * Returns the seed of this computation.
	 *
	 * @return the seed
	 */
	public long getSeed() { return seed; }

	/**
	 * Includes the seed in the signature, since it is compiled into the kernel as a constant
	 * and computations with different seeds must not share a cached kernel.
	 *
	 * @return the signature, or {@code null} if the parent signature is {@code null}
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;
		return signature + "{seed=" + seed + "}";
	}

	/**
	 * Builds the expression computing each element from the key.
	 *
	 * @param args the traversable arguments, where {@code args[1]} is the counter
	 * @return the expression for the random values
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		Expression<?> counter = args[1].getValueAt(new IntegerConstant(0)).toLong();
		Expression<?> stream = new LongConstant(seed).pcgHash().xor(counter.toUnsigned32()).pcgHash();
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
		return stream.xor(index.toUnsigned32()).pcgHash();
	}

	/**
	 * Generates a counter-based random computation over the given child processes.
	 *
	 * @param children the child processes, where {@code children.get(1)} is the counter
	 * @return a new {@link CounterRandom} with the same configuration
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CounterRandom(getShape(), normal, seed, (Producer<PackedCollection>) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
