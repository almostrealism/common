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

package org.almostrealism.studio.arrange;

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedChromosome;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Computes time-varying automation values driven by a {@link TimeCell} clock and
 * chromosome genes. Provides periodic, long-period, and short-period modulation
 * components that can be aggregated into a single automation signal.
 */
public class AutomationManager implements Setup, CellFeatures {
	/** Number of gene loci per automation gene (phase and magnitude for each period tier). */
	public static final int GENE_LENGTH = 6;

	/** Clock cell providing the current playback frame position. */
	private final TimeCell clock;

	/** Supplier that returns the current measure duration in seconds. */
	private final DoubleSupplier measureDuration;

	/** Audio sample rate used for time-to-frame conversion. */
	private final int sampleRate;

	/** GPU-resident collection holding the current measure duration (updated each setup). */
	private final PackedCollection scale;

	/** Amplitude scaling factor applied to automation waveforms. */
	private final double r = 1.0;

	/** Phase scaling factor applied to automation computations. */
	private final double p = 0.5;

	/**
	 * Creates an automation manager.
	 *
	 * @param chromosome      the chromosome supplying automation gene values
	 * @param clock           the time cell providing the playback frame
	 * @param measureDuration supplier returning the current measure duration in seconds
	 * @param sampleRate      audio sample rate
	 */
	public AutomationManager(ProjectedChromosome chromosome, TimeCell clock,
							 DoubleSupplier measureDuration, int sampleRate) {
		this.clock = clock;
		this.measureDuration = measureDuration;
		this.sampleRate = sampleRate;
		this.scale = PackedCollection.factory().apply(1);
	}

	/**
	 * Computes a normalised time value by combining an absolute position with a
	 * phase offset derived from the given phase gene value.
	 *
	 * @param position the absolute normalised position producer
	 * @param phase    the phase gene value in [0, 1]
	 * @return the combined normalised time producer
	 */
	protected Producer<PackedCollection> time(Producer<PackedCollection> position, Producer<PackedCollection> phase) {
		phase = c(2.0).multiply(phase).subtract(c(1.0)).multiply(c(p));
		return add(position, phase);
	}

	/**
	 * Computes a normalised time value from the current clock position and a phase offset.
	 *
	 * @param phase the phase gene value in [0, 1]
	 * @return the combined normalised time producer
	 */
	protected Producer<PackedCollection> time(Producer<PackedCollection> phase) {
		return time(divide(clock.time(sampleRate), cp(scale)), phase);
	}

	/**
	 * Returns an aggregated automation value for the current clock position, derived from
	 * the given gene, an optional amplitude scale, and a positional offset.
	 *
	 * @param gene   the automation gene providing phase and magnitude values
	 * @param scale  additional scale applied to magnitude components, or {@code null}
	 * @param offset positional offset applied to the main value component
	 * @return the aggregated automation producer
	 */
	public Producer<PackedCollection> getAggregatedValue(Gene<PackedCollection> gene,
															Producer<PackedCollection> scale,
															double offset) {
		return getAggregatedValue(
				gene.valueAt(0).getResultant(c(1.0)),
				gene.valueAt(1).getResultant(c(1.0)),
				gene.valueAt(2).getResultant(c(1.0)),
				applyScale(gene.valueAt(3).getResultant(c(1.0)), scale),
				applyScale(gene.valueAt(4).getResultant(c(1.0)), scale),
				applyScale(gene.valueAt(5).getResultant(c(1.0)), scale),
				c(offset));
	}

	/**
	 * Returns an aggregated automation value at the specified absolute position, derived from
	 * the given gene and a positional offset.
	 *
	 * @param position the absolute normalised position producer
	 * @param gene     the automation gene providing phase and magnitude values
	 * @param offset   positional offset applied to the main value component
	 * @return the aggregated automation producer
	 */
	public Producer<PackedCollection> getAggregatedValueAt(Producer<PackedCollection> position,
															  Gene<PackedCollection> gene, double offset) {
		return getAggregatedValueAt(
				position,
				gene.valueAt(0).getResultant(c(1.0)),
				gene.valueAt(1).getResultant(c(1.0)),
				gene.valueAt(2).getResultant(c(1.0)),
				gene.valueAt(3).getResultant(c(1.0)),
				gene.valueAt(4).getResultant(c(1.0)),
				gene.valueAt(5).getResultant(c(1.0)),
				c(offset));
	}

