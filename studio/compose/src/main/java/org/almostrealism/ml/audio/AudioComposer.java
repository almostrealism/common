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

package org.almostrealism.ml.audio;

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

public class AudioComposer implements Factor<PackedCollection>, Destroyable, CodeFeatures {
	public static boolean normalizeWeights = true;

	private final AutoEncoder autoencoder;
	private final int dim;

	private List<ComposableAudioFeatures> features;

	private Random random;
	private double deviation;

	public AudioComposer(AutoEncoder autoencoder, int dim) {
		this(autoencoder, dim, System.currentTimeMillis());
	}

	public AudioComposer(AutoEncoder autoencoder, int dim, long seed) {
		this(autoencoder, dim, new Random(seed));
	}

	public AudioComposer(AutoEncoder autoencoder, int dim, Random random) {
		this.autoencoder = autoencoder;
		this.dim = dim;
		this.random = random;
		this.features = new ArrayList<>();
		this.deviation = 1.0;
	}

	public double getDeviation() { return deviation; }
	public void setDeviation(double deviation) {
		this.deviation = deviation;
	}

	public double getMaximumAudioDuration() {
		return autoencoder.getMaximumDuration();
	}

	public int getEmbeddingDimension() { return dim; }

	public double getSampleRate() {
		return autoencoder.getSampleRate();
	}

	public void setWeightSeed(long seed) {
		this.random = new Random(seed);
	}

	public TraversalPolicy getFeatureShape() {
		if (features.isEmpty()) return null;
		return features.get(features.size() - 1).getFeatureShape();
	}

	public void addAudio(Producer<PackedCollection> audio) {
		addSource(autoencoder.encode(audio));
	}

	public void addSource(Producer<PackedCollection> features) {
		addSource(new ComposableAudioFeatures(features, createWeights(features)));
	}

	public void addSource(ComposableAudioFeatures features) {
		TraversalPolicy featureShape = getFeatureShape();
		if (featureShape != null && !featureShape.equals(features.getFeatureShape())) {
			throw new IllegalArgumentException();
		}

		this.features.add(features);
	}

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
