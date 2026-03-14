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

package org.almostrealism.ml.midi;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;

/**
 * Fundamental Music Embedding (FME) -- a sinusoidal continuous embedding
 * with learnable translation bias and linear projection.
 *
 * <p>Unlike standard token embeddings that use a lookup table, FME computes
 * a sinusoidal encoding of the input value (similar to positional encoding)
 * and then projects it through a learned linear layer. This allows the model
 * to embed continuous or large-vocabulary discrete attributes without a
 * massive embedding table.</p>
 *
 * <h2>Computation</h2>
 * <pre>
 * freqs[i] = 1.0 / base^(2*i / dim)     // same as RoPE frequency computation
 * angle[i] = (value + translation_bias) * freqs[i]
 * encoding[2*i]     = sin(angle[i])
 * encoding[2*i + 1] = cos(angle[i])
 * output = Linear(encoding)              // weight (dim, dim) + bias (dim)
 * </pre>
 *
 * <p>The frequency computation reuses the math from
 * {@code RotationFeatures.computeRopeFreqs()} -- the formula
 * {@code 1/base^(2i/dim)} is identical.</p>
 *
 * <h2>Weight Keys (StateDictionary)</h2>
 * <ul>
 *   <li>{@code <prefix>.linear.weight} -- shape (dim, dim)</li>
 *   <li>{@code <prefix>.linear.bias} -- shape (dim,)</li>
 *   <li>{@code <prefix>.translation_bias} -- shape (1,)</li>
 * </ul>
 *
 * @see CompoundMidiEmbedding
 * @see MoonbeamConfig
 */
public class FundamentalMusicEmbedding {

	/** The base value for frequency computation (attribute-specific theta). */
	private final double base;

	/** The embedding output dimension. */
	private final int dim;

	/** Precomputed inverse frequency values: 1/base^(2i/dim). */
	private final double[] invFreqs;

	/** Learned linear projection weight, shape (dim, dim). */
	private final PackedCollection linearWeight;

	/** Learned linear projection bias, shape (dim,). */
	private final PackedCollection linearBias;

	/** Learned translation bias added to input before sinusoidal transform, shape (1,). */
	private final PackedCollection translationBias;

	/**
	 * Create a FundamentalMusicEmbedding with pretrained weights.
	 *
	 * @param base            the frequency base (e.g., 199999 for onset)
	 * @param dim             the embedding dimension (e.g., 320)
	 * @param linearWeight    linear projection weight, shape (dim, dim)
	 * @param linearBias      linear projection bias, shape (dim,)
	 * @param translationBias translation bias, shape (1,)
	 */
	public FundamentalMusicEmbedding(double base, int dim,
									 PackedCollection linearWeight,
									 PackedCollection linearBias,
									 PackedCollection translationBias) {
		this.base = base;
		this.dim = dim;
		this.linearWeight = linearWeight;
		this.linearBias = linearBias;
		this.translationBias = translationBias;
		this.invFreqs = computeInvFreqs(base, dim);
	}

	/**
	 * Create a FundamentalMusicEmbedding with random weights for testing.
	 *
	 * @param base the frequency base
	 * @param dim  the embedding dimension
	 */
	public FundamentalMusicEmbedding(double base, int dim) {
		this.base = base;
		this.dim = dim;
		this.invFreqs = computeInvFreqs(base, dim);
		this.linearWeight = new PackedCollection(new TraversalPolicy(dim, dim));
		this.linearBias = new PackedCollection(new TraversalPolicy(dim));
		this.translationBias = new PackedCollection(new TraversalPolicy(1));
	}

	/**
	 * Compute the sinusoidal embedding for a single integer value.
	 *
	 * <p>This performs the full FME pipeline: translate, encode with
	 * sin/cos, then linear project.</p>
	 *
	 * @param value the integer attribute value to embed
	 * @return PackedCollection of shape (dim,) containing the embedding vector
	 */
	public PackedCollection embed(int value) {
		double bias = translationBias.toDouble(0);
		double biasedValue = value + bias;

		int halfDim = dim / 2;
		double[] sincos = new double[dim];
		for (int i = 0; i < halfDim; i++) {
			double angle = biasedValue * invFreqs[i];
			sincos[2 * i] = Math.sin(angle);
			sincos[2 * i + 1] = Math.cos(angle);
		}

		double[] output = new double[dim];
		for (int row = 0; row < dim; row++) {
			double sum = 0.0;
			for (int col = 0; col < dim; col++) {
				sum += linearWeight.toDouble(row * dim + col) * sincos[col];
			}
			output[row] = sum + linearBias.toDouble(row);
		}

		PackedCollection result = new PackedCollection(new TraversalPolicy(dim));
		for (int i = 0; i < dim; i++) {
			result.setMem(i, output[i]);
		}
		return result;
	}

	/**
	 * Compute the sinusoidal encoding (before linear projection) for a single value.
	 *
	 * <p>Useful for testing that the sinusoidal component produces correct shapes.</p>
	 *
	 * @param value the integer attribute value
	 * @return PackedCollection of shape (dim,) containing sin/cos encoding
	 */
	public PackedCollection encodeSinusoidal(int value) {
		double bias = translationBias.toDouble(0);
		double biasedValue = value + bias;

		int halfDim = dim / 2;
		PackedCollection result = new PackedCollection(new TraversalPolicy(dim));
		for (int i = 0; i < halfDim; i++) {
			double angle = biasedValue * invFreqs[i];
			result.setMem(2 * i, Math.sin(angle));
			result.setMem(2 * i + 1, Math.cos(angle));
		}
		return result;
	}

	/** Returns the embedding dimension. */
	public int getDim() { return dim; }

	/** Returns the frequency base. */
	public double getBase() { return base; }

	/** Returns the linear projection weight. */
	public PackedCollection getLinearWeight() { return linearWeight; }

	/** Returns the linear projection bias. */
	public PackedCollection getLinearBias() { return linearBias; }

	/** Returns the translation bias. */
	public PackedCollection getTranslationBias() { return translationBias; }

	/**
	 * Compute inverse frequency values for the sinusoidal encoding.
	 * This is the same formula used in {@code RotationFeatures.computeRopeFreqs()}:
	 * {@code invFreq[i] = 1.0 / base^(2*i / dim)}.
	 *
	 * @param base the frequency base
	 * @param dim  the embedding dimension
	 * @return array of dim/2 inverse frequency values
	 */
	public static double[] computeInvFreqs(double base, int dim) {
		int halfDim = dim / 2;
		double[] freqs = new double[halfDim];
		for (int i = 0; i < halfDim; i++) {
			freqs[i] = 1.0 / Math.pow(base, (2.0 * i) / dim);
		}
		return freqs;
	}
}
