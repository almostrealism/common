/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.time;

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.cycle.Setup;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.computations.FourierTransform;
import org.almostrealism.time.computations.Interpolate;
import org.almostrealism.time.computations.MultiOrderFilter;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface TemporalFeatures extends GeometryFeatures {
	boolean enableFlatSetup = true;

	default Frequency bpm(double bpm) {
		return Frequency.forBPM(bpm);
	}

	default Supplier<Runnable> iter(Temporal t, int iter) {
		return iter(t, iter, true);
	}

	default Supplier<Runnable> iter(Temporal t, int iter, boolean resetAfter) {
		return iter(t, v -> loop(v, iter), resetAfter);
	}

	default Supplier<Runnable> iter(Temporal t, Function<Temporal, Supplier<Runnable>> tick, boolean resetAfter) {
		Supplier<Runnable> tk = tick.apply(t);

		if (t instanceof Lifecycle || t instanceof Setup) {
			OperationList o = new OperationList("TemporalFeature Iteration");
			if (t instanceof Setup) {
				Supplier<Runnable> setup = ((Setup) t).setup();

				if (enableFlatSetup && setup instanceof OperationList) {
					o.addAll((OperationList) setup);
				} else {
					o.add(setup);
				}
			}

			o.add(tk);

			if (resetAfter && t instanceof Lifecycle) o.add(() -> ((Lifecycle) t)::reset);
			return o;
		} else {
			return tk;
		}
	}

	default Supplier<Runnable> loop(Temporal t, int iter) {
		return HardwareFeatures.getInstance().loop(t.tick(), iter);
	}
	
	default Interpolate interpolate(Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position,
									Producer<PackedCollection<?>> rate) {
		return new Interpolate(series, position, rate);
	}

	default Interpolate interpolate(
									Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position,
									Producer<PackedCollection<?>> rate,
									Function<Expression, Expression> timeForIndex,
									Function<Expression, Expression> indexForTime) {
		return new Interpolate(series, position, rate, timeForIndex, indexForTime);
	}

	default FourierTransform fft(int bins, Producer<PackedCollection<?>> input, ComputeRequirement... requirements) {
		FourierTransform fft = new FourierTransform(bins, input);
		if (requirements.length > 0) fft.setComputeRequirements(List.of(requirements));
		return fft;
	}

	default CollectionProducer<PackedCollection<?>> lowPassCoefficients(
			Producer<PackedCollection<?>> cutoff,
			int sampleRate, int filterOrder) {
		CollectionProducer<PackedCollection<?>> normalizedCutoff =
				c(2).multiply(cutoff).divide(sampleRate);

		int center = filterOrder / 2;
		CollectionProducer<PackedCollection<?>> index =
				c(IntStream.range(0, filterOrder + 1).mapToDouble(i -> i).toArray());
		CollectionProducer<PackedCollection<?>> k = index.subtract(c(center)).multiply(c(PI));
		k = k.repeat(shape(cutoff).getSize());

		normalizedCutoff = normalizedCutoff.traverse(1).repeat(shape(index).getSize());

		CollectionProducer<PackedCollection<?>> coeff =
				sin(k.multiply(normalizedCutoff)).divide(k);
		coeff = equals(index, c(center), normalizedCutoff, coeff);

		CollectionProducer<PackedCollection<?>> alt =
				c(0.54).subtract(c(0.46)
						.multiply(cos(c(2).multiply(PI).multiply(index).divide(filterOrder))));
		return coeff.multiply(alt).consolidate();
	}

	default CollectionProducer<PackedCollection<?>> highPassCoefficients(
			Producer<PackedCollection<?>> cutoff,
			int sampleRate, int filterOrder) {
		int center = filterOrder / 2;
		CollectionProducer<PackedCollection<?>> index =
				c(IntStream.range(0, filterOrder + 1).mapToDouble(i -> i).toArray());
		return equals(index, c(center), c(1.0), c(0.0))
				.subtract(lowPassCoefficients(cutoff, sampleRate, filterOrder));
	}

	default MultiOrderFilter lowPass(Producer<PackedCollection<?>> series,
									  Producer<PackedCollection<?>> cutoff,
									  int sampleRate) {
		return lowPass(series, cutoff, sampleRate, 40);
	}

	default MultiOrderFilter lowPass(Producer<PackedCollection<?>> series,
									 Producer<PackedCollection<?>> cutoff,
									 int sampleRate, int order) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return MultiOrderFilter.create(series, lowPassCoefficients(cutoff, sampleRate, order));
	}

	default MultiOrderFilter highPass(Producer<PackedCollection<?>> series,
									  Producer<PackedCollection<?>> cutoff,
									  int sampleRate) {
		return highPass(series, cutoff, sampleRate, 40);
	}

	default MultiOrderFilter highPass(Producer<PackedCollection<?>> series,
									  Producer<PackedCollection<?>> cutoff,
									  int sampleRate, int order) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return MultiOrderFilter.create(series, highPassCoefficients(cutoff, sampleRate, order));
	}
}
