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

package org.almostrealism.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelPreferences;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public interface OnnxFeatures extends CodeFeatures {
	boolean enableCoreMl = false;

	default OrtEnvironment getOnnxEnvironment() {
		throw new UnsupportedOperationException();
	}

	default TraversalPolicy shape(TensorInfo info) {
		return new TraversalPolicy(info.getShape());
	}

	/**
	 * Converts an {@link OnnxTensor} to a {@link PackedCollection}.
	 */
	default PackedCollection pack(OnnxTensor tensor) {
		FloatBuffer buffer = tensor.getFloatBuffer();
		float[] data = new float[buffer.capacity()];
		buffer.get(data);

		PackedCollection result = new PackedCollection(shape(tensor.getInfo()));
		result.setMem(0, data);
		return result;
	}

	default Map<String, PackedCollection> pack(Map<String, OnnxTensor> tensors) throws OrtException {
		Map<String, PackedCollection> result = new HashMap<>();
		tensors.forEach((key, value) -> result.put(key, pack(value)));
		return result;
	}

	/**
	 * Converts a {@link PackedCollection} to an {@link OnnxTensor}.
	 *
	 * @param env The OrtEnvironment to use for creating the tensor.
	 * @param collection The {@link PackedCollection} to convert.
	 * @return An {@link OnnxTensor} representing the {@link PackedCollection}.
	 * @throws HardwareException If there is an error creating the tensor.
	 */
	default OnnxTensor toOnnx(OrtEnvironment env, PackedCollection collection) {
		try {
			return OnnxTensor.createTensor(env,
					FloatBuffer.wrap(Objects.requireNonNull(collection).toFloatArray()),
					collection.getShape().extentLong());
		} catch (OrtException e) {
			throw new HardwareException("Failed to create tensor", e);
		}
	}

	default OnnxTensor toOnnx(PackedCollection collection) {
		return toOnnx(getOnnxEnvironment(), collection);
	}

	default OnnxTensor packOnnx(TraversalPolicy shape, float... data) {
		return packOnnx(getOnnxEnvironment(), shape, data);
	}

	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, float... data) {
		return packOnnx(env, shape, FloatBuffer.wrap(data));
	}

	default OnnxTensor packOnnx(TraversalPolicy shape, FloatBuffer data) {
		return packOnnx(getOnnxEnvironment(), shape, data);
	}

	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, FloatBuffer data) {
		try {
			return OnnxTensor.createTensor(env, data,  shape.extentLong());
		} catch (OrtException e) {
			throw new HardwareException("Failed to create tensor", e);
		}
	}


	default OnnxTensor packOnnx(TraversalPolicy shape, long... data) {
		return packOnnx(getOnnxEnvironment(), shape, data);
	}

	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, long... data) {
		return packOnnx(env, shape, LongBuffer.wrap(data));
	}

	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, LongBuffer data) {
		try {
			return OnnxTensor.createTensor(env, data, shape.extentLong());
		} catch (OrtException e) {
			throw new HardwareException("Failed to create tensor", e);
		}
	}

	static OrtSession.SessionOptions defaultOptions() {
		try {
			OrtSession.SessionOptions options = new OrtSession.SessionOptions();
			options.setIntraOpNumThreads(KernelPreferences.getCpuParallelism());
			options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
			if (enableCoreMl)
				options.addCoreML();
			return options;
		} catch (OrtException e) {
			throw new HardwareException("Failed to create ONNX session options", e);
		}
	}
}
