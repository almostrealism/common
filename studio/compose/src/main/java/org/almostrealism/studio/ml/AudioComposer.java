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
 * Each source is encoded via an {@link AutoEncoder} and assigned random weight vectors
 * that are used to blend the sources based on a supplied position vector.
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
	}

	/** Returns the standard deviation used to sample weight vectors. */
	public double getDeviation() { return deviation; }

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
		addSource(new ComposableAudioFeatures(features, createWeights(features)));
	}

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
	 * @param value the position vector to use for interpolation
	 * @return interpolated latent features [64, 256]
	 */
	public Producer<PackedCollection> getInterpolatedLatent(Producer<PackedCollection> value) {
		if (features.isEmpty()) {
			throw new IllegalStateException("No features have been added to the composer");
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
	 * Creates a random weight tensor for the given feature producer, repeated across
	 * the feature's bin and time dimensions.
	 *
	 * @param features the feature producer whose shape determines the weight repetition
	 * @return the weight tensor producer
	 */
	protected CollectionProducer createWeights(Producer<PackedCollection> features) {
		double scale = 1.0;
		int bins = shape(features).length(0);
		int time = shape(features).length(1);

		CollectionProducer rand = randn(shape(dim), scale, scale * getDeviation(), random);
		if (normalizeWeights)
			rand = normalize(rand);
		return rand.repeat(bins).repeat(time);
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
