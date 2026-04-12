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

package org.almostrealism.ml.audio;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.ml.OnnxFeatures;
import org.almostrealism.persist.assets.AssetGroup;

import java.util.HashMap;
import java.util.Map;

/**
 * ONNX Runtime implementation of {@link AudioAttentionConditioner}.
 * <p>
 * This class runs the conditioning model (typically T5-based) via ONNX Runtime
 * to produce cross-attention inputs for diffusion-based audio generation.
 */
public class OnnxAudioConditioner implements AudioAttentionConditioner, OnnxFeatures {

	/** Sequence length used when padding or truncating T5 token id arrays. */
	protected static final int T5_SEQ_LENGTH = 128;

	/** The ONNX Runtime environment used to create and run the conditioner session. */
	private final OrtEnvironment env;

	/** The ONNX session that runs the conditioner (T5-based) model. */
	private final OrtSession conditionersSession;

	/** Whether this instance created (and therefore owns) the {@link OrtEnvironment}. */
	private final boolean ownsEnvironment;

	/**
	 * Creates an OnnxAudioConditioner with a shared ORT environment.
	 *
	 * @param env the shared ORT environment
	 * @param options session options for the conditioner model
	 * @param conditionersModelPath path to the conditioners ONNX model
	 * @throws OrtException if ONNX session creation fails
	 */
	public OnnxAudioConditioner(OrtEnvironment env,
								OrtSession.SessionOptions options,
								String conditionersModelPath) throws OrtException {
		this.env = env;
		this.ownsEnvironment = false;
		this.conditionersSession = env.createSession(conditionersModelPath, options);
	}

	/**
	 * Creates an OnnxAudioConditioner from an asset group.
	 *
	 * @param assets asset group containing "conditioners.onnx"
	 * @throws OrtException if ONNX session creation fails
	 */
	public OnnxAudioConditioner(AssetGroup assets) throws OrtException {
		this.env = OrtEnvironment.getEnvironment();
		this.ownsEnvironment = true;

		OrtSession.SessionOptions options = OnnxFeatures.defaultOptions();
		this.conditionersSession = env.createSession(assets.getAssetPath("conditioners.onnx"), options);
	}

	/** {@inheritDoc} */
	@Override
	public OrtEnvironment getOnnxEnvironment() {
		return env;
	}

	/**
	 * Returns the ONNX session for the conditioners model.
	 *
	 * @return the conditioners {@link OrtSession}
	 */
	public OrtSession getConditionersSession() {
		return conditionersSession;
	}

	/**
	 * Runs the T5-based conditioner model to produce attention inputs for the diffusion model.
	 *
	 * <p>The token id array is padded or truncated to {@link #T5_SEQ_LENGTH} tokens.
	 * An attention mask is generated from the actual token count. The model returns
	 * cross-attention input, cross-attention mask, and a global conditioning vector.
	 *
	 * @param ids             token ids from a tokenizer, padded/truncated to {@link #T5_SEQ_LENGTH}
	 * @param durationSeconds target audio duration in seconds passed to the conditioner
	 * @return a {@link ConditionerOutput} containing the three conditioning tensors
	 * @throws org.almostrealism.hardware.HardwareException if the ONNX session fails
	 */
	@Override
	public ConditionerOutput runConditioners(long[] ids, double durationSeconds) {
		long[] paddedIds = new long[T5_SEQ_LENGTH];
		long[] attentionMask = new long[T5_SEQ_LENGTH];

		int tokenCount = Math.min(ids.length, T5_SEQ_LENGTH);
		System.arraycopy(ids, 0, paddedIds, 0, tokenCount);
		for (int i = 0; i < tokenCount; i++) {
			attentionMask[i] = 1;
		}

		Map<String, OnnxTensor> inputs = new HashMap<>();
		inputs.put("input_ids", packOnnx(shape(1, T5_SEQ_LENGTH), paddedIds));
		inputs.put("attention_mask", packOnnx(shape(1, T5_SEQ_LENGTH), attentionMask));
		inputs.put("seconds_total", packOnnx(shape(1), (float) durationSeconds));

		Map<String, OnnxTensor> outputs = new HashMap<>();

		try {
			OrtSession.Result result = conditionersSession.run(inputs);
			outputs.put("cross_attention_input", (OnnxTensor) result.get(0));
			outputs.put("cross_attention_masks", (OnnxTensor) result.get(1));
			outputs.put("global_cond", (OnnxTensor) result.get(2));

			Map<String, PackedCollection> packed = pack(outputs);
			return new ConditionerOutput(
					packed.get("cross_attention_input"),
					packed.get("cross_attention_masks"),
					packed.get("global_cond")
			);
		} catch (OrtException e) {
			throw new HardwareException("Failed to run conditioners", e);
		} finally {
			inputs.forEach((key, tensor) -> tensor.close());
			outputs.forEach((key, tensor) -> tensor.close());
		}
	}

	/**
	 * Releases ONNX Runtime resources held by this conditioner.
	 *
	 * <p>Closes the conditioner session. If this instance owns the {@link OrtEnvironment}
	 * (i.e., it was created via the asset-group constructor), the environment is also closed.
	 *
	 * @throws org.almostrealism.hardware.HardwareException if the session cannot be closed
	 */
	@Override
	public void destroy() {
		if (conditionersSession != null) {
			try {
				conditionersSession.close();
			} catch (OrtException e) {
				throw new HardwareException("Unable to close conditioner session", e);
			}
		}

		if (ownsEnvironment && env != null) {
			env.close();
		}
	}
}
