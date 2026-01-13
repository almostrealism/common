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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.cycle.Setup;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.time.computations.FourierTransform;
import org.almostrealism.time.computations.Interpolate;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.time.computations.FFTConvolution;
import org.almostrealism.time.computations.MelFilterBank;
import org.almostrealism.time.computations.STFTComputation;
import org.almostrealism.time.computations.WindowComputation;

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
		return concat(shape(2), time, value);
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
//		CollectionProducer index = integers(0, filterOrder + 1);
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

	// ==================== Window Functions ====================

	/**
	 * Creates a Hann (Hanning) window of the specified size.
	 *
	 * <p>The Hann window is a good general-purpose window with moderate frequency
	 * resolution and low spectral leakage. Formula: w[n] = 0.5 * (1 - cos(2*PI * n / (N-1)))</p>
	 *
	 * @param size the window size (number of coefficients)
	 * @return a WindowComputation that generates Hann window coefficients
	 * @see WindowComputation
	 */
	default WindowComputation hannWindow(int size) {
		return WindowComputation.hann(size);
	}

	/**
	 * Creates a Hamming window of the specified size.
	 *
	 * <p>The Hamming window has slightly better side lobe suppression than Hann
	 * but doesn't reach zero at boundaries. Formula: w[n] = 0.54 - 0.46 * cos(2*PI * n / (N-1))</p>
	 *
	 * @param size the window size (number of coefficients)
	 * @return a WindowComputation that generates Hamming window coefficients
	 * @see WindowComputation
	 */
	default WindowComputation hammingWindow(int size) {
		return WindowComputation.hamming(size);
	}

	/**
	 * Creates a Blackman window of the specified size.
	 *
	 * <p>The Blackman window has excellent side lobe suppression (-58 dB) but
	 * a wider main lobe than Hann/Hamming. Good for high dynamic range signals.</p>
	 *
	 * @param size the window size (number of coefficients)
	 * @return a WindowComputation that generates Blackman window coefficients
	 * @see WindowComputation
	 */
	default WindowComputation blackmanWindow(int size) {
		return WindowComputation.blackman(size);
	}

	/**
	 * Creates a Bartlett (triangular) window of the specified size.
	 *
	 * <p>The Bartlett window is a simple linear taper with lower side lobe
	 * suppression but computationally simple.</p>
	 *
	 * @param size the window size (number of coefficients)
	 * @return a WindowComputation that generates Bartlett window coefficients
	 * @see WindowComputation
	 */
	default WindowComputation bartlettWindow(int size) {
		return WindowComputation.bartlett(size);
	}

	/**
	 * Creates a flat-top window of the specified size.
	 *
	 * <p>The flat-top window is optimized for amplitude accuracy in spectral analysis.
	 * It has a very wide main lobe but minimal amplitude error for sinusoidal components.</p>
	 *
	 * @param size the window size (number of coefficients)
	 * @return a WindowComputation that generates flat-top window coefficients
	 * @see WindowComputation
	 */
	default WindowComputation flattopWindow(int size) {
		return WindowComputation.flattop(size);
	}

	/**
	 * Creates a window computation for the specified type and size.
	 *
	 * @param type the window function type
	 * @param size the window size (number of coefficients)
	 * @return a WindowComputation that generates window coefficients
	 * @see WindowComputation.Type
	 */
	default WindowComputation window(WindowComputation.Type type, int size) {
		return new WindowComputation(type, size);
	}

	/**
	 * Applies a window function to a signal by element-wise multiplication.
	 *
	 * <p>This is a convenience method that generates window coefficients and
	 * multiplies them with the input signal.</p>
	 *
	 * @param signal the input signal producer
	 * @param type the window function type to apply
	 * @return a producer that outputs the windowed signal
	 * @see WindowComputation.Type
	 */
	default CollectionProducer applyWindow(Producer<PackedCollection> signal,
										   WindowComputation.Type type) {
		TraversalPolicy shape = shape(signal);
		int size = shape.getSize();
		WindowComputation window = new WindowComputation(type, size);
		return multiply(signal, window);
	}

	// ==================== Spectral Analysis Operations ====================

	/**
	 * Computes the power spectrum (magnitude squared) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * power[k] = re[k]^2 + im[k]^2</p>
	 *
	 * <p>This is more efficient than computing the magnitude (which requires
	 * a square root) when the actual magnitude value isn't needed, such as
	 * for power spectral density or energy calculations.</p>
	 *
	 * <h3>Example Usage</h3>
	 * <pre>{@code
	 * // Compute FFT and get power spectrum
	 * Producer<PackedCollection> signal = ...;
	 * FourierTransform fft = fft(1024, signal);
	 *
	 * // Reshape to [512, 2] for complex pairs
	 * CollectionProducer complex = fft.reshape(512, 2);
	 *
	 * // Compute power spectrum
	 * CollectionProducer power = powerSpectrum(complex);
	 * // Output shape: [512]
	 * }</pre>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs the power spectrum with the last dimension removed
	 */
	default CollectionProducer powerSpectrum(Producer<PackedCollection> complex) {
		// Extract real and imaginary parts
		CollectionProducer cp = (CollectionProducer) complex;
		CollectionProducer real = l(cp.traverse(1));   // Real part at index 0
		CollectionProducer imag = r(cp.traverse(1));   // Imaginary part at index 1

		// power = re^2 + im^2
		return add(sq(real), sq(imag));
	}

	/**
	 * Computes the phase angle (argument) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * phase[k] = atan2(im[k], re[k])</p>
	 *
	 * <p>The result is in radians, ranging from -PI to PI. This represents
	 * the angle of each complex value in the complex plane.</p>
	 *
	 * <h3>Example Usage</h3>
	 * <pre>{@code
	 * // Compute FFT and get phase spectrum
	 * Producer<PackedCollection> signal = ...;
	 * FourierTransform fft = fft(1024, signal);
	 *
	 * // Reshape to [512, 2] for complex pairs
	 * CollectionProducer complex = fft.reshape(512, 2);
	 *
	 * // Compute phase
	 * CollectionProducer phaseAngles = phase(complex);
	 * // Output shape: [512], values in range [-PI, PI]
	 * }</pre>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs phase angles in radians with the last dimension removed
	 * @see io.almostrealism.expression.Atan2
	 */
	default CollectionProducer phase(Producer<PackedCollection> complex) {
		// Extract real and imaginary parts
		CollectionProducer cp = (CollectionProducer) complex;
		CollectionProducer real = l(cp.traverse(1));   // Real part at index 0
		CollectionProducer imag = r(cp.traverse(1));   // Imaginary part at index 1

		// phase = atan2(im, re)
		return atan2(imag, real);
	}

	/**
	 * Computes the atan2 function element-wise: atan2(y, x).
	 *
	 * <p>This is the two-argument arctangent function that correctly handles
	 * all four quadrants and produces results in the range [-PI, PI].</p>
	 *
	 * @param y the y-coordinate (or imaginary part for complex phase)
	 * @param x the x-coordinate (or real part for complex phase)
	 * @return A producer computing atan2(y, x) element-wise
	 */
	default CollectionProducer atan2(Producer<PackedCollection> y, Producer<PackedCollection> x) {
		TraversalPolicy shape = shape(y);
		return new org.almostrealism.time.computations.Atan2Computation(shape, y, x);
	}

	/**
	 * Computes the log magnitude (power in dB) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * logMag[k] = 10 * log10(re[k]^2 + im[k]^2)</p>
	 *
	 * <p>This is equivalent to: 20 * log10(magnitude) = 20 * log10(sqrt(re^2 + im^2))</p>
	 *
	 * <p>The result is in decibels (dB), which is the standard unit for expressing
	 * power ratios in signal processing. A small epsilon is added to avoid log(0).</p>
	 *
	 * <h3>Example Usage</h3>
	 * <pre>{@code
	 * // Compute FFT and get log magnitude spectrum (dB)
	 * Producer<PackedCollection> signal = ...;
	 * FourierTransform fft = fft(1024, signal);
	 *
	 * // Reshape to [512, 2] for complex pairs
	 * CollectionProducer complex = fft.reshape(512, 2);
	 *
	 * // Compute log magnitude in dB
	 * CollectionProducer dBSpectrum = logMagnitude(complex);
	 * // Output shape: [512], values in dB
	 * }</pre>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs log magnitude in dB with the last dimension removed
	 */
	default CollectionProducer logMagnitude(Producer<PackedCollection> complex) {
		// power = re^2 + im^2
		CollectionProducer power = powerSpectrum(complex);

		// Add small epsilon to avoid log(0)
		// Using 1e-10 as a reasonable floor for power values
		CollectionProducer safePower = add(power, c(1e-10));

		// logMag = 10 * log10(power) = 10 * ln(power) / ln(10)
		// Since log() computes natural log, we need to divide by ln(10) = 2.302585...
		double ln10 = Math.log(10.0);
		return multiply(c(10.0 / ln10), log(safePower));
	}

	/**
	 * Computes the magnitude (absolute value) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * magnitude[k] = sqrt(re[k]^2 + im[k]^2)</p>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs the magnitude with the last dimension removed
	 */
	default CollectionProducer complexMagnitude(Producer<PackedCollection> complex) {
		return sqrt(powerSpectrum(complex));
	}

	// ==================== Short-Time Fourier Transform (STFT) ====================

	/**
	 * Creates a Short-Time Fourier Transform (STFT) computation with Hann window.
	 *
	 * <p>This is a convenience method that uses a Hann window, which is the most
	 * common choice for general-purpose spectral analysis.</p>
	 *
	 * @param fftSize The FFT frame size (must be power of 2)
	 * @param hopSize The hop size (number of samples between frames)
	 * @param signal  The input time-domain signal
	 * @return A STFTComputation that produces the spectrogram
	 * @see STFTComputation
	 */
	default STFTComputation stft(int fftSize, int hopSize, Producer<PackedCollection> signal) {
		return stft(fftSize, hopSize, WindowComputation.Type.HANN, signal);
	}

	/**
	 * Creates a Short-Time Fourier Transform (STFT) computation.
	 *
	 * <p>The STFT divides a signal into overlapping frames, applies a window function
	 * to each frame, and computes the FFT. The result is a time-frequency representation
	 * (spectrogram) showing how frequency content changes over time.</p>
	 *
	 * <h3>Common Configurations</h3>
	 * <table border="1">
	 * <caption>Typical STFT Settings</caption>
	 * <tr><th>Application</th><th>FFT Size</th><th>Hop Size</th><th>Overlap</th></tr>
	 * <tr><td>Music analysis</td><td>2048</td><td>512</td><td>75%</td></tr>
	 * <tr><td>Speech analysis</td><td>512</td><td>128</td><td>75%</td></tr>
	 * <tr><td>Fast tracking</td><td>256</td><td>64</td><td>75%</td></tr>
	 * </table>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // Create STFT for music analysis
	 * Producer<PackedCollection> audio = ...; // Audio signal
	 * STFTComputation stft = stft(2048, 512, WindowComputation.Type.HANN, audio);
	 * PackedCollection spectrogram = stft.get().evaluate();
	 *
	 * // Get power spectrum of each frame
	 * CollectionProducer power = powerSpectrum(cp(spectrogram).reshape(-1, 2));
	 * }</pre>
	 *
	 * @param fftSize    The FFT frame size (must be power of 2)
	 * @param hopSize    The hop size (number of samples between frames)
	 * @param windowType The window function type to apply
	 * @param signal     The input time-domain signal
	 * @return A STFTComputation that produces the spectrogram
	 * @see STFTComputation
	 * @see WindowComputation.Type
	 */
	default STFTComputation stft(int fftSize, int hopSize,
								 WindowComputation.Type windowType,
								 Producer<PackedCollection> signal) {
		return new STFTComputation(fftSize, hopSize, windowType, signal);
	}

	/**
	 * Creates an STFT computation with explicit number of frames.
	 *
	 * <p>Use this when you want to control the exact number of output frames,
	 * such as when the signal length is not known at construction time.</p>
	 *
	 * @param fftSize    The FFT frame size (must be power of 2)
	 * @param hopSize    The hop size (number of samples between frames)
	 * @param windowType The window function type to apply
	 * @param signal     The input time-domain signal
	 * @param numFrames  The number of frames to compute
	 * @return A STFTComputation that produces the spectrogram
	 * @see STFTComputation
	 */
	default STFTComputation stft(int fftSize, int hopSize,
								 WindowComputation.Type windowType,
								 Producer<PackedCollection> signal,
								 int numFrames) {
		return new STFTComputation(fftSize, hopSize, windowType, signal, numFrames);
	}

	// ==================== Mel Filterbank ====================

	/**
	 * Creates a mel filterbank computation with default frequency range.
	 *
	 * <p>Uses 0 Hz as minimum and sampleRate/2 as maximum frequency.</p>
	 *
	 * @param fftSize       The FFT size used to compute the power spectrum
	 * @param sampleRate    The sample rate of the original signal
	 * @param numMelBands   The number of mel bands (filters)
	 * @param powerSpectrum The input power spectrum producer
	 * @return A MelFilterBank computation
	 * @see MelFilterBank
	 */
	default MelFilterBank melFilterBank(int fftSize, int sampleRate, int numMelBands,
										Producer<PackedCollection> powerSpectrum) {
		return melFilterBank(fftSize, sampleRate, numMelBands, 0, sampleRate / 2.0, powerSpectrum);
	}

	/**
	 * Creates a mel filterbank computation.
	 *
	 * <p>The mel filterbank applies a set of overlapping triangular filters
	 * that are equally spaced on the mel (perceptual) frequency scale.
	 * This is commonly used for:</p>
	 * <ul>
	 *   <li>MFCC (Mel-Frequency Cepstral Coefficients) extraction</li>
	 *   <li>Audio classification features</li>
	 *   <li>Speech recognition preprocessing</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // Compute mel spectrogram from audio
	 * Producer<PackedCollection> audio = ...;
	 * STFTComputation stft = stft(1024, 256, audio);
	 * PackedCollection spectrogram = stft.get().evaluate();
	 *
	 * // For each frame, get power spectrum and apply mel filterbank
	 * // (This shows the concept - real usage would process all frames)
	 * CollectionProducer frame = cp(spectrogram).subset(shape(1024, 2), 0);
	 * CollectionProducer power = powerSpectrum(frame);
	 *
	 * MelFilterBank melBank = melFilterBank(1024, 22050, 40, 0, 8000, power);
	 * PackedCollection melEnergies = melBank.get().evaluate();
	 * }</pre>
	 *
	 * @param fftSize       The FFT size used to compute the power spectrum
	 * @param sampleRate    The sample rate of the original signal
	 * @param numMelBands   The number of mel bands (filters)
	 * @param fMin          The minimum frequency in Hz
	 * @param fMax          The maximum frequency in Hz
	 * @param powerSpectrum The input power spectrum producer
	 * @return A MelFilterBank computation
	 * @see MelFilterBank
	 */
	default MelFilterBank melFilterBank(int fftSize, int sampleRate, int numMelBands,
										double fMin, double fMax,
										Producer<PackedCollection> powerSpectrum) {
		return new MelFilterBank(fftSize, sampleRate, numMelBands, fMin, fMax, powerSpectrum);
	}

	/**
	 * Converts a frequency value from Hz to mel scale.
	 *
	 * <p>Formula: mel = 2595 * log10(1 + hz/700)</p>
	 *
	 * @param hz frequency in Hz
	 * @return frequency in mel
	 */
	default double hzToMel(double hz) {
		return MelFilterBank.hzToMel(hz);
	}

	/**
	 * Converts a frequency value from mel scale to Hz.
	 *
	 * <p>Formula: hz = 700 * (10^(mel/2595) - 1)</p>
	 *
	 * @param mel frequency in mel
	 * @return frequency in Hz
	 */
	default double melToHz(double mel) {
		return MelFilterBank.melToHz(mel);
	}

	// ==================== FFT Convolution ====================

	/**
	 * Creates an FFT-based convolution computation.
	 *
	 * <p>FFT convolution is more efficient than direct convolution for large
	 * signals or kernels. It uses the convolution theorem: convolution in the
	 * time domain equals multiplication in the frequency domain.</p>
	 *
	 * <h3>When to Use FFT Convolution</h3>
	 * <ul>
	 *   <li>Signal or kernel length &gt; 64 samples</li>
	 *   <li>Convolution reverb with impulse responses</li>
	 *   <li>Long FIR filter application</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // Apply convolution reverb
	 * Producer<PackedCollection> audio = ...;         // Dry audio
	 * Producer<PackedCollection> impulseResponse = ...; // Room IR
	 *
	 * FFTConvolution reverb = fftConvolve(audio, impulseResponse);
	 * PackedCollection wetAudio = reverb.get().evaluate();
	 * }</pre>
	 *
	 * @param signal The input signal producer
	 * @param kernel The convolution kernel (filter/impulse response) producer
	 * @return An FFTConvolution computation
	 * @see FFTConvolution
	 */
	default FFTConvolution fftConvolve(Producer<PackedCollection> signal,
									   Producer<PackedCollection> kernel) {
		return new FFTConvolution(signal, kernel);
	}
}
