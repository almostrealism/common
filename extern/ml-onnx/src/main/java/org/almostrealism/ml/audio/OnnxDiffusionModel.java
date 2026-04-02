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

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.OnnxFeatures;

import java.util.HashMap;
import java.util.Map;

/**
 * ONNX Runtime implementation of the {@link DiffusionModel} interface for audio generation.
 *
 * <p>Wraps a single ONNX diffusion transformer model (DiT) that accepts a noisy audio
 * latent {@code x}, a timestep tensor {@code t}, cross-attention conditioning from a
 * text encoder, and a global conditioning vector, and returns a noise or velocity
 * prediction used by the diffusion sampler.
 *
 * <p>All ONNX Runtime types are fully encapsulated within this class.
 *
 * @see DiffusionModel
 * @see OnnxFeatures
 */
public class OnnxDiffusionModel implements DiffusionModel, OnnxFeatures {

	/** The ONNX Runtime environment used to create the model session. */
	private final OrtEnvironment env;

	/** The ONNX session that runs the diffusion transformer model. */
	private final OrtSession session;

	/**
	 * Creates an {@code OnnxDiffusionModel} from a shared environment, session options,
	 * and the path to the DiT ONNX model file.
	 *
	 * @param env       the shared ONNX Runtime environment
	 * @param options   session options for the model
	 * @param modelFile path to the diffusion transformer ONNX model file
	 * @throws OrtException if the ONNX session cannot be created
	 */
	public OnnxDiffusionModel(OrtEnvironment env, OrtSession.SessionOptions options, String modelFile) throws OrtException {
		this.env = env;
		this.session = env.createSession(modelFile, options);
	}

	/**
	 * Runs the DiT model forward pass via ONNX Runtime.
	 *
	 * <p>All four inputs are converted to {@link ai.onnxruntime.OnnxTensor} objects,
	 * the session is run, and the single output tensor is unpacked into a
	 * {@link org.almostrealism.collect.PackedCollection}.
	 *
	 * @param x            current noisy audio latent
	 * @param t            timestep tensor
	 * @param crossAttnCond cross-attention conditioning produced by the text conditioner
	 * @param globalCond   global conditioning vector (duration, style, etc.)
	 * @return the model prediction (noise or velocity) as a {@link org.almostrealism.collect.PackedCollection}
	 * @throws RuntimeException if the ONNX session fails
	 */
	public PackedCollection forward(PackedCollection x, PackedCollection t,
									   PackedCollection crossAttnCond,
									   PackedCollection globalCond) {
		Map<String, OnnxTensor> ditInputs = new HashMap<>();
		OnnxTensor ditOutput = null;

		try {
			// Prepare DiT inputs
			ditInputs.put("x", toOnnx(env, x));
			ditInputs.put("t", toOnnx(env, t));
			ditInputs.put("cross_attn_cond", toOnnx(env, crossAttnCond));
			ditInputs.put("global_cond", toOnnx(env, globalCond));

			// Run DiT model
			OrtSession.Result ditResult = session.run(ditInputs);
			ditOutput = (OnnxTensor) ditResult.get(0);
			return pack(ditOutput);
		} catch (OrtException e) {
			throw new RuntimeException("Error running DiT model", e);
		} finally {
			ditInputs.values().forEach(OnnxTensor::close);

			if (ditOutput != null) {
				ditOutput.close();
			}
		}
	}

	/**
	 * Closes the ONNX session held by this diffusion model, releasing native resources.
	 *
	 * @throws RuntimeException if the session cannot be closed
	 */
	@Override
	public void destroy() {
		try {
			session.close();
		} catch (OrtException e) {
			throw new RuntimeException(e);
		}
	}
}
