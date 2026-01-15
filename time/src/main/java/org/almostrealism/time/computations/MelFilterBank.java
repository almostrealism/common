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

package org.almostrealism.time.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * A computation for applying a mel-scale filterbank to a power spectrum.
 *
 * <p>The mel-scale filterbank is a set of overlapping triangular filters
 * that are spaced linearly in the mel frequency domain. This perceptual
 * frequency scale better models how humans perceive sound, with finer
 * resolution at lower frequencies and coarser resolution at higher frequencies.</p>
 *
 * <h2>What is Mel Scale?</h2>
 * <p>The mel scale is a perceptual scale of pitches. The name "mel" comes from
 * "melody," as the scale is based on pitch comparison experiments. The conversion
 * from Hz to mel is:</p>
 * <pre>
 * mel = 2595 * log10(1 + hz/700)
 * </pre>
 *
 * <h2>Filterbank Structure</h2>
 * <p>The filterbank consists of triangular filters that:</p>
 * <ul>
 *   <li>Are equally spaced in the mel domain</li>
 *   <li>Have peaks at the center of each band</li>
 *   <li>Overlap with neighboring filters</li>
 *   <li>Sum to approximately 1.0 at all frequencies (constant-Q property)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Compute mel filterbank energies from power spectrum
 * int fftSize = 1024;
 * int sampleRate = 22050;
 * int numMelBands = 40;
 *
 * // Get power spectrum from FFT
 * Producer<PackedCollection> powerSpectrum = powerSpectrum(fftOutput);
 *
 * // Apply mel filterbank
 * MelFilterBank melBank = new MelFilterBank(fftSize, sampleRate, numMelBands, 0, sampleRate/2, powerSpectrum);
 * PackedCollection melEnergies = melBank.get().evaluate();
 * // Result shape: [numMelBands]
 * }</pre>
 *
 * <h2>Common Configurations</h2>
 * <table border="1">
 * <caption>Typical Mel Filterbank Settings</caption>
 * <tr><th>Application</th><th>Mel Bands</th><th>Frequency Range</th></tr>
 * <tr><td>Speech (MFCC)</td><td>26-40</td><td>0 - 8000 Hz</td></tr>
 * <tr><td>Music analysis</td><td>40-128</td><td>0 - SR/2</td></tr>
 * <tr><td>Audio classification</td><td>64-128</td><td>20 - 8000 Hz</td></tr>
 * </table>
 *
 * @see org.almostrealism.time.TemporalFeatures#melFilterBank(int, int, int, double, double, Producer)
 *
 * @author Michael Murray
 */
public class MelFilterBank implements MatrixFeatures {

	private final int fftSize;
	private final int sampleRate;
	private final int numMelBands;
	private final double fMin;
	private final double fMax;
	private final Producer<PackedCollection> powerSpectrum;
	private final TraversalPolicy outputShape;

	// Pre-computed filterbank matrix as a PackedCollection [numMelBands, numFreqBins]
	private final PackedCollection filterbankMatrix;

	/**
	 * Constructs a mel filterbank computation.
	 *
	 * @param fftSize       The FFT size used to compute the power spectrum
	 * @param sampleRate    The sample rate of the original signal
	 * @param numMelBands   The number of mel bands (filters)
	 * @param fMin          The minimum frequency in Hz (typically 0 or 20)
	 * @param fMax          The maximum frequency in Hz (typically sampleRate/2)
	 * @param powerSpectrum The input power spectrum producer
	 */
	public MelFilterBank(int fftSize, int sampleRate, int numMelBands,
						 double fMin, double fMax,
						 Producer<PackedCollection> powerSpectrum) {
		this.fftSize = fftSize;
		this.sampleRate = sampleRate;
		this.numMelBands = numMelBands;
		this.fMin = fMin;
		this.fMax = fMax;
		this.powerSpectrum = powerSpectrum;
		this.outputShape = shape(numMelBands);

		// Pre-compute the filterbank matrix as a PackedCollection
		this.filterbankMatrix = computeFilterbankMatrix();
	}

	/**
	 * Returns the number of mel bands.
	 *
	 * @return the number of mel bands
	 */
	public int getNumMelBands() {
		return numMelBands;
	}

