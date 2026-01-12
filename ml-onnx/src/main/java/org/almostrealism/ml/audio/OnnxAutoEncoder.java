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
import org.almostrealism.persistence.AssetGroup;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

public class OnnxAutoEncoder implements AutoEncoder, OnnxFeatures {
	public static double MAX_DURATION = 11.0;
	public static final int SAMPLE_RATE = 44100;
	public static final int FRAME_COUNT = 2048 * 256;
	public static final int LATENT_DIMENSIONS = 64;

	private final OrtEnvironment env;
	private final OrtSession encoderSession;
	private final OrtSession decoderSession;
	private boolean destroyEnv;

	public OnnxAutoEncoder(AssetGroup assets) throws OrtException {
		this(assets.getAssetPath("encoder.onnx"),
				assets.getAssetPath("decoder.onnx"));
	}

	public OnnxAutoEncoder(String encoderModelPath,
						   String decoderModelPath) throws OrtException {
		this(OrtEnvironment.getEnvironment(),
				OnnxFeatures.defaultOptions(),
				encoderModelPath, decoderModelPath);
		this.destroyEnv = true;
	}

	public OnnxAutoEncoder(OrtEnvironment environment,
						   OrtSession.SessionOptions options,
						   String encoderModelPath,
						   String decoderModelPath) throws OrtException {
		env = environment;
		encoderSession = env.createSession(encoderModelPath, options);
		decoderSession = env.createSession(decoderModelPath, options);
	}

	@Override
	public OrtEnvironment getOnnxEnvironment() { return env; }

	@Override
	public double getSampleRate() { return SAMPLE_RATE; }

	@Override
	public double getLatentSampleRate() {
		double ratio = 256.0 / FRAME_COUNT;
		return getSampleRate() * ratio;
	}

	@Override
	public double getMaximumDuration() { return MAX_DURATION; }

	@Override
	public Producer<PackedCollection> decode(Producer<PackedCollection> latent) {
		if (!shape(latent).equalsIgnoreAxis(shape(LATENT_DIMENSIONS, 256))) {
			throw new IllegalArgumentException(shape(latent).toStringDetail());
		}

		return func(shape(2, FRAME_COUNT),
				in -> args -> decode(in[0]), latent);
	}

	@Override
	public Producer<PackedCollection> encode(Producer<PackedCollection> input) {
		return func(shape(LATENT_DIMENSIONS, 256),
				in -> args -> encode(in[0]), input);
	}

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