	/**
	 * Computes the aggregated automation value from explicit phase and magnitude components
	 * for the short-period, long-period, and main tiers.
	 *
	 * @param shortPeriodPhase     phase for the short-period oscillator
	 * @param longPeriodPhase      phase for the long-period oscillator
	 * @param mainPhase            phase for the main polynomial trend
	 * @param shortPeriodMagnitude magnitude for the short-period oscillator
	 * @param longPeriodMagnitude  magnitude for the long-period oscillator
	 * @param mainMagnitude        magnitude for the main polynomial trend
	 * @param offset               positional offset for the main trend
	 * @return the combined automation value producer
	 */
	public Producer<PackedCollection> getAggregatedValue(
			Producer<PackedCollection> shortPeriodPhase,
			Producer<PackedCollection> longPeriodPhase,
			Producer<PackedCollection> mainPhase,
			Producer<PackedCollection> shortPeriodMagnitude,
			Producer<PackedCollection> longPeriodMagnitude,
			Producer<PackedCollection> mainMagnitude,
			Producer<PackedCollection> offset) {
		Producer<PackedCollection> shortPeriod = applyMagnitude(shortPeriodMagnitude, getShortPeriodValue(shortPeriodPhase));
		Producer<PackedCollection> longPeriod = applyMagnitude(longPeriodMagnitude, getLongPeriodValue(longPeriodPhase));
		Producer<PackedCollection> main = multiply(mainMagnitude, getMainValue(mainPhase, offset));

		return multiply(main, multiply(shortPeriod, longPeriod));
	}

	/**
	 * Computes the aggregated automation value at a specific absolute position from
	 * explicit phase and magnitude components.
	 *
	 * @param position             the absolute normalised position producer
	 * @param shortPeriodPhase     phase for the short-period oscillator
	 * @param longPeriodPhase      phase for the long-period oscillator
	 * @param mainPhase            phase for the main polynomial trend
	 * @param shortPeriodMagnitude magnitude for the short-period oscillator
	 * @param longPeriodMagnitude  magnitude for the long-period oscillator
	 * @param mainMagnitude        magnitude for the main polynomial trend
	 * @param offset               positional offset for the main trend
	 * @return the combined automation value producer
	 */
	public Producer<PackedCollection> getAggregatedValueAt(
			Producer<PackedCollection> position,
			Producer<PackedCollection> shortPeriodPhase,
			Producer<PackedCollection> longPeriodPhase,
			Producer<PackedCollection> mainPhase,
			Producer<PackedCollection> shortPeriodMagnitude,
			Producer<PackedCollection> longPeriodMagnitude,
			Producer<PackedCollection> mainMagnitude,
			Producer<PackedCollection> offset) {
		Producer<PackedCollection> shortPeriod = applyMagnitude(shortPeriodMagnitude, getShortPeriodValueAt(position, shortPeriodPhase));
		Producer<PackedCollection> longPeriod = applyMagnitude(longPeriodMagnitude, getLongPeriodValueAt(position, longPeriodPhase));
		Producer<PackedCollection> main = multiply(mainMagnitude, getMainValueAt(position, mainPhase, offset));

		return multiply(main, multiply(shortPeriod, longPeriod));
	}

	/**
	 * Applies a magnitude blend to a value: {@code value * magnitude + (1 - magnitude)}.
	 * When magnitude is 0 the result is 1.0; when magnitude is 1 the result equals the value.
	 *
	 * @param magnitude blend weight in [0, 1]
	 * @param value     the oscillator value
	 * @return the blended result
	 */
	protected Producer<PackedCollection> applyMagnitude(Producer<PackedCollection> magnitude,
														   Producer<PackedCollection> value) {
		return multiply(value, magnitude).add(c(1.0).subtract(magnitude));
	}

	/**
	 * Optionally multiplies a value by a scale factor.
	 *
	 * @param value the value to scale
	 * @param scale the scale factor, or {@code null} to return the value unchanged
	 * @return the scaled value
	 */
	protected Producer<PackedCollection> applyScale(Producer<PackedCollection> value, Producer<PackedCollection> scale) {
		return scale == null ? value : multiply(value, scale);
	}

	/**
	 * Computes a periodic automation value with expression structure optimized for LICM.
	 * Factors the sin argument into invariant (angularRate, phaseContrib) and variant
	 * (clock frame) parts so the compiler can hoist invariant sub-expressions.
	 */
	private Producer<PackedCollection> computePeriodicValue(Producer<PackedCollection> phase, double freq) {
		Producer<PackedCollection> angularRate =
				c(freq).divide(c((double) sampleRate).multiply(cp(scale)));
		Producer<PackedCollection> phaseContrib =
				c(2.0).multiply(phase).subtract(c(1.0)).multiply(c(p * freq));
		return sin(add(multiply(clock.frame(), angularRate), phaseContrib));
	}

