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

package org.almostrealism.time;

import io.almostrealism.code.Computation;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.cycle.Setup;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.relation.Producer;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
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

/**
 * A feature interface providing convenient factory methods for creating temporal operations,
 * signal processing computations, and time-series manipulations.
 *
 * <p>{@link TemporalFeatures} extends {@link GeometryFeatures} to provide a fluent API for
 * working with time-based data, including FFT/IFFT, filtering, interpolation, and iteration
 * utilities. All methods create hardware-accelerated computations suitable for GPU execution.</p>
 *
 * <h2>Core Capabilities</h2>
 * <ul>
 *   <li><strong>Iteration:</strong> Execute temporal operations with setup/teardown ({@link #iter})</li>
 *   <li><strong>Interpolation:</strong> Time-series value queries with various mapping modes</li>
 *   <li><strong>FFT:</strong> Fast Fourier Transform and inverse transform</li>
 *   <li><strong>Filtering:</strong> Low-pass, high-pass, and custom FIR filters</li>
 *   <li><strong>Looping:</strong> Hardware-accelerated iteration with {@link Loop}</li>
 * </ul>
 *
 * <h2>Implementation</h2>
 * <p>Typically, classes implement this interface to gain access to temporal methods:</p>
 * <pre>{@code
 * public class MyProcessor implements TemporalFeatures {
 *     public void processAudio() {
 *         Producer<PackedCollection> signal = ...;
 *         Producer<PackedCollection> cutoff = c(1000.0);
 *
 *         // Use TemporalFeatures methods directly
 *         MultiOrderFilter lpf = lowPass(signal, cutoff, 44100);
 *         PackedCollection filtered = lpf.get().evaluate();
 *     }
 * }
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Temporal Iteration with Lifecycle</h3>
 * <pre>{@code
 * class AudioEngine implements TemporalFeatures {
 *     void run() {
 *         Temporal synth = createSynthesizer();
 *
 *         // Setup, iterate 1000 times, reset
 *         Supplier<Runnable> op = iter(synth, 1000, true);
 *         op.get().run();
 *     }
 * }
 * }</pre>
 *
 * <h3>Frequency Analysis Pipeline</h3>
 * <pre>{@code
 * class SpectrumAnalyzer implements TemporalFeatures {
 *     PackedCollection analyze(Producer<PackedCollection> audio) {
 *         // Convert to complex format
 *         Producer<PackedCollection> complex = ...;
 *
 *         // Compute FFT
 *         FourierTransform fft = fft(512, complex);
 *         return fft.get().evaluate();
 *     }
 * }
 * }</pre>
 *
 * <h3>Audio Filter Chain</h3>
 * <pre>{@code
 * class FilterChain implements TemporalFeatures {
 *     Producer<PackedCollection> process(Producer<PackedCollection> input) {
 *         // High-pass at 80Hz (remove rumble)
 *         Producer<PackedCollection> hp =
 *             highPass(input, c(80.0), 44100, 20).get();
 *
 *         // Low-pass at 12kHz (anti-aliasing)
 *         Producer<PackedCollection> lp =
 *             lowPass(hp, c(12000.0), 44100, 40).get();
 *
 *         return lp;
 *     }
 * }
 * }</pre>
 *
 * <h3>Time-Series Resampling</h3>
 * <pre>{@code
 * class Resampler implements TemporalFeatures {
 *     PackedCollection resample(AcceleratedTimeSeries series,
 *                                   double newRate) {
 *         Producer<PackedCollection> position = ...;
 *         Interpolate interp = interpolate(p(series), position, 44100.0);
 *         return interp.get().evaluate();
 *     }
 * }
 * }</pre>
 *
 * <h2>Method Categories</h2>
 *
 * <h3>Iteration &amp; Looping</h3>
 * <ul>
 *   <li>{@link #iter(Temporal, int)} - Execute temporal with iteration count</li>
 *   <li>{@link #loop(Temporal, int)} - Hardware-accelerated loop</li>
 *   <li>{@link #bpm(double)} - Create frequency from BPM</li>
 * </ul>
 *
 * <h3>Interpolation</h3>
 * <ul>
 *   <li>{@link #interpolate(Producer, Producer)} - Basic linear interpolation</li>
 *   <li>{@link #interpolate(Producer, Producer, Producer)} - With rate adjustment</li>
 *   <li>{@link #interpolate(Producer, Producer, double)} - With sample rate mapping</li>
 *   <li>{@link #interpolate(Producer, Producer, Producer, Function, Function)} - Custom mapping functions</li>
 * </ul>
 *
 * <h3>FFT &amp; Signal Analysis</h3>
 * <ul>
 *   <li>{@link #fft(int, Producer, ComputeRequirement...)} - Forward FFT</li>
 *   <li>{@link #ifft(int, Producer, ComputeRequirement...)} - Inverse FFT</li>
 * </ul>
 *
 * <h3>Filtering</h3>
 * <ul>
 *   <li>{@link #lowPass(Producer, Producer, int)} - Low-pass filter</li>
 *   <li>{@link #highPass(Producer, Producer, int)} - High-pass filter</li>
 *   <li>{@link #aggregate(Producer, Producer)} - Custom FIR filter</li>
 *   <li>{@link #lowPassCoefficients(Producer, int, int)} - Generate LP coefficients</li>
 *   <li>{@link #highPassCoefficients(Producer, int, int)} - Generate HP coefficients</li>
 * </ul>
 *
 * <h2>Hardware Acceleration</h2>
 * <p>All operations created by TemporalFeatures methods can execute on GPU/accelerator
 * hardware when compiled. The framework automatically handles:</p>
 * <ul>
 *   <li>Kernel generation and compilation</li>
 *   <li>Memory transfer between CPU and GPU</li>
 *   <li>Operation graph optimization</li>
 *   <li>Caching of compiled kernels</li>
 * </ul>
 *
 * @see Temporal
 * @see FourierTransform
 * @see MultiOrderFilter
 * @see Interpolate
 * @see GeometryFeatures
 *
 * @author Michael Murray
 */
