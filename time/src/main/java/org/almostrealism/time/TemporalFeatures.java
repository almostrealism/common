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

import io.almostrealism.code.Computation;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.cycle.Setup;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Loop;
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
		return loop(t.tick(), iter);
	}

	default Supplier<Runnable> loop(Supplier<Runnable> c, int iterations) {
		if (!(c instanceof Computation) || (c instanceof OperationList && !((OperationList) c).isComputation())) {
			return () -> {
				Runnable r = c.get();
				return () -> IntStream.range(0, iterations).forEach(i -> r.run());
			};
		} else {
			return new Loop((Computation) c, iterations);
		}
	}

	default CollectionProducer<TemporalScalar> temporal(Supplier<Evaluable<? extends Scalar>> time,
														Supplier<Evaluable<? extends Scalar>> value) {
		return new ExpressionComputation<>(
				List.of(args -> args.get(1).getValueRelative(0), args -> args.get(2).getValueRelative(0)),
				(Supplier) time, (Supplier) value)
				.setPostprocessor(TemporalScalar.postprocessor());
	}

	default Interpolate interpolate(Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position) {
		return interpolate(series, position, c(1.0));
	}
	
	default Interpolate interpolate(Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position,
									Producer<PackedCollection<?>> rate) {
		return new Interpolate(series, position, rate);
	}

	default Interpolate interpolate(Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> time,
									double sampleRate) {
		return new Interpolate(series, time,
				v -> Product.of(v, e(1.0 / sampleRate)),
				v -> Product.of(v, e(sampleRate)));
	}

	default Interpolate interpolate(Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> time,
									Producer<PackedCollection<?>> rate,
									double sampleRate) {
		return new Interpolate(series, time, rate,
				v -> Product.of(v, e(1.0 / sampleRate)),
				v -> Product.of(v, e(sampleRate)));
	}

	default Interpolate interpolate(
									Producer<PackedCollection<?>> series,
									Producer<PackedCollection<?>> position,
									Producer<PackedCollection<?>> rate,
									Function<Expression, Expression> timeForIndex,
									Function<Expression, Expression> indexForTime) {
		return new Interpolate(series, position, rate, timeForIndex, indexForTime);
	}

	default MultiOrderFilter aggregate(Producer<PackedCollection<?>> series,
									   Producer<PackedCollection<?>> coefficients) {
		return MultiOrderFilter.create(series, coefficients);
	}

	default FourierTransform fft(int bins, Producer<PackedCollection<?>> input,
								 ComputeRequirement... requirements) {
		return fft(bins, false, input, requirements);
	}

	default FourierTransform ifft(int bins, Producer<PackedCollection<?>> input,
								  ComputeRequirement... requirements) {
		return fft(bins, true, input, requirements);
	}

	default FourierTransform fft(int bins, boolean inverse,
								 Producer<PackedCollection<?>> input,
								 ComputeRequirement... requirements) {
		TraversalPolicy shape = shape(input);

		int targetAxis = shape.getDimensions() - 2;

		if (shape.getDimensions() > 1 && shape.getTraversalAxis() != targetAxis) {
			input = traverse(targetAxis, input);
		}

		int count = shape(input).getCount();

		if (count > 1 && shape.getDimensions() < 3) {
			throw new IllegalArgumentException();
		}

		FourierTransform fft = new FourierTransform(count, bins, inverse, input);
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
//		CollectionProducer<PackedCollection<?>> index = integers(0, filterOrder + 1);
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
