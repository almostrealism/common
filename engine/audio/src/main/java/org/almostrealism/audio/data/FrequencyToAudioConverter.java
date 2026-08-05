/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package org.almostrealism.audio.data;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.FourierTransform;

import java.util.Random;

/**
 * Converts frequency magnitude data to audio waveform via IFFT.
 *
 * <p>This utility enables spatial frequency drawings to be used as audio
 * conditions for generation. It takes a {@link WaveDetails} with populated
 * {@code freqData} (magnitude spectrum) and computes the corresponding
 * audio waveform, storing it in {@code data}.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>For each frequency frame, extract magnitude values</li>
 *   <li>Generate random phase to create complex spectrum</li>
 *   <li>Ensure conjugate symmetry for real-valued output</li>
 *   <li>Apply IFFT to get time-domain frame</li>
 *   <li>Apply Hann window and overlap-add to output</li>
 * </ol>
 *
 * <p>Random phase is used because the drawing only captures magnitude
 * information. While this produces imperfect audio reconstruction, it's
 * sufficient for autoencoder feature extraction used in generation.</p>
 *
 * @see WaveDetails
 * @see FourierTransform
 */
public class FrequencyToAudioConverter implements TemporalFeatures, ConsoleFeatures {

	/** Random number generator used for phase initialization during synthesis. */
	private final Random random;

	/**
	 * Creates a converter with a new random seed.
	 */
	public FrequencyToAudioConverter() {
		this(new Random());
	}

	/**
	 * Creates a converter with the specified random generator.
	 *
	 * @param random random generator for phase estimation
	 */
	public FrequencyToAudioConverter(Random random) {
		this.random = random;
	}

	/**
	 * Converts frequency data to audio and populates {@code WaveDetails.data}.
	 *
	 * <p>The WaveDetails must have {@code freqData}, {@code freqBinCount},
	 * {@code freqFrameCount}, {@code freqSampleRate}, and {@code sampleRate}
	 * already set. After conversion, {@code data} and {@code frameCount}
	 * will be populated.</p>
	 *
	 * @param details the WaveDetails to convert
	 * @throws IllegalArgumentException if required frequency data is missing
	 */
	public void convert(WaveDetails details) {
		PackedCollection freqData = details.getFreqData();
		if (freqData == null) {
			throw new IllegalArgumentException("WaveDetails has no frequency data");
		}

		int freqBins = details.getFreqBinCount();
		int freqFrames = details.getFreqFrameCount();
		double sampleRate = details.getSampleRate();
		double freqSampleRate = details.getFreqSampleRate();

		if (freqBins <= 0 || freqFrames <= 0) {
			throw new IllegalArgumentException("Invalid frequency dimensions: " +
					freqBins + " bins, " + freqFrames + " frames");
		}

		// FFT size is 2x frequency bins for real signals
		int fftSize = freqBins * 2;

		// Hop size between frames
		int hopSize = (int) (sampleRate / freqSampleRate);

		// Output length
		int outputLength = details.getFrameCount();
		if (outputLength <= 0) {
			outputLength = (int) (freqFrames * sampleRate / freqSampleRate);
			details.setFrameCount(outputLength);
		}

		// Allocate output buffer
		PackedCollection output = new PackedCollection(outputLength);

		// Precompute Hann window
		PackedCollection window = createHannWindow(fftSize);

		// Process each frame
		for (int frame = 0; frame < freqFrames; frame++) {
			// Extract magnitude for this frame
			double[] magnitude = extractMagnitude(freqData, frame, freqBins);

			// Create complex spectrum with random phase
			PackedCollection complexSpectrum = createComplexSpectrum(magnitude, fftSize);

			// Apply IFFT
			PackedCollection timeDomain = applyIfft(complexSpectrum, fftSize);

			// Apply window and overlap-add to output
			int startSample = frame * hopSize;
			overlapAdd(output, timeDomain, window, startSample, outputLength);
		}

		// Normalize output to prevent clipping
		normalizeAudio(output);

		// Store in WaveDetails
		details.setData(output);
		details.setChannelCount(1);

		log("Converted " + freqFrames + " frames to " + outputLength + " samples");
	}

	/**
	 * Extracts magnitude values for a single frame from frequency data.
	 */
	private double[] extractMagnitude(PackedCollection freqData, int frame, int bins) {
		double[] magnitude = new double[bins];
		int offset = frame * bins;

		for (int i = 0; i < bins; i++) {
			magnitude[i] = freqData.toDouble(offset + i);
		}

		return magnitude;
	}