public interface TemporalFeatures extends GeometryFeatures {
	/**
	 * When true, flattens setup operations in iteration (adds them directly to operation list).
	 * When false, wraps setup in a single operation.
	 */
	boolean enableFlatSetup = true;

	/**
	 * Creates a {@link Frequency} from beats per minute (BPM).
	 *
	 * @param bpm The tempo in beats per minute
	 * @return A Frequency instance representing the BPM
	 * @see Frequency#forBPM(double)
	 */
	default Frequency bpm(double bpm) {
		return Frequency.forBPM(bpm);
	}

	/**
	 * Executes a temporal operation for a specified number of iterations, with automatic reset.
	 *
	 * <p>Equivalent to {@code iter(t, iter, true)}.</p>
	 *
	 * @param t The temporal operation to execute
	 * @param iter Number of iterations
	 * @return A supplier producing the complete operation (setup + loop + reset)
	 */
	default Supplier<Runnable> iter(Temporal t, int iter) {
		return iter(t, iter, true);
	}

	/**
	 * Executes a temporal operation for a specified number of iterations.
	 *
	 * <p>If the temporal implements {@link Setup}, calls its setup before iteration.
	 * If resetAfter is true and temporal implements {@link Lifecycle}, calls reset after iteration.</p>
	 *
	 * @param t The temporal operation to execute
	 * @param iter Number of iterations
	 * @param resetAfter If true, reset the temporal after iteration (if Lifecycle)
	 * @return A supplier producing the complete operation
	 */
	default Supplier<Runnable> iter(Temporal t, int iter, boolean resetAfter) {
		return iter(t, v -> loop(v, iter), resetAfter);
	}

	/**
	 * Executes a temporal operation with custom tick function and optional reset.
	 *
	 * <p>This method provides maximum control over temporal execution, allowing custom
	 * iteration logic via the tick function.</p>
	 *
	 * @param t The temporal operation
	 * @param tick Function producing the tick operation from the temporal
	 * @param resetAfter If true, reset after execution (if Lifecycle)
	 * @return A supplier producing the complete operation
	 */
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

	/**
	 * Creates a hardware-accelerated loop that ticks a temporal operation multiple times.
	 *
	 * @param t The temporal operation to tick
	 * @param iter Number of iterations
	 * @return A supplier producing the looped operation
	 * @see Loop
	 */
	default Supplier<Runnable> loop(Temporal t, int iter) {
		return loop(t.tick(), iter);
	}

	/**
	 * Creates a hardware-accelerated loop for a computation.
	 *
	 * <p>If the supplier is a {@link Computation}, uses {@link Loop} for GPU execution.
	 * Otherwise, creates a simple Java loop.</p>
	 *
	 * @param c The computation to loop
	 * @param iterations Number of iterations
	 * @return A supplier producing the looped operation
	 */
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

	/**
	 * Creates a {@link TemporalScalar} producer by concatenating time and value.
	 *
	 * @param time Producer providing the timestamp
	 * @param value Producer providing the value
	 * @return A producer for the temporal scalar
	 */
	default CollectionProducer temporal(Producer<PackedCollection> time,
										Producer<PackedCollection> value) {
		return (CollectionProducer) concat(shape(2), time, value);
	}