	/**
	 * Returns the main polynomial trend value at the current clock position.
	 *
	 * @param phase  phase gene value in [0, 1]
	 * @param offset positional offset applied to the trend
	 * @return the main trend value producer
	 */
	public Producer<PackedCollection> getMainValue(Producer<PackedCollection> phase, Producer<PackedCollection> offset) {
		Producer<PackedCollection> invRate =
				c(1.0).divide(c((double) sampleRate).multiply(cp(scale)));
		Producer<PackedCollection> phaseContrib =
				c(2.0).multiply(phase).subtract(c(1.0)).multiply(c(p));
		Producer<PackedCollection> v = multiply(c(0.1 * r),
				add(multiply(clock.frame(), invRate), phaseContrib).pow(c(3.0)));
		v = rectify(add(v, offset));
		return multiply(v, c(0.01));
	}

	/**
	 * Returns the main polynomial trend value at a specific normalised position.
	 *
	 * @param position normalised time position producer
	 * @param phase    phase gene value in [0, 1]
	 * @param offset   positional offset applied to the trend
	 * @return the main trend value producer
	 */
	public Producer<PackedCollection> getMainValueAt(Producer<PackedCollection> position,
														Producer<PackedCollection> phase,
														Producer<PackedCollection> offset) {
		return getMainValueAt(time(position, phase), offset);
	}

	/**
	 * Returns the main polynomial trend value for a precomputed time value.
	 *
	 * @param time   the normalised time producer
	 * @param offset positional offset applied to the trend
	 * @return the main trend value producer
	 */
	public Producer<PackedCollection> getMainValueAt(Producer<PackedCollection> time, Producer<PackedCollection> offset) {
		Producer<PackedCollection> v = multiply(c(0.1 * r), pow(time, c(3.0)));
		v = rectify(add(v, offset));
		return multiply(v, c(0.01));
	}

	/**
	 * Returns the long-period oscillator value at the current clock position.
	 *
	 * @param phase phase gene value in [0, 1]
	 * @return the long-period oscillator producer
	 */
	public Producer<PackedCollection> getLongPeriodValue(Producer<PackedCollection> phase) {
		return c(0.95).add(computePeriodicValue(phase, 2.0 * r)).multiply(c(0.05));
	}

	/**
	 * Returns the long-period oscillator value at a specific normalised position.
	 *
	 * @param position normalised time position producer
	 * @param phase    phase gene value in [0, 1]
	 * @return the long-period oscillator producer
	 */
	public Producer<PackedCollection> getLongPeriodValueAt(Producer<PackedCollection> position, Producer<PackedCollection> phase) {
		return getLongPeriodValueAt(time(position, phase));
	}

	/**
	 * Returns the long-period oscillator value for a precomputed time value.
	 *
	 * @param time the normalised time producer
	 * @return the long-period oscillator producer
	 */
	public Producer<PackedCollection> getLongPeriodValueAt(Producer<PackedCollection> time) {
		return c(0.95).add(sin(multiply(time, c(2.0 * r)))).multiply(c(0.05));
	}

	/**
	 * Returns the short-period oscillator value at the current clock position.
	 *
	 * @param phase phase gene value in [0, 1]
	 * @return the short-period oscillator producer
	 */
	public Producer<PackedCollection> getShortPeriodValue(Producer<PackedCollection> phase) {
		return c(1.0).add(multiply(computePeriodicValue(phase, 16.0 * r), c(-0.04)));
	}

	/**
	 * Returns the short-period oscillator value at a specific normalised position.
	 *
	 * @param position normalised time position producer
	 * @param phase    phase gene value in [0, 1]
	 * @return the short-period oscillator producer
	 */
	public Producer<PackedCollection> getShortPeriodValueAt(Producer<PackedCollection> position, Producer<PackedCollection> phase) {
		return getShortPeriodValueAt(time(position, phase));
	}

	/**
	 * Returns the short-period oscillator value for a precomputed time value.
	 *
	 * @param time the normalised time producer
	 * @return the short-period oscillator producer
	 */
	public Producer<PackedCollection> getShortPeriodValueAt(Producer<PackedCollection> time) {
		return c(1.0).add(sin(multiply(time, c(16.0 * r))).multiply(c(-0.04)));
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("AutomationManager Setup");
		setup.add(() -> () -> {
			scale.set(0, measureDuration.getAsDouble());
		});
		return setup;
	}
}
