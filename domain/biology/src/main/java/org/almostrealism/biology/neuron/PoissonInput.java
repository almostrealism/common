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

package org.almostrealism.biology.neuron;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CounterRandom;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;

import java.util.function.Supplier;

/**
 * Independent Poisson spike trains delivered to a selected set of neurons, each spike a fixed
 * kick. As a {@link Block} it is a stage that adds the kicks to the drive flowing through it:
 * {@code [count]} in, {@code [count]} out. It is the counterpart of a Brian2 {@code PoissonInput}
 * with one source per target: on every step, each selected neuron independently receives the kick
 * with probability {@code rate * dt}.
 *
 * <p>The draws come from a {@link CounterRandom} whose counter is a step counter supplied by the
 * caller, typically the one a {@code SimulationRunner} advances, so every step draws new values
 * with no state of this block's own to advance. Values are a pure function of the seed, the step
 * and the neuron, so a run is reproducible on every backend, and {@link #setSeed(int)} moves to
 * an independent stream, such as the next trial of an experiment.</p>
 */
public class PoissonInput implements Block {

	/** The number of neurons the drive passes through. */
	private final int count;

	/** The probability of a kick to a selected neuron on one step, {@code rate * dt}. */
	private final double probability;

	/** The size of one kick. */
	private final double amplitude;

	/** 1 for each neuron that receives the input and 0 elsewhere. */
	private final PackedCollection targets;

	/** The seed of the draws, a single value. */
	private final PackedCollection seed;

	/** Uniform draws in {@code [0, 1)}, one per neuron, for the current step. */
	private final CounterRandom uniform;

	/** The forward cell: the drive plus this step's kicks. */
	private final Cell<PackedCollection> forward;

	/** The backward cell, which passes the gradient through unchanged. */
	private final Cell<PackedCollection> backward;

	/**
	 * Creates the input.
	 *
	 * @param rate      the rate of each spike train
	 * @param dt        the duration of one step, in the reciprocal unit of the rate
	 * @param amplitude the size of one kick
	 * @param targets   1 for each neuron that receives the input and 0 elsewhere, which also
	 *                  sets the number of neurons; it belongs to the caller
	 * @param step      the single-value step counter the draws are indexed by
	 * @param seed      the initial seed, in {@code [0, }{@link CounterRandom#SEED_LIMIT}{@code )}
	 * @throws IllegalArgumentException if the rate is negative, the step duration is not positive,
	 *                                  the kick probability {@code rate * dt} exceeds one, or the
	 *                                  seed is out of range
	 */
	public PoissonInput(double rate, double dt, double amplitude, PackedCollection targets,
						PackedCollection step, int seed) {
		if (rate < 0) {
			throw new IllegalArgumentException("Rate must not be negative, got " + rate);
		} else if (dt <= 0) {
			throw new IllegalArgumentException("Step duration must be positive, got " + dt);
		} else if (rate * dt > 1.0) {
			throw new IllegalArgumentException("Rate " + rate + " over a step of " + dt
					+ " gives a kick probability of " + (rate * dt) + ", above one; a single Bernoulli"
					+ " kick per step needs rate * dt <= 1");
		}

		this.count = targets.getShape().getTotalSize();
		this.probability = rate * dt;
		this.amplitude = amplitude;
		this.targets = targets;
		this.seed = new PackedCollection(1);
		this.uniform = new CounterRandom(shape(count), false, this.seed, step);
		this.forward = Cell.of(this::step);
		this.backward = Cell.of((in, next) -> next == null ? new OperationList() : next.push(in));
		setSeed(seed);
	}

	/**
	 * Moves the draws to the independent stream of another seed.
	 *
	 * @param seed the seed, in {@code [0, }{@link CounterRandom#SEED_LIMIT}{@code )}
	 * @throws IllegalArgumentException if the seed is out of range
	 */
	public void setSeed(int seed) {
		if (seed < 0 || seed >= CounterRandom.SEED_LIMIT) {
			throw new IllegalArgumentException("Seed " + seed + " is outside [0, "
					+ CounterRandom.SEED_LIMIT + ")");
		}

		this.seed.fill(seed);
	}

	/**
	 * Builds one step: the drive plus a kick for every selected neuron whose draw falls below the
	 * probability of a spike.
	 *
	 * @param input producer of the {@code [count]} drive
	 * @param next  the receptor the result is pushed to, or {@code null} for none
	 * @return the push of the result, or an empty list without a receptor
	 */
	protected Supplier<Runnable> step(Producer<PackedCollection> input, Receptor<PackedCollection> next) {
		if (next == null) return new OperationList("poisson-input");
		return next.push(c(input).reshape(shape(count)).add(kicks()));
	}

	/**
	 * Returns this step's kicks: the amplitude for every selected neuron whose draw is below the
	 * probability of a spike, and zero elsewhere.
	 *
	 * @return a producer of the {@code [count]} kicks
	 */
	public CollectionProducer kicks() {
		return lessThan(uniform, c(probability), c(amplitude), c(0.0)).multiply(cp(targets));
	}

	/**
	 * Returns the input shape, {@code [count]}.
	 *
	 * @return the input shape, {@code [count]}
	 */
	@Override
	public TraversalPolicy getInputShape() { return shape(count); }

	/**
	 * Returns the output shape, {@code [count]}.
	 *
	 * @return the output shape, {@code [count]}
	 */
	@Override
	public TraversalPolicy getOutputShape() { return shape(count); }

	/**
	 * Returns the cell that adds this step's kicks to the drive.
	 *
	 * @return the cell that adds this step's kicks to the drive
	 */
	@Override
	public Cell<PackedCollection> getForward() { return forward; }

	/**
	 * Returns the backward cell, which passes the gradient through.
	 *
	 * @return the backward cell, which passes the gradient through
	 */
	@Override
	public Cell<PackedCollection> getBackward() { return backward; }

	/**
	 * Returns no setup: the draws depend only on the seed and the step counter.
	 *
	 * @return no setup: the draws depend only on the seed and the step counter
	 */
	@Override
	public Supplier<Runnable> setup() { return new OperationList("poisson-input-setup"); }

	/** Releases the seed and the generator. The targets and the step counter belong to the caller. */
	@Override
	public void destroy() {
		uniform.destroy();
		seed.destroy();
	}
}
