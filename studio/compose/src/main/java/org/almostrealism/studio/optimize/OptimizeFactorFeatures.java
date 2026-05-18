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

package org.almostrealism.studio.optimize;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.HeredityFeatures;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGene;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Mixin interface providing encoding and decoding helpers for genetic factor parameters used
 * in audio scene optimization. Includes utilities for adjustment envelope chromosomes,
 * polycyclic modulation chromosomes, and various factor-to-value conversion methods for
 * timing, speed-up, slow-down, and repeat parameters.
 *
 * <p>Implementors gain default methods for constructing genes that express time-varying
 * audio parameter adjustments from the raw unit-range values stored in {@link ProjectedGenome}
 * chromosomes.</p>
 */
public interface OptimizeFactorFeatures extends HeredityFeatures, CodeFeatures {
	/** Number of gene slots per adjustment chromosome gene (two delay parameters, two polynomial params, one scale, one offset). */
	int ADJUSTMENT_CHROMOSOME_SIZE = 6;

	/** Number of gene slots per polycyclic modulation chromosome gene (two speed-up params, two slow-down params, two poly params). */
	int POLYCYCLIC_CHROMOSOME_SIZE = 6;

	/**
	 * Initializes adjustment envelope genes for the given number of channels on the
	 * supplied chromosome. Each gene encodes a time-polynomial envelope with delay,
	 * wavelength, exponent, scale, and offset parameters.
	 *
	 * @param channels    the number of channels (one gene per channel)
	 * @param chromosome  the projected chromosome to add genes to
	 * @return an ordered list of initialized {@link ProjectedGene} instances
	 */
	default List<ProjectedGene> initializeAdjustment(int channels, ProjectedChromosome chromosome) {
		return IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(ADJUSTMENT_CHROMOSOME_SIZE);
			g.setTransform(0, p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			g.setTransform(1, p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			g.setTransform(2, p -> oneToInfinity(p, 1.0).multiply(c(10.0)));
			g.setTransform(3, p -> oneToInfinity(p, 1.0).multiply(c(10.0)));
			g.setTransform(4, p -> p);
			g.setTransform(5, p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			return g;
		}).collect(Collectors.toList());
	}

	/**
	 * Initializes polycyclic modulation genes for the given number of channels on the
	 * supplied chromosome. Each gene encodes speed-up and slow-down sinusoidal modulation
	 * parameters alongside a polynomial speed-up component.
	 *
	 * @param channels    the number of channels (one gene per channel)
	 * @param chromosome  the projected chromosome to add genes to
	 * @return an ordered list of initialized {@link ProjectedGene} instances
	 */
	default List<ProjectedGene> initializePolycyclic(int channels, ProjectedChromosome chromosome) {
		return IntStream.range(0, channels).mapToObj(i -> {
			ProjectedGene g = chromosome.addGene(POLYCYCLIC_CHROMOSOME_SIZE);
			g.setTransform(0, p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			g.setTransform(1, p -> oneToInfinity(p, 0.5).multiply(c(10.0)));
			g.setTransform(2, p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			g.setTransform(3, p -> p);
			g.setTransform(4, p -> oneToInfinity(p, 3.0).multiply(c(60.0)));
			g.setTransform(5, p -> oneToInfinity(p, 1.0).multiply(c(10.0)));
			return g;
		}).collect(Collectors.toList());
	}

	/**
	 * Constructs a {@link Gene} that applies an adjustment envelope to audio samples using
	 * the given clock and chromosome index. No external scale factor is applied.
	 *
	 * @param clock      the time cell providing the current playback time
	 * @param sampleRate the audio sample rate in Hz
	 * @param chromosome the chromosome containing the adjustment parameters
	 * @param i          the gene index within the chromosome
	 * @return a {@link Gene} that applies the adjustment envelope
	 */
	default Gene<PackedCollection> toAdjustmentGene(TimeCell clock, int sampleRate,
													   Chromosome<PackedCollection> chromosome, int i) {
		return toAdjustmentGene(clock, sampleRate, null, chromosome, i);
	}

	/**
	 * Constructs a {@link Gene} that applies an adjustment envelope to audio samples using
	 * the given clock, an optional external scale factor, and the chromosome index.
	 *
	 * @param clock      the time cell providing the current playback time
	 * @param sampleRate the audio sample rate in Hz
	 * @param scale      an optional scale factor multiplied into the envelope amplitude; may be {@code null}
	 * @param chromosome the chromosome containing the adjustment parameters
	 * @param i          the gene index within the chromosome
	 * @return a {@link Gene} that applies the adjustment envelope
	 */
	default Gene<PackedCollection> toAdjustmentGene(TimeCell clock, int sampleRate, Producer<PackedCollection> scale,
													   Chromosome<PackedCollection> chromosome, int i) {
		return new Gene<>() {
			@Override
			public int length() { return 1; }

			@Override
			public Factor<PackedCollection> valueAt(int pos) {
				return in -> {
					Producer<PackedCollection> s = chromosome.valueAt(i, 4).getResultant(c(1.0));
					if (scale != null) s = multiply(s, scale);

					return multiply(adjustment(
							chromosome.valueAt(i, 0).getResultant(c(1.0)),
							chromosome.valueAt(i, 1).getResultant(c(1.0)),
							chromosome.valueAt(i, 2).getResultant(c(1.0)),
							chromosome.valueAt(i, 3).getResultant(c(1.0)),
							s,
							chromosome.valueAt(i, 5).getResultant(c(1.0)),
							clock.time(sampleRate), 0.0, 1.0, false), in);
				};
			}
		};
	}

	/**
	 * Constructs a {@link Gene} that applies a polycyclic modulation factor to audio samples
	 * using sinusoidal speed-up, slow-down, and polynomial speed-up components.
	 *
	 * @param clock      the time cell providing the current frame counter
	 * @param sampleRate the audio sample rate in Hz
	 * @param chromosome the chromosome containing the polycyclic modulation parameters
	 * @param i          the gene index within the chromosome
	 * @return a {@link Gene} that applies the polycyclic modulation
	 */
	default Gene<PackedCollection> toPolycyclicGene(TimeCell clock, int sampleRate, Chromosome<PackedCollection> chromosome, int i) {
		return new Gene<>() {
			@Override
			public int length() { return 1; }

			@Override
			public Factor<PackedCollection> valueAt(int pos) {
				return in ->
						multiply(polycyclic(
								chromosome.valueAt(i, 0).getResultant(c(1.0)),
								chromosome.valueAt(i, 1).getResultant(c(1.0)),
								chromosome.valueAt(i, 2).getResultant(c(1.0)),
								chromosome.valueAt(i, 3).getResultant(c(1.0)),
								chromosome.valueAt(i, 4).getResultant(c(1.0)),
								chromosome.valueAt(i, 5).getResultant(c(1.0)),
								clock.frame(), sampleRate), in);
			}
		};
	}

	/**
	 * Returns the unit-range gene factor value that encodes the given number of beats as a
	 * repeat rate.
	 *
	 * @param beats the repeat rate in beats
	 * @return the encoded factor value in the unit range
	 */
	default double factorForRepeat(double beats) {
		return ((Math.log(beats) / Math.log(2)) / 16) + 0.5;
	}

	/**
	 * Returns the unit-range factor encoding the given repeat speed-up duration in seconds.
	 *
	 * @param seconds the repeat speed-up duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForRepeatSpeedUpDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given delay duration in seconds.
	 *
	 * @param seconds the delay duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForDelay(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given polynomial exponent value.
	 *
	 * @param exp the polynomial exponent value
	 * @return the encoded factor value
	 */
	default double factorForExponent(double exp) {
		return invertOneToInfinity(exp, 10, 1);
	}

	/**
	 * Returns the unit-range factor encoding the given periodic adjustment duration in seconds.
	 *
	 * @param seconds the periodic adjustment cycle duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForPeriodicAdjustmentDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given polynomial adjustment duration in seconds.
	 *
	 * @param seconds the polynomial adjustment duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForPolyAdjustmentDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given polynomial adjustment exponent.
	 *
	 * @param exp the polynomial adjustment exponent
	 * @return the encoded factor value
	 */
	default double factorForPolyAdjustmentExponent(double exp) {
		return invertOneToInfinity(exp, 10, 1);
	}

	/**
	 * Returns the unit-range factor encoding the given adjustment initial value.
	 *
	 * @param value the initial value for the adjustment envelope
	 * @return the encoded factor value
	 */
	default double factorForAdjustmentInitial(double value) {
		return invertOneToInfinity(value, 10, 1);
	}

	/**
	 * Returns the unit-range factor encoding the given adjustment time offset in seconds.
	 *
	 * @param value the time offset in seconds before the adjustment begins
	 * @return the encoded factor value
	 */
	default double factorForAdjustmentOffset(double value) {
		return invertOneToInfinity(value, 60, 3);
	}


	/**
	 * Returns the unit-range factor encoding the given speed-up ramp duration in seconds.
	 *
	 * @param seconds the speed-up ramp duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForSpeedUpDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given speed-up percentage as a decimal.
	 *
	 * @param decimal the speed-up percentage expressed as a decimal (e.g., 0.1 for 10%)
	 * @return the encoded factor value
	 */
	default double factorForSpeedUpPercentage(double decimal) {
		return invertOneToInfinity(decimal, 10, 0.5);
	}

	/**
	 * Returns the unit-range factor encoding the given slow-down ramp duration in seconds.
	 *
	 * @param seconds the slow-down ramp duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForSlowDownDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given slow-down percentage as a decimal.
	 *
	 * @param decimal the slow-down percentage expressed as a decimal
	 * @return the encoded factor value (identity mapping for slow-down percentage)
	 */
	default double factorForSlowDownPercentage(double decimal) {
		return decimal;
	}

	/**
	 * Returns the unit-range factor encoding the given polynomial speed-up duration in seconds.
	 *
	 * @param seconds the polynomial speed-up wavelength duration in seconds
	 * @return the encoded factor value
	 */
	default double factorForPolySpeedUpDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	/**
	 * Returns the unit-range factor encoding the given polynomial speed-up exponent.
	 *
	 * @param exp the polynomial speed-up exponent value
	 * @return the encoded factor value
	 */
	default double factorForPolySpeedUpExponent(double exp) {
		return invertOneToInfinity(exp, 10, 1);
	}

	/**
	 * Computes a time-polynomial adjustment value that begins rising after an offset and follows
	 * a polynomial curve defined by the wavelength and exponent parameters. The result is clamped
	 * to the specified range.
	 *
	 * @param periodicWavelength the wavelength of the periodic component (not used directly here)
	 * @param polyWaveLength     the polynomial wavelength used as the base for the power function
	 * @param polyExp            the polynomial exponent controlling the curve shape
	 * @param initial            the initial value before the adjustment begins
	 * @param scale              the amplitude scale applied to the polynomial component
	 * @param offset             the time offset in samples before adjustment begins
	 * @param time               the current playback time in samples
	 * @param min                the minimum clamping bound
	 * @param max                the maximum clamping bound
	 * @param relative           when {@code true} the scale is multiplied by the initial value
	 * @return a producer yielding the clamped adjustment value at each sample
	 */
	default ProducerComputation<PackedCollection> adjustment(Producer<PackedCollection> periodicWavelength,
																Producer<PackedCollection> polyWaveLength,
																Producer<PackedCollection> polyExp,
																Producer<PackedCollection> initial,
																Producer<PackedCollection> scale,
																Producer<PackedCollection> offset,
																Producer<PackedCollection> time,
																double min,
																double max,
																boolean relative) {
		if (relative) scale = multiply(scale, initial);
		CollectionProducer pos = subtract(time, offset);
		return bound(pos.greaterThan(c(0.0),
						pow(polyWaveLength, c(-1.0))
								.multiply(pos).pow(polyExp)
								.multiply(scale).add(initial), initial),
				min, max);
	}

	/**
	 * Computes a polycyclic modulation factor with expression structure optimized for LICM.
	 * Factors sin and polynomial arguments so that invariant sub-expressions (angular rates,
	 * reciprocal wavelengths) are hoistable out of the sample loop.
	 *
	 * @param frame the raw frame counter (not divided by sampleRate)
	 * @param sampleRate the audio sample rate in Hz
	 */
	default CollectionProducer polycyclic(Producer<PackedCollection> speedUpWavelength,
										  Producer<PackedCollection> speedUpAmp,
										  Producer<PackedCollection> slowDownWavelength,
										  Producer<PackedCollection> slowDownAmp,
										  Producer<PackedCollection> polySpeedUpWaveLength,
										  Producer<PackedCollection> polySpeedUpExp,
										  Producer<PackedCollection> frame, int sampleRate) {
		CollectionProducer speedUpRate = c(TWO_PI / sampleRate).divide(speedUpWavelength);
		CollectionProducer slowDownRate = c(TWO_PI / sampleRate).divide(slowDownWavelength);
		CollectionProducer polyRate = c(1.0 / sampleRate).divide(polySpeedUpWaveLength);

		return c(1.0).add(sin(speedUpRate.multiply(frame)).multiply(speedUpAmp).pow(c(2.0)))
				.multiply(c(1.0).subtract(sin(slowDownRate.multiply(frame)).multiply(slowDownAmp).pow(c(2.0))))
				.multiply(c(1.0).add(polyRate.multiply(frame).pow(polySpeedUpExp)));
	}

	/**
	 * Computes a smooth rise-or-fall interpolation over the given time range. The direction
	 * (up or down), magnitude, plateau, and curve exponent are all controlled by the supplied
	 * producers.
	 *
	 * @param minValue the minimum possible output value
	 * @param maxValue the maximum possible output value
	 * @param minScale the minimum scale factor applied to the magnitude
	 * @param d        a direction selector producer (0 = fall from max, 1 = rise from min)
	 * @param m        the magnitude producer controlling how far the value travels
	 * @param p        the plateau producer controlling the mid-point offset
	 * @param e        the exponent producer controlling the curve shape
	 * @param time     the current playback position within the segment
	 * @param duration the total duration of the rise/fall segment
	 * @return a producer yielding the interpolated value at each sample
	 */
	default CollectionProducer riseFall(double minValue, double maxValue, double minScale,
										Producer<PackedCollection> d,
										Producer<PackedCollection> m,
										Producer<PackedCollection> p,
										Producer<PackedCollection> e,
										Producer<PackedCollection> time,
										Producer<PackedCollection> duration) {
		PackedCollection directionChoices = new PackedCollection(shape(2, 1).traverse(1));
		directionChoices.setMem(0, -1);
		directionChoices.setMem(1, 1);

		double sc = maxValue - minValue;

		CollectionProducer scale = c(sc);
		m = c(minScale).add(multiply(m, c(1.0 - minScale)));
		p = multiply(p, subtract(c(1.0), m));

		CollectionProducer downOrigin = c(maxValue).subtract(multiply(scale, p));
		CollectionProducer upOrigin = c(minValue).add(multiply(scale, p));

		CollectionProducer originChoices = concat(shape(2), downOrigin, upOrigin)
				.reshape(shape(2, 1).traverse(1));

		CollectionProducerComputation direction = choice(2, shape(1), d, p(directionChoices));

		CollectionProducer magnitude = multiply(scale, m);
		CollectionProducer start = choice(2, shape(1), d, originChoices);
		CollectionProducer end = multiply(direction, magnitude).add(start);

		CollectionProducer pos = pow(divide(time, duration), e);

		return add(start, multiply(end.subtract(start), pos));
	}

	/**
	 * Computes a duration adjustment factor that doubles or halves the playback speed
	 * at discrete intervals after a speed-up offset. The initial speed is determined by
	 * {@code rp} and is halved with each elapsed {@code speedUpDuration} interval past
	 * {@code speedUpOffset}.
	 *
	 * @param rp              the repeat parameter producer (unit range) encoding the initial speed factor
	 * @param speedUpDuration the duration of each speed-up interval in samples
	 * @param speedUpOffset   the offset in samples before speed-up begins
	 * @param time            the current playback time in samples
	 * @return a producer yielding the duration adjustment multiplier at each sample
	 */
	default CollectionProducer durationAdjustment(Producer<PackedCollection> rp,
												  Producer<PackedCollection> speedUpDuration,
												  Producer<PackedCollection> speedUpOffset,
												  Producer<PackedCollection> time) {
		CollectionProducer initial = pow(c(2.0), c(16).multiply(c(-0.5).add(rp)));

		Producer<PackedCollection> speedUp = max(c(0.0), subtract(time, speedUpOffset));
		speedUp = floor(divide(speedUp, speedUpDuration));
		return initial.divide(pow(c(2.0), speedUp));
	}
}
