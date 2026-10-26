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

package org.almostrealism.graph;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.time.Temporal;

import java.util.List;
import java.util.function.Supplier;

/**
 * A population of firing-rate units coupled through a dense, signed weight matrix, whose time
 * step is expressed entirely as {@link CollectionProducer} math over device-resident state.
 *
 * <p>The rates {@code r} follow</p>
 * <pre>
 *   tau * dr/dt = -r + f(g * s (.) (W r) + input)
 * </pre>
 * <p>where {@code W} is the {@code [n, n]} coupling matrix (row = target unit, column = source
 * unit, so the input to unit {@code i} is row {@code i} of {@code W} times the rates), {@code s}
 * an optional per-row scale of {@code n} values, {@code (.)} the element-wise product, {@code g}
 * a global gain and {@code input} an external drive the caller (or a boundary input of the
 * simulation loop owner) writes. Applying the row scale after the product is the same as
 * {@code diag(s) W r}, costs {@code n} multiplications per step, and never copies {@code W}.</p>
 *
 * <h2>Activation</h2>
 * <p>The default activation is a saturating rectifier,
 * {@code f(x) = rMax * tanh(max(x, 0) / rMax)}, whose values lie in {@code [0, rMax)}. A subclass
 * models another activation by overriding {@link #activate(CollectionProducer)}; the bound
 * described below holds for any activation whose values lie in {@code [0, rMax]}.</p>
 *
 * <h2>Integration</h2>
 * <p>One step is exponential Euler with the input to {@code f} held for the step: with
 * {@code decay = exp(-dt / tau)},</p>
 * <pre>
 *   r' = decay * r + (1 - decay) * f(...)
 * </pre>
 * <p>{@code r'} is a convex combination of {@code r} and a value of {@code f}, so with a bounded
 * activation and initial rates in {@code [0, rMax]} the rates stay in {@code [0, rMax]} for any
 * step size and any {@code W}. Boundedness is a property of the update, not of a well-chosen
 * gain; whether the rates settle rather than move within the bounds still depends on
 * {@code g * W}.</p>
 *
 * <h2>The step</h2>
 * <p>{@link #tick()} is one {@link OperationList} of two operations, following the step contract
 * of the simulation loop owner {@code SimulationRunner}: the update reads every unit's rate
 * through {@code W r}, so it writes a next-state collection, and a later operation copies that
 * back over the rates. The population owns no loop; a {@code SimulationRunner} steps it.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>The population owns its rates, next-state and input collections and releases them in
 * {@link #destroy()}. It borrows the weight matrix and the row scale; the caller manages their
 * lifetime and must keep them alive while a compiled step that reads them is in use.</p>
 *
 * <p>It is a stateful system rather than a single-signal graph element, so it is neither a
 * {@link org.almostrealism.graph.Cell} nor a block.</p>
 *
 * @author Michael Murray
 */
public class RatePopulation implements Temporal, LayerFeatures, Destroyable {

	/** The number of units. */
	private final int count;

	/** The coupling matrix, {@code [count, count]}, borrowed. */
	private final PackedCollection weights;

	/** The per-row scale of {@code count} values, borrowed, or {@code null} for none. */
	private final Producer<PackedCollection> rowScale;

	/** The global gain applied to the coupling input. */
	private final double gain;

	/** The per-step decay factor {@code exp(-dt / tau)}. */
	private final double decay;

	/** The maximum rate, the upper bound of the activation. */
	private final double rMax;

	/** The rate of each unit, {@code [count]}. */
	private final PackedCollection rates;

	/** The next-state buffer the update writes before it is copied back, {@code [count]}. */
	private final PackedCollection next;

	/** The external drive of each unit, {@code [count]}. */
	private final PackedCollection input;