	/**
	 * Creates a basic linear interpolation operation (rate = 1.0).
	 *
	 * @param series Producer providing the time-series data
	 * @param position Producer providing query positions
	 * @return An interpolation computation
	 * @see Interpolate
	 */
	default Interpolate interpolate(Producer<PackedCollection> series,
									Producer<PackedCollection> position) {
		return interpolate(series, position, c(1.0));
	}

	/**
	 * Creates a linear interpolation operation with playback rate adjustment.
	 *
	 * @param series Producer providing the time-series data
	 * @param position Producer providing query positions
	 * @param rate Producer providing the playback rate multiplier
	 * @return An interpolation computation
	 * @see Interpolate
	 */
	default Interpolate interpolate(Producer<PackedCollection> series,
									Producer<PackedCollection> position,
									Producer<PackedCollection> rate) {
		return new Interpolate(series, position, rate);
	}

	/**
	 * Creates interpolation with sample rate index/time mapping.
	 *
	 * <p>Maps between array indices and time values using the specified sample rate.</p>
	 *
	 * @param series Producer providing the time-series data
	 * @param time Producer providing query times
	 * @param sampleRate Sample rate for index/time conversion
	 * @return An interpolation computation
	 * @see Interpolate
	 */
	default Interpolate interpolate(Producer<PackedCollection> series,
									Producer<PackedCollection> time,
									double sampleRate) {
		return new Interpolate(series, time,
				v -> Product.of(v, e(1.0 / sampleRate)),
				v -> Product.of(v, e(sampleRate)));
	}

	/**
	 * Creates interpolation with sample rate mapping and playback rate.
	 *
	 * @param series Producer providing the time-series data
	 * @param time Producer providing query times
	 * @param rate Producer providing the playback rate multiplier
	 * @param sampleRate Sample rate for index/time conversion
	 * @return An interpolation computation
	 * @see Interpolate
	 */
	default Interpolate interpolate(Producer<PackedCollection> series,
									Producer<PackedCollection> time,
									Producer<PackedCollection> rate,
									double sampleRate) {
		return new Interpolate(series, time, rate,
				v -> Product.of(v, e(1.0 / sampleRate)),
				v -> Product.of(v, e(sampleRate)));
	}

	/**
	 * Creates interpolation with custom index/time mapping functions.
	 *
	 * <p>Provides full control over how array indices map to timestamps and vice versa.</p>
	 *
	 * @param series Producer providing the time-series data
	 * @param position Producer providing query positions
	 * @param rate Producer providing the playback rate (null for no rate adjustment)
	 * @param timeForIndex Function mapping array index to timestamp
	 * @param indexForTime Function mapping timestamp to array index
	 * @return An interpolation computation
	 * @see Interpolate
	 */
	default Interpolate interpolate(
									Producer<PackedCollection> series,
									Producer<PackedCollection> position,
									Producer<PackedCollection> rate,
									Function<Expression, Expression> timeForIndex,
									Function<Expression, Expression> indexForTime) {
		return new Interpolate(series, position, rate, timeForIndex, indexForTime);
	}

	/**
	 * Creates a multi-order FIR filter with custom coefficients.
	 *
	 * @param series Producer providing the input signal
	 * @param coefficients Producer providing filter coefficients
	 * @return A filter computation
	 * @see MultiOrderFilter
	 */
	default MultiOrderFilter aggregate(Producer<PackedCollection> series,
									   Producer<PackedCollection> coefficients) {
		return MultiOrderFilter.create(series, coefficients);
	}

	/**
	 * Creates a forward Fast Fourier Transform.
	 *
	 * @param bins Number of frequency bins (should be power of 2)
	 * @param input Producer providing complex input signal
	 * @param requirements Optional compute requirements
	 * @return An FFT computation
	 * @see FourierTransform
	 */
	default FourierTransform fft(int bins, Producer<PackedCollection> input,
								 ComputeRequirement... requirements) {
		return fft(bins, false, input, requirements);
	}

	/**
	 * Creates an Inverse Fast Fourier Transform.
	 *
	 * @param bins Number of frequency bins (should be power of 2)
	 * @param input Producer providing complex frequency-domain signal
	 * @param requirements Optional compute requirements
	 * @return An IFFT computation
	 * @see FourierTransform
	 */
	default FourierTransform ifft(int bins, Producer<PackedCollection> input,
								  ComputeRequirement... requirements) {
		return fft(bins, true, input, requirements);
	}