	/**
	 * Creates a complex spectrum from magnitude with random phase.
	 *
	 * <p>For real-valued output, the spectrum must be conjugate symmetric:
	 * X[N-k] = conj(X[k]). This method generates random phase for positive
	 * frequencies and mirrors them for negative frequencies.</p>
	 *
	 * @param magnitude magnitude values for positive frequencies
	 * @param fftSize   total FFT size (2 * magnitude.length)
	 * @return complex spectrum in interleaved format [re0, im0, re1, im1, ...]
	 */
	private PackedCollection createComplexSpectrum(double[] magnitude, int fftSize) {
		// Complex spectrum: interleaved real/imaginary
		double[] spectrum = new double[fftSize * 2];
		int halfSize = fftSize / 2;

		// DC component (bin 0) - real only, no phase
		spectrum[0] = magnitude[0];
		spectrum[1] = 0.0;

		// Positive frequencies (bins 1 to N/2-1)
		for (int k = 1; k < halfSize; k++) {
			double mag = k < magnitude.length ? magnitude[k] : 0.0;
			double phase = random.nextDouble() * 2 * Math.PI;

			double re = mag * Math.cos(phase);
			double im = mag * Math.sin(phase);

			// Positive frequency
			spectrum[k * 2] = re;
			spectrum[k * 2 + 1] = im;

			// Negative frequency (conjugate symmetric)
			int negK = fftSize - k;
			spectrum[negK * 2] = re;
			spectrum[negK * 2 + 1] = -im;
		}

		// Nyquist frequency (bin N/2) - real only
		if (halfSize < magnitude.length) {
			spectrum[halfSize * 2] = magnitude[halfSize];
			spectrum[halfSize * 2 + 1] = 0.0;
		}

		return PackedCollection.of(spectrum);
	}

	/**
	 * Applies inverse FFT to complex spectrum.
	 *
	 * @param complexSpectrum complex spectrum in interleaved format
	 * @param fftSize         number of frequency bins
	 * @return time-domain samples (real part only)
	 */
	private PackedCollection applyIfft(PackedCollection complexSpectrum, int fftSize) {
		FourierTransform ifft = new FourierTransform(1, fftSize, true, cp(complexSpectrum));
		PackedCollection result = ifft.get().evaluate();

		// Extract real part -- column 0 of the interleaved (real, imaginary) pairs
		return subset(shape(fftSize, 1), cp(result.reshape(shape(fftSize, 2))), 0, 0)
				.evaluate().reshape(shape(fftSize));
	}

	/**
	 * Creates a Hann window of the specified size.
	 */
	private PackedCollection createHannWindow(int size) {
		return c(1.0).subtract(cos(integers(0, size).multiply(2 * Math.PI / size)))
				.multiply(0.5).evaluate();
	}

	/**
	 * Applies window and overlap-adds time-domain frame to output.
	 */
	private void overlapAdd(PackedCollection output, PackedCollection frame,
							PackedCollection window, int startSample, int outputLength) {
		int begin = Math.max(0, -startSample);
		int end = Math.min(frame.getMemLength(), outputLength - startSample);
		if (end <= begin) return;

		int count = end - begin;
		PackedCollection destination = output.range(shape(count), startSample + begin);

		cp(destination)
				.add(cp(frame.range(shape(count), begin))
						.multiply(cp(window.range(shape(count), begin))))
				.into(destination.traverseEach()).evaluate();
	}

	/**
	 * Normalizes audio to prevent clipping.
	 *
	 * <p>The scale is evaluated through {@link Process#optimized(java.util.function.Supplier)}
	 * rather than by calling {@link io.almostrealism.relation.Producer#into(Object)}
	 * directly. Isolation is only consulted during optimization, so an unoptimized
	 * consumer embeds the whole-collection peak reduction at every element and the
	 * expression depth grows with the length of the buffer, exceeding
	 * {@code ScopeSettings.maxDepth} once the audio is long enough. Deciding where
	 * to split belongs to the process tree, not to this method.</p>
	 */
	private void normalizeAudio(PackedCollection audio) {
		CollectionProducer peak = max(cp(audio).abs());
		CollectionProducer scaled = cp(audio)
				.multiply(greaterThan(peak, c(1e-6), c(0.9).divide(peak), c(1.0)));

		Evaluable scale = Process.optimized(scaled).get();
		scale.into(audio.traverseEach()).evaluate();
	}
}