	/**
	 * Creates a population. Its activation is the saturating rectifier
	 * {@code f(x) = rMax * tanh(max(x, 0) / rMax)} unless a subclass overrides
	 * {@link #activate(CollectionProducer)}.
	 *
	 * @param weights  the coupling matrix, {@code [n, n]}, row = target, column = source
	 * @param rowScale a producer of the per-row scale, {@code n} values, or {@code null} for none
	 * @param gain     the global gain
	 * @param dt       the time step
	 * @param tau      the rate time constant
	 * @param rMax     the maximum rate, the upper bound of the activation
	 * @throws IllegalArgumentException if {@code weights} is not square, the row scale has the
	 *                                  wrong size, or a time constant or {@code rMax} is not
	 *                                  positive
	 */
	public RatePopulation(PackedCollection weights, Producer<PackedCollection> rowScale,
						  double gain, double dt, double tau, double rMax) {
		TraversalPolicy shape = weights.getShape();
		if (shape.getDimensions() != 2 || shape.length(0) != shape.length(1)) {
			throw new IllegalArgumentException("weights must be a square matrix, not " + shape);
		} else if (!(dt > 0.0) || !(tau > 0.0)) {
			throw new IllegalArgumentException("dt and tau must be positive");
		} else if (!(rMax > 0.0)) {
			throw new IllegalArgumentException("rMax must be positive");
		}

		this.count = shape.length(0);
		if (rowScale != null && shape(rowScale).getTotalSizeLong() != count) {
			throw new IllegalArgumentException("rowScale must hold one value per unit (" + count + ")");
		}

		this.weights = weights;
		this.rowScale = rowScale;
		this.gain = gain;
		this.decay = Math.exp(-dt / tau);
		this.rMax = rMax;
		this.rates = new PackedCollection(shape(count));
		this.next = new PackedCollection(shape(count));
		this.input = new PackedCollection(shape(count));
	}

	/**
	 * Applies the activation to the total input of each unit. The default is the saturating
	 * rectifier {@code rMax * tanh(max(x, 0) / rMax)}, whose values lie in {@code [0, rMax)}. A
	 * subclass that models a different activation overrides this method; the bound on the rates
	 * holds when its values lie in {@code [0, }{@link #getMaxRate()}{@code ]}.
	 *
	 * @param x a producer of the total input of each unit
	 * @return a producer of the activated values
	 */
	protected CollectionProducer activate(CollectionProducer x) {
		return tanh(rectify(x).divide(rMax)).multiply(rMax);
	}

	/**
	 * Returns the coupling input of every unit, {@code s (.) (W r)}, or {@code W r} when there is
	 * no row scale.
	 *
	 * @return a producer of the {@code [count]} coupling input
	 */
	public CollectionProducer couplingInput() {
		CollectionProducer product = matmul(cp(weights), cp(rates)).reshape(shape(count));
		return rowScale == null ? product : multiply(rowScale, product);
	}

	/**
	 * Returns one step of the dynamics: the exponential-Euler update into the next-state buffer,
	 * then the copy back over the rates.
	 *
	 * @return the two operations of one step
	 */
	@Override
	public Supplier<Runnable> tick() {
		CollectionProducer target = activate(couplingInput().multiply(gain).add(cp(input)));

		OperationList tick = new OperationList("Rate Population Step");
		tick.add(a("rateUpdate", cp(next), cp(rates).multiply(decay).add(target.multiply(1.0 - decay))));
		tick.add(a("rateCopyBack", cp(rates), cp(next)));
		return tick;
	}

	/** Returns the number of units. */
	public int getCount() { return count; }

	/** Returns the per-step decay factor {@code exp(-dt / tau)}. */
	public double getDecay() { return decay; }

	/** Returns the maximum rate, the upper bound of the activation. */
	public double getMaxRate() { return rMax; }

	/** Returns the global gain. */
	public double getGain() { return gain; }

	/** Returns the borrowed coupling matrix. */
	public PackedCollection getWeights() { return weights; }

	/**
	 * Returns the rate of each unit, {@code [count]}. The caller may write an initial state into
	 * it before a simulation starts.
	 *
	 * @return the rates
	 */
	public PackedCollection getRates() { return rates; }

	/**
	 * Returns the external drive of each unit, {@code [count]}, initially zero. It is read by
	 * every step, so writing it (directly before a run, or through a boundary input) changes the
	 * drive from the next step on.
	 *
	 * @return the input collection
	 */
	public PackedCollection getInput() { return input; }

	/** Releases the rates, next-state and input collections. */
	@Override
	public void destroy() {
		Destroyable.releaseAll(List.of(rates::destroy, next::destroy, input::destroy));
	}
}
