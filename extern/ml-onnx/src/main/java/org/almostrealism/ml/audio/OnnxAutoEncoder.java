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

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.ml.OnnxFeatures;
import org.almostrealism.persist.assets.AssetGroup;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * ONNX Runtime implementation of the {@link AutoEncoder} interface for audio signals.
 *
 * <p>Wraps a pair of ONNX encoder and decoder models to compress stereo audio into a
 * compact latent representation and reconstruct audio from that latent. The encoder
 * accepts stereo audio at {@link #SAMPLE_RATE} Hz and produces a latent tensor of
 * shape {@code (LATENT_DIMENSIONS, 256)}; the decoder reverses the process.
 *
 * <p>All ONNX Runtime types are fully encapsulated within this class and do not leak
 * into higher-level modules.
 *
 * @see AutoEncoder
 * @see OnnxFeatures
 */
public class OnnxAutoEncoder implements AutoEncoder, OnnxFeatures {

	/** Maximum audio duration in seconds that the encoder can process. */
	public static double MAX_DURATION = 11.0;

	/** Sample rate of the raw stereo audio accepted by the encoder, in Hz. */
	public static final int SAMPLE_RATE = 44100;

	/**
	 * Number of audio frames per channel that the encoder expects.
	 * Audio is zero-padded or truncated to this length before encoding.
	 */
	public static final int FRAME_COUNT = 2048 * 256;

	/** Number of channels in the latent representation produced by the encoder. */
	public static final int LATENT_DIMENSIONS = 64;

	/** The shared ONNX Runtime environment used by both sessions. */
	private final OrtEnvironment env;

	/** The ONNX session that runs the encoder model. */
	private final OrtSession encoderSession;

	/** The ONNX session that runs the decoder model. */
	private final OrtSession decoderSession;

	/** Whether this instance owns the {@link OrtEnvironment} and should close it on destroy. */
	private boolean destroyEnv;

	/**
	 * Creates an {@code OnnxAutoEncoder} by loading encoder and decoder models
	 * from the given asset group.
	 *
	 * @param assets the asset group containing {@code encoder.onnx} and {@code decoder.onnx}
	 * @throws OrtException if either ONNX session cannot be created
	 */
	public OnnxAutoEncoder(AssetGroup assets) throws OrtException {
		this(assets.getAssetPath("encoder.onnx"),
				assets.getAssetPath("decoder.onnx"));
	}

	/**
	 * Creates an {@code OnnxAutoEncoder} from explicit model file paths.
	 * A new {@link OrtEnvironment} is created and owned by this instance.
	 *
	 * @param encoderModelPath path to the encoder ONNX model file
	 * @param decoderModelPath path to the decoder ONNX model file
	 * @throws OrtException if either ONNX session cannot be created
	 */
	public OnnxAutoEncoder(String encoderModelPath,
						   String decoderModelPath) throws OrtException {
		this(OrtEnvironment.getEnvironment(),
				OnnxFeatures.defaultOptions(),
				encoderModelPath, decoderModelPath);
		this.destroyEnv = true;
	}

	/**
	 * Creates an {@code OnnxAutoEncoder} from a shared environment and explicit model paths.
	 * The caller retains ownership of the {@link OrtEnvironment}; it will not be closed
	 * when {@link #destroy()} is called.
	 *
	 * @param environment      the shared ONNX Runtime environment
	 * @param options          session options to apply to both encoder and decoder sessions
	 * @param encoderModelPath path to the encoder ONNX model file
	 * @param decoderModelPath path to the decoder ONNX model file
	 * @throws OrtException if either ONNX session cannot be created
	 */
	public OnnxAutoEncoder(OrtEnvironment environment,
						   OrtSession.SessionOptions options,
						   String encoderModelPath,
						   String decoderModelPath) throws OrtException {
		env = environment;
		encoderSession = env.createSession(encoderModelPath, options);
		decoderSession = env.createSession(decoderModelPath, options);
	}

	/** {@inheritDoc} */
	@Override
	public OrtEnvironment getOnnxEnvironment() { return env; }

	/** {@inheritDoc} */
	@Override
	public double getSampleRate() { return SAMPLE_RATE; }

	/**
	 * Returns the sample rate of the latent representation in Hz.
	 * Computed as {@code getSampleRate() * (256 / FRAME_COUNT)}.
	 *
	 * @return latent sample rate in Hz
	 */
	@Override
	public double getLatentSampleRate() {
		double ratio = 256.0 / FRAME_COUNT;
		return getSampleRate() * ratio;
	}

	/** {@inheritDoc} */
	@Override
	public double getMaximumDuration() { return MAX_DURATION; }

	/**
	 * Decodes a latent tensor into stereo audio using the ONNX decoder model.
	 *
	 * <p>The latent must have a shape compatible with {@code (LATENT_DIMENSIONS, 256)},
	 * ignoring any leading batch axis. The output shape is {@code (2, FRAME_COUNT)}.
	 *
	 * @param latent producer for the latent tensor to decode
	 * @return producer for the reconstructed stereo audio tensor of shape {@code (2, FRAME_COUNT)}
	 * @throws IllegalArgumentException if the latent shape is incompatible
	 */
	@Override
	public Producer<PackedCollection> decode(Producer<PackedCollection> latent) {
		if (!shape(latent).equalsIgnoreAxis(shape(LATENT_DIMENSIONS, 256))) {
			throw new IllegalArgumentException(shape(latent).toStringDetail());
		}

		return func(shape(2, FRAME_COUNT),
				in -> args -> decode(in[0]), latent);
	}

	/**
	 * Encodes raw audio into a latent representation using the ONNX encoder model.
	 *
	 * <p>The output shape is {@code (LATENT_DIMENSIONS, 256)}.
	 *
	 * @param input producer for the raw audio tensor
	 * @return producer for the compressed latent tensor of shape {@code (LATENT_DIMENSIONS, 256)}
	 */
	@Override
	public Producer<PackedCollection> encode(Producer<PackedCollection> input) {
		return func(shape(LATENT_DIMENSIONS, 256),
				in -> args -> encode(in[0]), input);
	}

	/**
	 * Eagerly encodes a {@link PackedCollection} containing stereo audio into a latent tensor.
	 *
	 * <p>Accepts mono ({@code shape (N,)} or {@code shape (1, N)}) or stereo
	 * ({@code shape (2, N)}) audio. Audio is zero-padded or truncated to
	 * {@link #FRAME_COUNT} frames per channel before encoding.
	 *
	 * @param audio the audio data to encode
	 * @return the latent representation produced by the encoder
	 * @throws IllegalArgumentException if the audio shape is not mono or stereo
	 * @throws org.almostrealism.hardware.HardwareException if the ONNX session fails
	 */
	public PackedCollection encode(PackedCollection audio) {
		Map<String, OnnxTensor> inputs = new HashMap<>();

		float[] leftData;
		float[] rightData;

		if (audio.getShape().getDimensions() == 1 || audio.getShape().length(0) == 1) {
			leftData = audio.toFloatArray(0, Math.min(audio.getMemLength(), FRAME_COUNT));
			rightData = leftData;
		} else if (audio.getShape().length(0) == 2) {
			int frames = audio.getShape().length(1);
			leftData = audio.toFloatArray(0, Math.min(frames, FRAME_COUNT));
			rightData = audio.toFloatArray(frames, Math.min(frames, FRAME_COUNT));
		} else {
			throw new IllegalArgumentException(audio.getShape() +
					" is not a valid shape for audio data");
		}

		FloatBuffer buf = FloatBuffer.allocate(2 * FRAME_COUNT);

		buf.put(leftData);
		if (leftData.length < FRAME_COUNT)
			buf.put(new float[FRAME_COUNT - leftData.length]);

		buf.put(rightData);
		if (rightData.length < FRAME_COUNT)
			buf.put(new float[FRAME_COUNT - rightData.length]);

		inputs.put("audio",
				packOnnx(shape(2, FRAME_COUNT), buf.position(0)));


		OnnxTensor latentTensor = null;

		try {
			OrtSession.Result result = encoderSession.run(inputs);
			latentTensor = (OnnxTensor) result.get(0);
			return pack(latentTensor);
		} catch (OrtException e) {
			throw new HardwareException("Unable to run ONNX encoder", e);
		} finally {
			if (latentTensor != null)
				latentTensor.close();
			inputs.values().forEach(OnnxTensor::close);
		}
	}

	/**
	 * Eagerly decodes a latent {@link PackedCollection} back to stereo audio.
	 *
	 * <p>The latent is reshaped to a 3-dimensional tensor before being passed
	 * to the decoder ONNX session. The returned collection contains the
	 * reconstructed stereo audio.
	 *
	 * @param latent the latent tensor to decode
	 * @return the reconstructed audio data produced by the decoder
	 * @throws org.almostrealism.hardware.HardwareException if the ONNX session fails
	 */
	public PackedCollection decode(PackedCollection latent) {
		TraversalPolicy shape = padDimensions(latent.getShape(), 1, 3);

		Map<String, OnnxTensor> inputs = new HashMap<>();
		inputs.put("sampled", toOnnx(latent.reshape(shape)));

		OnnxTensor audioTensor = null;

		try {
			OrtSession.Result result = decoderSession.run(inputs);
			audioTensor = (OnnxTensor) result.get(0);
			return pack(audioTensor);
		} catch (OrtException e) {
			throw new HardwareException("Unable to run ONNX decoder", e);
		} finally {
			if (audioTensor != null)
				audioTensor.close();
		}
	}


	/**
	 * Releases all ONNX Runtime resources held by this encoder.
	 *
	 * <p>Closes the encoder and decoder sessions. If this instance owns the
	 * {@link OrtEnvironment} (i.e., it was created via the two-argument or
	 * asset-group constructors), the environment is also closed.
	 *
	 * @throws org.almostrealism.hardware.HardwareException if any session cannot be closed
	 */
	@Override
	public void destroy() {
		try {
			encoderSession.close();
			decoderSession.close();

			if (destroyEnv) {
				env.close();
			}
		} catch (OrtException e) {
			throw new HardwareException("Failed to close ONNX sessions", e);
		}
	}
}
