/*
 * Copyright 2026 Michael Murray
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
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;

/**
 * AutoEncoder implementation that wraps CompiledModel instances for encoder and decoder.
 *
 * <p>This class provides a bridge between the {@link CompiledModel} infrastructure
 * (used for native/HPC implementations like OobleckEncoder/OobleckDecoder) and the
 * {@link AutoEncoder} interface used by audio generation systems.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Build encoder and decoder models
 * OobleckEncoder encoderBlock = new OobleckEncoder(weights, batchSize, audioLength);
 * OobleckDecoder decoderBlock = new OobleckDecoder(weights, batchSize, latentLength);
 *
 * Model encoderModel = new Model(audioShape);
 * encoderModel.add(encoderBlock);
 *
 * Model decoderModel = new Model(latentShape);
 * decoderModel.add(decoderBlock);
 *
 * // Create AutoEncoder wrapper
 * AutoEncoder autoEncoder = new CompiledModelAutoEncoder(
 *     encoderModel.compile(false),
 *     decoderModel.compile(false),
 *     44100,   // audio sample rate
 *     21.5,    // latent sample rate
 *     11.0     // max duration
 * );
 * }</pre>
 *
 * @see AutoEncoder
 * @see CompiledModel
 * @author Michael Murray
 */
public class CompiledModelAutoEncoder implements AutoEncoder, CodeFeatures {

	private final CompiledModel encoder;
	private final CompiledModel decoder;
	private final double sampleRate;
	private final double latentSampleRate;
	private final double maxDuration;

	/**
	 * Creates an AutoEncoder from compiled encoder and decoder models.
	 *
	 * @param encoder Compiled encoder model
	 * @param decoder Compiled decoder model
	 * @param sampleRate Audio sample rate (e.g., 44100)
	 * @param latentSampleRate Latent sample rate (samples per second in latent space)
	 * @param maxDuration Maximum audio duration in seconds
	 */
	public CompiledModelAutoEncoder(CompiledModel encoder, CompiledModel decoder,
									double sampleRate, double latentSampleRate,
									double maxDuration) {
		this.encoder = encoder;
		this.decoder = decoder;
		this.sampleRate = sampleRate;
		this.latentSampleRate = latentSampleRate;
		this.maxDuration = maxDuration;
	}

	/**
	 * Creates an AutoEncoder with only a decoder (for generation-only use cases).
	 *
	 * @param decoder Compiled decoder model
	 * @param sampleRate Audio sample rate (e.g., 44100)
	 * @param latentSampleRate Latent sample rate
	 * @param maxDuration Maximum audio duration in seconds
	 */
	public CompiledModelAutoEncoder(CompiledModel decoder,
									double sampleRate, double latentSampleRate,
									double maxDuration) {
		this(null, decoder, sampleRate, latentSampleRate, maxDuration);
	}

	@Override
	public double getSampleRate() {
		return sampleRate;
	}

	@Override
	public double getLatentSampleRate() {
		return latentSampleRate;
	}

	@Override
	public double getMaximumDuration() {
		return maxDuration;
	}

	@Override
	public Producer<PackedCollection> encode(Producer<PackedCollection> input) {
		if (encoder == null) {
			throw new UnsupportedOperationException("This AutoEncoder does not have an encoder");
		}
		return () -> args -> {
			PackedCollection in = input.get().evaluate(args);
			return encoder.forward(in);
		};
	}

	@Override
	public Producer<PackedCollection> decode(Producer<PackedCollection> latent) {
		return () -> args -> {
			PackedCollection lat = latent.get().evaluate(args);
			return decoder.forward(lat);
		};
	}

	/**
	 * Returns the underlying encoder model, or null if not available.
	 *
	 * @return The encoder CompiledModel
	 */
	public CompiledModel getEncoder() {
		return encoder;
	}

	/**
	 * Returns the underlying decoder model.
	 *
	 * @return The decoder CompiledModel
	 */
	public CompiledModel getDecoder() {
		return decoder;
	}

	@Override
	public void destroy() {
		if (encoder != null) {
			encoder.destroy();
		}
		if (decoder != null) {
			decoder.destroy();
		}
	}
}
