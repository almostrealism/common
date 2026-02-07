/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.optimize;

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
import org.almostrealism.heredity.ScaleFactor;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface OptimizeFactorFeatures extends HeredityFeatures, CodeFeatures {
	int ADJUSTMENT_CHROMOSOME_SIZE = 6;
	int POLYCYCLIC_CHROMOSOME_SIZE = 6;

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

	default Gene<PackedCollection> toAdjustmentGene(TimeCell clock, int sampleRate,
													   Chromosome<PackedCollection> chromosome, int i) {
		return toAdjustmentGene(clock, sampleRate, null, chromosome, i);
	}

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
								clock.time(sampleRate)), in);
			}
		};
	}

	default double valueForFactor(Factor<PackedCollection> value) {
		if (value instanceof ScaleFactor) {
			return ((ScaleFactor) value).getScaleValue();
		} else {
			return value.getResultant(c(1.0)).get().evaluate().toDouble(0);
		}
	}

	default double valueForFactor(Factor<PackedCollection> value, double exp, double multiplier) {
		if (value instanceof ScaleFactor) {
			return oneToInfinity(((ScaleFactor) value).getScaleValue(), exp) * multiplier;
		} else {
			double v = value.getResultant(c(1.0)).get().evaluate().toDouble(0);
			return oneToInfinity(v, exp) * multiplier;
		}
	}

	default double[] repeatForFactor(Factor<PackedCollection> f) {
		double v = 16 * (valueForFactor(f) - 0.5);

		if (v == 0) {
			return new double[] { 1.0, 1.0 };
		} else if (v > 0) {
			return new double[] { Math.pow(2.0, v), 1.0 };
		} else if (v < 0) {
			return new double[] { 1.0, Math.pow(2.0, -v) };
		}

		return null;
	}

	default double factorForRepeat(double beats) {
		return ((Math.log(beats) / Math.log(2)) / 16) + 0.5;
	}

	default double factorForRepeatSpeedUpDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double repeatSpeedUpDurationForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 3, 60);
	}

	default double factorForDelay(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double delayForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 3, 60);
	}

	default double factorForExponent(double exp) {
		return invertOneToInfinity(exp, 10, 1);
	}

	default double factorForPeriodicAdjustmentDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double factorForPolyAdjustmentDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double factorForPolyAdjustmentExponent(double exp) {
		return invertOneToInfinity(exp, 10, 1);
	}

	default double factorForAdjustmentInitial(double value) {
		return invertOneToInfinity(value, 10, 1);
	}

	default double factorForAdjustmentOffset(double value) {
		return invertOneToInfinity(value, 60, 3);
	}


	default double factorForSpeedUpDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double speedUpDurationForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 3, 60);
	}

	default double factorForSpeedUpPercentage(double decimal) {
		return invertOneToInfinity(decimal, 10, 0.5);
	}

	default double speedUpPercentageForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 0.5, 10);
	}

	default double factorForSlowDownDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double slowDownDurationForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 3, 60);
	}

	default double factorForSlowDownPercentage(double decimal) {
		return decimal;
	}

	default double slowDownPercentageForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f);
	}

	default double factorForPolySpeedUpDuration(double seconds) {
		return invertOneToInfinity(seconds, 60, 3);
	}

	default double polySpeedUpDurationForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 3, 60);
	}

	default double factorForPolySpeedUpExponent(double exp) {
		return invertOneToInfinity(exp, 10, 1);
	}

	default double polySpeedUpExponentForFactor(Factor<PackedCollection> f) {
		return valueForFactor(f, 1, 10);
	}

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
		CollectionProducer periodicAmp = c(1.0);

		if (relative) scale = multiply(scale, initial);
		CollectionProducer pos = subtract(time, offset);
		return bound(pos.greaterThan(c(0.0),
						pow(polyWaveLength, c(-1.0))
								.multiply(pos).pow(polyExp)
								.multiply(scale).add(initial), initial),
				min, max);
	}

	default CollectionProducer polycyclic(Producer<PackedCollection> speedUpWavelength,
										  Producer<PackedCollection> speedUpAmp,
										  Producer<PackedCollection> slowDownWavelength,
										  Producer<PackedCollection> slowDownAmp,
										  Producer<PackedCollection> polySpeedUpWaveLength,
										  Producer<PackedCollection> polySpeedUpExp,
										  Producer<PackedCollection> time) {
		return c(1.0).add(sinw(time, speedUpWavelength, speedUpAmp).pow(c(2.0)))
				.multiply(c(1.0).subtract(sinw(time, slowDownWavelength, slowDownAmp).pow(c(2.0))))
				.multiply(c(1.0).add(pow(polySpeedUpWaveLength, c(-1.0))
						.multiply(time).pow(polySpeedUpExp)));
	}

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
