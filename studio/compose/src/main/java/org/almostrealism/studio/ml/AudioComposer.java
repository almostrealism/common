/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio.ml;
import org.almostrealism.ml.audio.AutoEncoder;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Composes audio by interpolating multiple encoded audio sources in latent space.
 * Each source is encoded via an {@link AutoEncoder} and assigned a merge weight vector
 * that is used to blend the sources based on a supplied position vector. By default the
 * weight vector is sampled from a Gaussian; a caller may instead supply it explicitly
 * ({@link #addSource(Producer, Producer)}, {@link #setWeights(int, Producer)}) and may
 * scale each source by a blend contribution
 * ({@link ComposableAudioFeatures#setContribution(double)}), which makes the composed
 * latent a deterministic function of the sources.
 */
public class AudioComposer implements Factor<PackedCollection>, Destroyable, CodeFeatures {
	/** When {@code true}, weight vectors are L2-normalized before use. */
	public static boolean normalizeWeights = true;

	/** The autoencoder used to encode audio sources and decode the composed latent. */
	private final AutoEncoder autoencoder;

	/** Dimensionality of the random weight vectors for each source. */
	private final int dim;

	/** List of composable audio feature wrappers, one per added audio source. */
	private List<ComposableAudioFeatures> features;

	/** Random number generator used to create weight vectors. */
	private Random random;

	/** Standard deviation of the Gaussian distribution used to sample weight vectors. */
	private double deviation;

	/**
	 * Whether the composed latent is a weighted mean of the sources rather than a weighted
	 * sum; see {@link #setNormalizedBlend(boolean)}.
	 */
	private boolean normalizedBlend;

	/**
	 * Temperature dividing the source scores in a normalized blend; see
	 * {@link #setBlendTemperature(double)}.
	 */
	private double blendTemperature;

	/**
	 * Creates a composer using the given autoencoder and latent dimension, seeded with the
	 * current time.
	 *
	 * @param autoencoder the autoencoder for encoding and decoding audio
	 * @param dim         dimensionality of the interpolation weight vectors
	 */
	public AudioComposer(AutoEncoder autoencoder, int dim) {
		this(autoencoder, dim, System.currentTimeMillis());
	}

	/**
	 * Creates a composer using the given autoencoder, latent dimension, and random seed.
	 *
	 * @param autoencoder the autoencoder for encoding and decoding audio
	 * @param dim         dimensionality of the interpolation weight vectors
	 * @param seed        seed for the random weight generator
	 */
	public AudioComposer(AutoEncoder autoencoder, int dim, long seed) {
		this(autoencoder, dim, new Random(seed));
	}

	/**
	 * Creates a composer using the given autoencoder, latent dimension, and random instance.
	 *
	 * @param autoencoder the autoencoder for encoding and decoding audio
	 * @param dim         dimensionality of the interpolation weight vectors
	 * @param random      the random number generator for weight sampling
	 */
	public AudioComposer(AutoEncoder autoencoder, int dim, Random random) {
		this.autoencoder = autoencoder;
		this.dim = dim;
		this.random = random;
		this.features = new ArrayList<>();
		this.deviation = 1.0;
		this.blendTemperature = 1.0;
	}

	/** Returns the standard deviation used to sample weight vectors. */
	public double getDeviation() { return deviation; }

	/**
	 * Returns whether the composed latent is a weighted mean of the sources; see
	 * {@link #setNormalizedBlend(boolean)}.
	 *
	 * @return {@code true} if the blend is normalized
	 */
	public boolean isNormalizedBlend() { return normalizedBlend; }

	/**
	 * Sets whether the composed latent is a weighted mean of the sources rather than a
	 * weighted sum. In a weighted sum the magnitude of the latent follows the magnitude of
	 * the position: a random position can scale every source far above or below its own
	 * level, and the decoded audio is then near-silent or clipped. In a weighted mean the
	 * position decides only the proportions of the sources, which sum to one, so the latent
	 * stays within the range of the sources it mixes.
	 *
	 * <p>
	 * The proportions are a softmax of the sources'
	 * {@link ComposableAudioFeatures#getScore(Producer) scores} (the dot product of each
	 * merge weight vector with the position) divided by the
	 * {@link #setBlendTemperature(double) blend temperature}, each multiplied by the
	 * source's blend contribution ({@link ComposableAudioFeatures#setContribution(double)})
	 * before the division by their total. Mathematically every blended source therefore has
	 * a strictly positive share, and the shares vary continuously with the position: a
	 * random position mixes all of the sources in its own proportions rather than selecting
	 * some of them. In floating point, a share whose exponent is far enough below zero (a
	 * large score gap relative to a small temperature) underflows to exactly zero, so a
	 * vanishing temperature selects the highest-scoring source alone.
	 * With the other inputs held fixed, raising a source's score raises its share, so among
	 * sources of equal contribution a higher score always gives a larger share; a source
	 * with a much smaller contribution can still receive a smaller share despite a higher
	 * score. A position built from per-source values (for example, explicit orthogonal
	 * weight vectors, unit contributions, and a position whose components are those values)
	 * therefore reproduces their ordering deterministically, and equal scores give shares in
	 * the ratio of the contributions. A position at the origin,
	 * where every score is zero, yields the contribution-weighted mean of the sources.
	 * </p>
	 *
	 * <p>
	 * A source whose contribution is zero or negative cannot be a share of a weighted mean
	 * and is excluded from the blend ({@link ComposableAudioFeatures#isBlended()}). If every
	 * source is excluded, the composed latent is the plain mean of the sources.
	 * </p>
	 *
	 * @param normalizedBlend {@code true} to compose a weighted mean of the sources
	 */
	public void setNormalizedBlend(boolean normalizedBlend) {
		this.normalizedBlend = normalizedBlend;
	}

	/**
	 * Returns the temperature that divides the source scores in a normalized blend; see
	 * {@link #setBlendTemperature(double)}.
	 *
	 * @return the blend temperature
	 */
	public double getBlendTemperature() { return blendTemperature; }

	/**
	 * Sets the temperature that divides the source scores before the softmax of a
	 * normalized blend ({@link #setNormalizedBlend(boolean)}). The default of {@code 1.0}
	 * suits the default weights, which are unit vectors, scored against a position with
	 * unit Gaussian components: each score then has unit variance, and random positions
	 * give clearly different, but never exclusive, mixes. A smaller temperature sharpens the
	 * blend towards the highest-scoring source; a larger one flattens it towards the mean.
	 * The weighted sum does not use the temperature.
	 *
	 * @param blendTemperature the positive temperature
	 * @throws IllegalArgumentException if the temperature is not positive
	 */
	public void setBlendTemperature(double blendTemperature) {
		if (!(blendTemperature > 0)) {
			throw new IllegalArgumentException("Blend temperature must be positive but is " + blendTemperature);
		}

		this.blendTemperature = blendTemperature;
	}

	/**
	 * Sets the standard deviation used to sample weight vectors.
	 *
	 * @param deviation the standard deviation
	 */
	public void setDeviation(double deviation) {
		this.deviation = deviation;
	}

	/**
	 * Returns the maximum audio duration the autoencoder can process, in seconds.
	 *
	 * @return the maximum duration in seconds
	 */
	public double getMaximumAudioDuration() {
		return autoencoder.getMaximumDuration();
	}

	/** Returns the dimensionality of the interpolation weight vectors. */
	public int getEmbeddingDimension() { return dim; }

	/**
	 * Returns the audio sample rate of the underlying autoencoder.
	 *
	 * @return the sample rate in Hz
	 */
	public double getSampleRate() {
		return autoencoder.getSampleRate();
	}

	/**
	 * Resets the random number generator to the given seed for reproducible weight generation.
	 *
	 * @param seed the new random seed
	 */
	public void setWeightSeed(long seed) {
		this.random = new Random(seed);
	}

	/**
	 * Returns the feature shape of the most recently added audio source, or
	 * {@code null} if no sources have been added.
	 *
	 * @return the feature shape, or {@code null}
	 */
	public TraversalPolicy getFeatureShape() {
		if (features.isEmpty()) return null;
		return features.get(features.size() - 1).getFeatureShape();
	}

	/**
	 * Encodes the given audio producer and adds it as a composable source.
	 *
	 * @param audio the raw audio producer to encode and add
	 */
	public void addAudio(Producer<PackedCollection> audio) {
		addSource(autoencoder.encode(audio));
	}

	/**
	 * Wraps the given encoded feature producer in a {@link ComposableAudioFeatures} with
	 * random weights and adds it as a source.
	 *
	 * @param features the encoded latent features to add
	 */
	public void addSource(Producer<PackedCollection> features) {
		addSource(new ComposableAudioFeatures(features, createWeights()));
	}

	/**
	 * Wraps the given encoded feature producer in a {@link ComposableAudioFeatures} whose
	 * merge weights are the supplied length-{@link #getEmbeddingDimension()} vector, which
	 * that object broadcasts across the feature cells exactly as it does the sampled weights.
	 * The vector is used verbatim: no random draw is made and {@link #normalizeWeights}
	 * does not apply, so the source's contribution for a given position vector is fully
	 * determined by the caller.
	 *
	 * @param features the encoded latent features to add
	 * @param weights  the merge weight vector for this source, of length {@code dim}
	 * @throws IllegalArgumentException if the weight vector is not of length {@code dim},
	 *                                  or the feature shape is inconsistent with prior sources
	 */
	public void addSource(Producer<PackedCollection> features, Producer<PackedCollection> weights) {
		addSource(new ComposableAudioFeatures(features, requireWeightVector(weights)));
	}

	/**
	 * Replaces the merge weight vector of an already-added source. Any blend contribution
	 * previously set on that source is preserved. As with
	 * {@link #addSource(Producer, Producer)}, the vector is used verbatim.
	 *
	 * @param index   the index of the source, in the order sources were added
	 * @param weights the new merge weight vector for the source, of length {@code dim}
	 * @throws IndexOutOfBoundsException if no source exists at {@code index}
	 * @throws IllegalArgumentException  if the weight vector is not of length {@code dim}
	 */
	public void setWeights(int index, Producer<PackedCollection> weights) {
		getSource(index).setWeights(requireWeightVector(weights));
	}

	/**
	 * Returns the source at the given index. Per-source state that does not depend on
	 * the composer, such as the blend contribution
	 * ({@link ComposableAudioFeatures#setContribution(double)}), is set on the returned
	 * object directly.
	 *
	 * @param index the index of the source, in the order sources were added
	 * @return the composable audio features for that source
	 * @throws IndexOutOfBoundsException if no source exists at {@code index}
	 */
	public ComposableAudioFeatures getSource(int index) {
		return features.get(index);
	}

	/** Returns the number of sources that have been added. */
	public int getSourceCount() { return features.size(); }

	/**
	 * Adds the given composable audio features as a source, verifying that its
	 * shape is consistent with previously added sources.
	 *
	 * @param features the composable audio features to add
	 * @throws IllegalArgumentException if the feature shape is inconsistent
	 */
	public void addSource(ComposableAudioFeatures features) {
		TraversalPolicy featureShape = getFeatureShape();
		if (featureShape != null && !featureShape.equals(features.getFeatureShape())) {
			throw new IllegalArgumentException();
		}

		this.features.add(features);
	}

	/**
	 * Destroys all currently registered sources and clears the source list.
	 */
	public void clearSources() {
		this.features.forEach(ComposableAudioFeatures::destroy);
		this.features.clear();
	}

	/**
	 * Get the interpolated latent features (before decoding to audio).
	 *
	 * <p>
	 * In a {@link #setNormalizedBlend(boolean) normalized blend} each blended source's
	 * {@link ComposableAudioFeatures#getScore(Producer) score} is built once and read both
	 * by the per-cell reference, the largest score, and by that source's softmax term. The
	 * reference is subtracted before exponentiating so that no term overflows; it does not
	 * change the proportions. Each term is weighted by the source's contribution
	 * ({@link ComposableAudioFeatures#contributed(CollectionProducer)}).
	 * </p>
	 *
	 * @param value the position vector to use for interpolation
	 * @return interpolated latent features [64, 256]
	 */
	public Producer<PackedCollection> getInterpolatedLatent(Producer<PackedCollection> value) {
		if (features.isEmpty()) {
			throw new IllegalStateException("No features have been added to the composer");
		}

		if (normalizedBlend) {
			List<ComposableAudioFeatures> blended = features.stream()
					.filter(ComposableAudioFeatures::isBlended).toList();

			if (blended.isEmpty()) {
				// No source can be a share of the mean, so every source counts equally
				List<Producer<?>> terms = new ArrayList<>();
				features.forEach(source -> terms.add(source.scaled(c(1.0 / features.size()))));
				return add(terms);
			}

			List<CollectionProducer> scores = blended.stream()
					.map(source -> source.getScore(value)).toList();

			CollectionProducer reference = scores.get(0);
			for (int i = 1; i < scores.size(); i++) {
				reference = max(reference, scores.get(i));
			}

			List<Producer<?>> weights = new ArrayList<>();
			List<Producer<?>> terms = new ArrayList<>();
			for (int i = 0; i < blended.size(); i++) {
				ComposableAudioFeatures source = blended.get(i);
				// Multiplying by the reciprocal keeps a vanishing temperature finite: its
				// literal saturates to the largest finite value, while the temperature itself
				// would narrow to zero below FP64 and divide zero by zero
				CollectionProducer weight = source.contributed(exp(scores.get(i)
						.subtract(reference).multiply(c(1.0 / blendTemperature))));
				weights.add(weight);
				terms.add(source.scaled(weight));
			}

			// The highest-scoring source has weight equal to its positive contribution,
			// so the total is never zero
			return add(terms).divide(add(weights));
		}

		List<Producer<?>> components = new ArrayList<>();
		features.stream()
				.map(features -> features.getResultant(value))
				.forEach(components::add);

		// Sum all the weighted feature components
		// Each component has shape matching getFeatureShape(), typically (64, 256)
		return add(components);
	}

	@Override
	public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
		return autoencoder.decode(getInterpolatedLatent(value));
	}

	/**
	 * Samples a random length-{@code dim} merge weight vector, L2-normalized when
	 * {@link #normalizeWeights} is set. The {@link ComposableAudioFeatures} it is given
	 * to broadcasts it across the feature cells.
	 *
	 * @return the weight vector producer
	 */
	protected CollectionProducer createWeights() {
		double scale = 1.0;

		CollectionProducer rand = randn(shape(dim), scale, scale * getDeviation(), random);
		if (normalizeWeights)
			rand = normalize(rand);
		return rand;
	}

	/**
	 * Verifies that the given merge weight vector has the length this composer
	 * interpolates over, and flattens it to shape {@code (dim)}. Flattening ensures the
	 * {@link ComposableAudioFeatures} broadcasts it to every feature cell, rather than
	 * mistaking a vector shaped like {@code (1, 1, dim)} for a per-cell weight tensor.
	 *
	 * @param weights the weight vector, of any shape with {@code dim} elements
	 * @return the weight vector, shaped {@code (dim)}
	 * @throws IllegalArgumentException if the weight vector is not of length {@code dim}
	 */
	protected Producer<PackedCollection> requireWeightVector(Producer<PackedCollection> weights) {
		TraversalPolicy weightShape = shape(weights);
		if (weightShape.getTotalSize() != dim) {
			throw new IllegalArgumentException("Merge weight vector must have length " + dim
					+ " but has shape " + weightShape);
		}

		return weightShape.getDimensions() == 1 ? weights : c(weights).reshape(shape(dim));
	}

	@Override
	public void destroy() {
		if (autoencoder != null) {
			autoencoder.destroy();
		}

		if (features != null) {
			features.forEach(ComposableAudioFeatures::destroy);
			features = null;
		}
	}
}