	/**
	 * Creates a Fast Fourier Transform with explicit forward/inverse specification.
	 *
	 * @param bins Number of frequency bins (should be power of 2)
	 * @param inverse If true, performs IFFT; if false, performs FFT
	 * @param input Producer providing input signal
	 * @param requirements Optional compute requirements
	 * @return An FFT/IFFT computation
	 * @see FourierTransform
	 */
	default FourierTransform fft(int bins, boolean inverse,
								 Producer<PackedCollection> input,
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

	/**
	 * Generates low-pass filter coefficients using windowed sinc design.
	 *
	 * <p>Creates a Hamming-windowed sinc filter for the specified cutoff frequency.</p>
	 *
	 * @param cutoff Producer providing cutoff frequency in Hz
	 * @param sampleRate Sample rate in Hz
	 * @param filterOrder Filter order (number of coefficients - 1)
	 * @return Producer providing filter coefficients
	 */
	default CollectionProducer lowPassCoefficients(
			Producer<PackedCollection> cutoff,
			int sampleRate, int filterOrder) {
		CollectionProducer normalizedCutoff =
				c(2).multiply(cutoff).divide(sampleRate);

		int center = filterOrder / 2;
		CollectionProducer index =
				c(IntStream.range(0, filterOrder + 1).mapToDouble(i -> i).toArray());
//		CollectionProducer<PackedCollection> index = integers(0, filterOrder + 1);
		CollectionProducer k = index.subtract(c(center)).multiply(c(PI));
		k = k.repeat(shape(cutoff).getSize());

		normalizedCutoff = normalizedCutoff.traverse(1).repeat(shape(index).getSize());

		CollectionProducer coeff =
				sin(k.multiply(normalizedCutoff)).divide(k);
		coeff = equals(index, c(center), normalizedCutoff, coeff);

		CollectionProducer alt =
				c(0.54).subtract(c(0.46)
						.multiply(cos(c(2).multiply(PI).multiply(index).divide(filterOrder))));
		return coeff.multiply(alt).consolidate();
	}

	/**
	 * Generates high-pass filter coefficients by spectral inversion of low-pass.
	 *
	 * @param cutoff Producer providing cutoff frequency in Hz
	 * @param sampleRate Sample rate in Hz
	 * @param filterOrder Filter order (number of coefficients - 1)
	 * @return Producer providing filter coefficients
	 */
	default CollectionProducer highPassCoefficients(
			Producer<PackedCollection> cutoff,
			int sampleRate, int filterOrder) {
		int center = filterOrder / 2;
		CollectionProducer index =
				c(IntStream.range(0, filterOrder + 1).mapToDouble(i -> i).toArray());
		return equals(index, c(center), c(1.0), c(0.0))
				.subtract(lowPassCoefficients(cutoff, sampleRate, filterOrder));
	}

	/**
	 * Creates a low-pass filter with default order (40).
	 *
	 * @param series Producer providing input signal
	 * @param cutoff Producer providing cutoff frequency in Hz
	 * @param sampleRate Sample rate in Hz
	 * @return A low-pass filter computation
	 * @see #lowPass(Producer, Producer, int, int)
	 */
	default MultiOrderFilter lowPass(Producer<PackedCollection> series,
									  Producer<PackedCollection> cutoff,
									  int sampleRate) {
		return lowPass(series, cutoff, sampleRate, 40);
	}

	/**
	 * Creates a low-pass filter with specified order.
	 *
	 * <p>Higher order provides sharper cutoff but more computation.</p>
	 *
	 * @param series Producer providing input signal
	 * @param cutoff Producer providing cutoff frequency in Hz
	 * @param sampleRate Sample rate in Hz
	 * @param order Filter order (affects steepness)
	 * @return A low-pass filter computation
	 */
	default MultiOrderFilter lowPass(Producer<PackedCollection> series,
									 Producer<PackedCollection> cutoff,
									 int sampleRate, int order) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return MultiOrderFilter.create(series, lowPassCoefficients(cutoff, sampleRate, order));
	}

	/**
	 * Creates a high-pass filter with default order (40).
	 *
	 * @param series Producer providing input signal
	 * @param cutoff Producer providing cutoff frequency in Hz
	 * @param sampleRate Sample rate in Hz
	 * @return A high-pass filter computation
	 * @see #highPass(Producer, Producer, int, int)
	 */
	default MultiOrderFilter highPass(Producer<PackedCollection> series,
									  Producer<PackedCollection> cutoff,
									  int sampleRate) {
		return highPass(series, cutoff, sampleRate, 40);
	}

	/**
	 * Creates a high-pass filter with specified order.
	 *
	 * <p>Higher order provides sharper cutoff but more computation.</p>
	 *
	 * @param series Producer providing input signal
	 * @param cutoff Producer providing cutoff frequency in Hz
	 * @param sampleRate Sample rate in Hz
	 * @param order Filter order (affects steepness)
	 * @return A high-pass filter computation
	 */
	default MultiOrderFilter highPass(Producer<PackedCollection> series,
									  Producer<PackedCollection> cutoff,
									  int sampleRate, int order) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return MultiOrderFilter.create(series, highPassCoefficients(cutoff, sampleRate, order));
	}
}
