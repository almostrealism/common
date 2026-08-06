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
	 * <p>Every frame's spectrum is built by one computation, but the inverse
	 * transform runs a frame at a time and reads from a buffer that stays in the
	 * same place. An operation compiled against a particular offset into a larger
	 * buffer is not the operation the next frame needs, so handing the transform a
	 * moving view of the spectra would compile one program per frame.</p>
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

		// Complex spectra for every frame at once, with random phase
		PackedCollection spectra = createComplexSpectra(freqData, freqFrames, freqBins);
		PackedCollection frameSpectrum = new PackedCollection(shape(fftSize, 2));

		// Process each frame
		for (int frame = 0; frame < freqFrames; frame++) {
			frameSpectrum.setFrom(0, spectra.range(shape(fftSize * 2), frame * fftSize * 2));

			PackedCollection timeDomain = applyIfft(frameSpectrum, fftSize);

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
	 * Creates the complex spectrum of every frame, with random phase.
	 *
	 * <p>For real-valued output, each spectrum must be conjugate symmetric:
	 * X[N-k] = conj(X[k]). Random phase is assigned to the positive frequencies
	 * and {@link #conjugateSymmetric(io.almostrealism.relation.Producer)} mirrors
	 * them into the negative frequencies. The DC bin of each frame is given zero
	 * phase, so that it stays real as conjugate symmetry requires.</p>
	 *
	 * <p>Every frame is built by the same computation rather than one at a time:
	 * the phase draw, the polar to rectangular conversion and the symmetric
	 * extension do not depend on each other across frames, and running them once
	 * for the whole sequence avoids paying for a separate evaluation per frame.</p>
	 *
	 * @param freqData the magnitude spectrum for every frame, shaped (frames, bins)
	 * @param frames   the number of frames
	 * @param bins     the number of magnitude values per frame
	 * @return the spectra, shaped (frames, 2 * bins, 2), each in interleaved
	 *         format [re0, im0, re1, im1, ...]
	 */
	private PackedCollection createComplexSpectra(PackedCollection freqData, int frames, int bins) {
		// Zero phase for the DC bin of each frame, random phase elsewhere. The draw
		// is realized once, so the real and imaginary parts describe the same angle.
		PackedCollection angle = rand(shape(frames, bins), random)
				.multiply(2 * Math.PI)
				.multiply(greaterThan(integers(0, frames * bins).mod((double) bins),
						c(0.5), c(1.0), c(0.0)).reshape(shape(frames, bins)))
				.evaluate();

		CollectionProducer magnitude = cp(freqData).reshape(shape(frames, bins, 1));
		CollectionProducer phase = cp(angle).reshape(shape(frames, bins, 1));

		CollectionProducer half = concat(2,
				magnitude.multiply(cos(phase)),
				magnitude.multiply(sin(phase)));

		return conjugateSymmetric(half).evaluate();
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