	/**
	 * Returns the output shape.
	 *
	 * @return the output shape [numMelBands]
	 */
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	/**
	 * Returns the pre-computed filterbank matrix as a PackedCollection.
	 *
	 * <p>The matrix has shape [numMelBands, numFreqBins] where
	 * numFreqBins = fftSize/2 + 1.</p>
	 *
	 * @return the filterbank matrix as a PackedCollection
	 */
	public PackedCollection getFilterbankMatrix() {
		return filterbankMatrix;
	}

	/**
	 * Returns the pre-computed filterbank matrix as a 2D array.
	 *
	 * <p>The matrix has shape [numMelBands, numFreqBins] where
	 * numFreqBins = fftSize/2 + 1.</p>
	 *
	 * @return the filterbank matrix as double[][]
	 */
	public double[][] getFilterbank() {
		int numFreqBins = fftSize / 2 + 1;
		double[][] fb = new double[numMelBands][numFreqBins];
		for (int m = 0; m < numMelBands; m++) {
			for (int k = 0; k < numFreqBins; k++) {
				fb[m][k] = filterbankMatrix.toDouble(m * numFreqBins + k);
			}
		}
		return fb;
	}

	/**
	 * Returns a GPU-accelerated producer that applies the mel filterbank.
	 *
	 * <p>The computation uses matrix multiplication: output = filterbank @ spectrum,
	 * where filterbank is [numMelBands, numFreqBins] and spectrum is [numFreqBins].</p>
	 *
	 * @return CollectionProducer that computes mel filterbank energies on GPU
	 */
	public CollectionProducer get() {
		// Use GPU-accelerated matrix multiplication: output = filterbank @ spectrum
		// filterbank: [numMelBands, numFreqBins]
		// spectrum: [numFreqBins]
		// output: [numMelBands]
		return matmul(cp(filterbankMatrix), powerSpectrum).reshape(outputShape);
	}

	/**
	 * Computes the mel filterbank matrix as a PackedCollection.
	 *
	 * @return the filterbank matrix as PackedCollection with shape [numMelBands, numFreqBins]
	 */
	private PackedCollection computeFilterbankMatrix() {
		int numFreqBins = fftSize / 2 + 1;

		// Convert frequency bounds to mel
		double melMin = hzToMel(fMin);
		double melMax = hzToMel(fMax);

		// Create numMelBands + 2 points (for filter edges)
		double[] melPoints = new double[numMelBands + 2];
		for (int i = 0; i < numMelBands + 2; i++) {
			melPoints[i] = melMin + (melMax - melMin) * i / (numMelBands + 1);
		}

		// Convert mel points back to Hz
		double[] hzPoints = new double[numMelBands + 2];
		for (int i = 0; i < numMelBands + 2; i++) {
			hzPoints[i] = melToHz(melPoints[i]);
		}

		// Convert Hz points to FFT bin indices
		int[] binPoints = new int[numMelBands + 2];
		for (int i = 0; i < numMelBands + 2; i++) {
			binPoints[i] = (int) Math.floor((fftSize + 1) * hzPoints[i] / sampleRate);
		}

		// Create the filterbank matrix as a PackedCollection
		PackedCollection fb = new PackedCollection(shape(numMelBands, numFreqBins));

		for (int m = 0; m < numMelBands; m++) {
			int fStart = binPoints[m];
			int fCenter = binPoints[m + 1];
			int fEnd = binPoints[m + 2];

			// Rising slope
			for (int k = fStart; k < fCenter && k < numFreqBins; k++) {
				if (fCenter != fStart) {
					fb.setMem(m * numFreqBins + k, (double) (k - fStart) / (fCenter - fStart));
				}
			}

			// Falling slope
			for (int k = fCenter; k < fEnd && k < numFreqBins; k++) {
				if (fEnd != fCenter) {
					fb.setMem(m * numFreqBins + k, (double) (fEnd - k) / (fEnd - fCenter));
				}
			}
		}

		return fb;
	}

	/**
	 * Converts frequency from Hz to mel scale.
	 *
	 * @param hz frequency in Hz
	 * @return frequency in mel
	 */
	public static double hzToMel(double hz) {
		return 2595.0 * Math.log10(1.0 + hz / 700.0);
	}

	/**
	 * Converts frequency from mel to Hz scale.
	 *
	 * @param mel frequency in mel
	 * @return frequency in Hz
	 */
	public static double melToHz(double mel) {
		return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
	}
}
