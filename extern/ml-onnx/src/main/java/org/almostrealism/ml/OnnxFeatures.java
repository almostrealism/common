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

/**
 * Mixin interface providing ONNX Runtime utility methods for classes that
 * interact with the ORT inference engine.
 *
 * <p>Implementors gain convenience methods for converting between
 * {@link org.almostrealism.collect.PackedCollection} (the AR framework's
 * GPU-resident tensor type) and {@link ai.onnxruntime.OnnxTensor} objects,
 * as well as helpers for packaging raw float and long arrays into ONNX
 * tensors with a given {@link io.almostrealism.collect.TraversalPolicy} shape.
 *
 * <p>ONNX Runtime types (OnnxTensor, OrtSession, OrtEnvironment) must not
 * leak out of classes that implement this interface into higher-level modules.
 *
 * @see org.almostrealism.CodeFeatures
 */
public interface OnnxFeatures extends CodeFeatures {

	/**
	 * Whether to enable the CoreML execution provider when creating ONNX sessions.
	 * Set to {@code true} only on Apple Silicon where CoreML acceleration is available.
	 */
	boolean enableCoreMl = false;

	/**
	 * Returns the {@link OrtEnvironment} used by this object to create ONNX tensors.
	 *
	 * @return the ONNX Runtime environment
	 * @throws UnsupportedOperationException if not overridden by the implementing class
	 */
	default OrtEnvironment getOnnxEnvironment() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the shape of an ONNX tensor as a {@link TraversalPolicy}.
	 *
	 * @param info the tensor info describing the tensor's shape
	 * @return a {@link TraversalPolicy} matching the tensor dimensions
	 */
	default TraversalPolicy shape(TensorInfo info) {
		return new TraversalPolicy(info.getShape());
	}

	/**
	 * Converts an {@link OnnxTensor} to a {@link PackedCollection}.
	 *
	 * <p>The tensor's float data is read into a Java array and stored in a new
	 * {@link PackedCollection} whose shape matches the tensor's dimension information.
	 *
	 * @param tensor the ONNX tensor to convert; its shape is used for the result
	 * @return a new {@link PackedCollection} containing a copy of the tensor's float data
	 */
	default PackedCollection pack(OnnxTensor tensor) {
		FloatBuffer buffer = tensor.getFloatBuffer();
		float[] data = new float[buffer.capacity()];
		buffer.get(data);

		PackedCollection result = new PackedCollection(shape(tensor.getInfo()));
		result.setMem(0, data);
		return result;
	}

	/**
	 * Converts a map of named {@link OnnxTensor} objects to a map of
	 * {@link PackedCollection} objects using {@link #pack(OnnxTensor)}.
	 *
	 * @param tensors a map from output name to {@link OnnxTensor}
	 * @return a new map from output name to the corresponding {@link PackedCollection}
	 * @throws OrtException if any tensor conversion fails
	 */
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

	/**
	 * Converts a {@link PackedCollection} to an {@link OnnxTensor} using the environment
	 * returned by {@link #getOnnxEnvironment()}.
	 *
	 * @param collection the {@link PackedCollection} to convert
	 * @return an {@link OnnxTensor} wrapping the collection's float data
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor toOnnx(PackedCollection collection) {
		return toOnnx(getOnnxEnvironment(), collection);
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a float array,
	 * using the environment returned by {@link #getOnnxEnvironment()}.
	 *
	 * @param shape the desired tensor shape
	 * @param data  the float values to wrap in the tensor
	 * @return an {@link OnnxTensor} holding the provided float data
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(TraversalPolicy shape, float... data) {
		return packOnnx(getOnnxEnvironment(), shape, data);
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a float array and
	 * explicit environment.
	 *
	 * @param env   the ONNX Runtime environment to use
	 * @param shape the desired tensor shape
	 * @param data  the float values to wrap in the tensor
	 * @return an {@link OnnxTensor} holding the provided float data
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, float... data) {
		return packOnnx(env, shape, FloatBuffer.wrap(data));
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a {@link FloatBuffer},
	 * using the environment returned by {@link #getOnnxEnvironment()}.
	 *
	 * @param shape the desired tensor shape
	 * @param data  the float buffer to wrap in the tensor
	 * @return an {@link OnnxTensor} backed by the provided buffer
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(TraversalPolicy shape, FloatBuffer data) {
		return packOnnx(getOnnxEnvironment(), shape, data);
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a {@link FloatBuffer}
	 * and explicit environment.
	 *
	 * @param env   the ONNX Runtime environment to use
	 * @param shape the desired tensor shape
	 * @param data  the float buffer to wrap in the tensor
	 * @return an {@link OnnxTensor} backed by the provided buffer
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, FloatBuffer data) {
		try {
			return OnnxTensor.createTensor(env, data,  shape.extentLong());
		} catch (OrtException e) {
			throw new HardwareException("Failed to create tensor", e);
		}
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a long array,
	 * using the environment returned by {@link #getOnnxEnvironment()}.
	 *
	 * @param shape the desired tensor shape
	 * @param data  the long values (e.g., token ids) to wrap in the tensor
	 * @return an {@link OnnxTensor} holding the provided long data
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(TraversalPolicy shape, long... data) {
		return packOnnx(getOnnxEnvironment(), shape, data);
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a long array
	 * and explicit environment.
	 *
	 * @param env   the ONNX Runtime environment to use
	 * @param shape the desired tensor shape
	 * @param data  the long values (e.g., token ids) to wrap in the tensor
	 * @return an {@link OnnxTensor} holding the provided long data
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, long... data) {
		return packOnnx(env, shape, LongBuffer.wrap(data));
	}

	/**
	 * Creates an {@link OnnxTensor} with the given shape from a {@link LongBuffer}
	 * and explicit environment.
	 *
	 * @param env   the ONNX Runtime environment to use
	 * @param shape the desired tensor shape
	 * @param data  the long buffer (e.g., attention masks) to wrap in the tensor
	 * @return an {@link OnnxTensor} backed by the provided buffer
	 * @throws HardwareException if the tensor cannot be created
	 */
	default OnnxTensor packOnnx(OrtEnvironment env, TraversalPolicy shape, LongBuffer data) {
		try {
			return OnnxTensor.createTensor(env, data, shape.extentLong());
		} catch (OrtException e) {
			throw new HardwareException("Failed to create tensor", e);
		}
	}

	/**
	 * Creates a default set of {@link OrtSession.SessionOptions} suitable for inference.
	 *
	 * <p>Configures intra-op thread count from {@link KernelPreferences#getCpuParallelism()},
	 * enables all ORT graph-level optimizations, and optionally enables the CoreML
	 * execution provider when {@link #enableCoreMl} is {@code true}.
	 *
	 * @return a configured {@link OrtSession.SessionOptions} instance
	 * @throws HardwareException if the options object cannot be created
	 */
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
