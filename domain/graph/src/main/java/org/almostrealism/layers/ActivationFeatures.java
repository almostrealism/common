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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Factor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;

import java.util.function.Function;

/**
 * Factory interface for creating activation function layers.
 *
 * <p>This interface provides methods for constructing common activation functions
 * used in neural networks, including ReLU, SiLU, GELU, Softmax, and Snake activations.</p>
 *
 * <p>Classes implementing this interface must provide an implementation of
 * {@link #layer(String, TraversalPolicy, TraversalPolicy, Factor, ComputeRequirement...)}
 * to create the actual layer instances.</p>
 *
 * @see LayerFeatures
 */
public interface ActivationFeatures extends GeometryFeatures {

	boolean enableIgnoreZero = true;
	boolean enableLogStability = true;

	/**
	 * Creates a layer with the specified properties.
	 * This method must be implemented by classes that provide layer creation functionality.
	 */
	CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
						Factor<PackedCollection> operator, ComputeRequirement... requirements);

	/**
	 * Creates a Softmax activation layer factory.
	 * Softmax normalizes the input into a probability distribution where all values
	 * sum to 1 and each value is between 0 and 1.
	 *
	 * <p>The output shape will match the input shape.</p>
	 */
	default Function<TraversalPolicy, CellularLayer> softmax() {
		return shape -> softmax(shape);
	}

	default CellularLayer softmax(int size) {
		return softmax(shape(size));
	}

	default CellularLayer softmax(TraversalPolicy shape) {
		return layer("softmax", shape, shape,
				input -> c(input).traverse(1).exp().divide(c(input).traverse(1).exp().traverse(0).sum()));
	}

	default Function<TraversalPolicy, CellularLayer> softmax(boolean subtractMax, ComputeRequirement... requirements) {
		return shape -> softmax(shape, subtractMax, requirements);
	}

	default CellularLayer softmax(TraversalPolicy shape, boolean subtractMax, ComputeRequirement... requirements) {
		if (shape.getDimensions() < 2) {
			throw new IllegalArgumentException();
		}

		int axis = shape.getDimensions() - 1;
		int seqLen = shape.length(axis);
		double eps = 1e-5;

		if (enableLogStability) {
			return layer("softmax2d", shape, shape, input -> {
				CollectionProducer max = traverse(axis, input).max();
				CollectionProducer stable =
						traverse(axis + 1, input).subtract(max.expand(seqLen));
				CollectionProducer logSum =
						stable.exp().traverse(axis).sum().log().expand(seqLen);
				return stable.subtract(logSum).exp();
			}, requirements);
		} else {
			return layer("softmax2d", shape, shape, input -> {
				CollectionProducer o = traverse(axis, input);

				if (subtractMax) {
					if (enableIgnoreZero) {
						o = o.max();
						o = o.expand(seqLen);
						o = traverse(axis + 1, input).subtractIgnoreZero(o);
					} else {
						o = o.max().add(eps);
						o = o.expand(seqLen);
						o = traverse(axis + 1, input).subtract(o);
					}
				}

				o = o.expIgnoreZero().traverse(axis);

				if (subtractMax && enableIgnoreZero) {
					o = o.divide(o.sum().expand(seqLen));
				} else {
					o = o.divide(o.sum().add(eps).expand(seqLen));
				}

				return o;
			}, requirements);
		}
	}

	default Function<TraversalPolicy, CellularLayer> logSoftmax(ComputeRequirement... requirements) {
		return shape -> logSoftmax(shape, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> logSoftmax(int size, ComputeRequirement... requirements) {
		return shape -> {
			shape = padDimensions(shape, 2);
			if (shape.length(1) != size) {
				throw new IllegalArgumentException();
			}

			return logSoftmax(shape, requirements);
		};
	}

	default CellularLayer logSoftmax(TraversalPolicy shape, ComputeRequirement... requirements) {
		shape = padDimensions(shape, 2).traverse(1);

		return layer("logSoftmax", shape, shape, input ->
						c(input).traverse(2).subtract(
								c(input).traverse(2).exp().traverse(1).sum().log()),
				requirements);
	}

	/**
	 * Creates a ReLU (Rectified Linear Unit) activation layer factory.
	 * ReLU returns max(0, x) for each element.
	 *
	 * @param requirements Optional compute requirements
	 * @return Function that creates a ReLU activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> relu(ComputeRequirement... requirements) {
		return shape -> relu(shape, requirements);
	}

	default CellularLayer relu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("relu", shape, shape, input -> rectify(input), requirements);
	}

	/**
	 * Creates a SiLU (Sigmoid Linear Unit) activation layer factory.
	 * SiLU is defined as: f(x) = x * sigmoid(x)
	 *
	 * <p>Also known as Swish activation.</p>
	 *
	 * @param requirements Optional compute requirements
	 * @return Function that creates a SiLU activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> silu(ComputeRequirement... requirements) {
		return shape -> silu(shape, requirements);
	}

	default CellularLayer silu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("silu", shape, shape, input -> multiply(traverseEach(input), sigmoid(traverseEach(input))), requirements);
	}

	/**
	 * Creates a GELU (Gaussian Error Linear Unit) activation layer factory.
	 * GELU is defined as: f(x) = 0.5 * x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 * x^3)))
	 *
	 * @param requirements Optional compute requirements
	 * @return Function that creates a GELU activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> gelu(ComputeRequirement... requirements) {
		return shape -> gelu(shape, requirements);
	}

	default CellularLayer gelu(TraversalPolicy shape, ComputeRequirement... requirements) {
		// 0.5 * x * (1 + math.tanh(sqrt(2 / pi) * (x + 0.044715 * x^3)))
		return layer("gelu", shape, shape, input -> {
			CollectionProducer x = c(input).traverseEach();
			CollectionProducer x3 = pow(x, c(3));
			CollectionProducer tanh =
					tanh(x.add(x3.multiply(c(0.044715)))
							.multiply(c(ROOT_2_BY_PI)));
			return c(0.5).multiply(x).multiply(tanh.add(c(1)));
		}, requirements);
	}

	/**
	 * Creates a Snake activation layer factory with default alpha=1.0.
	 * Snake activation is defined as: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 *
	 * <p>Snake is a learnable periodic activation function that provides smoother
	 * gradients than ReLU and is particularly effective for audio synthesis tasks.</p>
	 *
	 * @param requirements Optional compute requirements
	 * @return Function that creates a Snake activation layer for any input shape
	 * @see #snake(double, ComputeRequirement...)
	 */
	default Function<TraversalPolicy, CellularLayer> snake(ComputeRequirement... requirements) {
		return snake(1.0, requirements);
	}

	/**
	 * Creates a Snake activation layer factory with specified alpha parameter.
	 * Snake activation is defined as: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 *
	 * @param alpha The frequency parameter for the sinusoidal component (default 1.0)
	 * @param requirements Optional compute requirements
	 * @return Function that creates a Snake activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> snake(double alpha, ComputeRequirement... requirements) {
		return shape -> snake(shape, alpha, requirements);
	}

	/**
	 * Creates a Snake activation layer with specified shape and alpha parameter.
	 * Snake activation is defined as: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 *
	 * @param shape Input and output shape for the layer
	 * @param alpha The frequency parameter for the sinusoidal component
	 * @param requirements Optional compute requirements
	 * @return CellularLayer implementing Snake activation
	 */
	default CellularLayer snake(TraversalPolicy shape, double alpha, ComputeRequirement... requirements) {
		return layer("snake", shape, shape, input -> {
			CollectionProducer x = c(input).traverseEach();
			// f(x) = x + (1/alpha) * sin^2(alpha * x)
			CollectionProducer sinPart = sin(x.multiply(c(alpha)));
			CollectionProducer sinSquared = pow(sinPart, c(2.0));
			return x.add(sinSquared.multiply(c(1.0 / alpha)));
		}, requirements);
	}

	/**
	 * Creates a learnable Snake activation layer factory with per-channel alpha and beta parameters.
	 * Snake activation is defined as: f(x) = x + (1/beta) * sin^2(alpha * x)
	 *
	 * <p>This variant is used by Stable Audio Open / DAC autoencoders where alpha and beta
	 * are learned parameters stored per-channel.</p>
	 *
	 * @param alpha Per-channel frequency parameter, shape (channels,)
	 * @param beta Per-channel scaling parameter, shape (channels,)
	 * @param requirements Optional compute requirements
	 * @return Function that creates a learnable Snake activation layer for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> snake(PackedCollection alpha,
														   PackedCollection beta,
														   ComputeRequirement... requirements) {
		return shape -> snake(shape, alpha, beta, requirements);
	}

	/**
	 * Creates a learnable Snake activation layer with per-channel alpha and beta parameters.
	 * Snake activation is defined as: f(x) = x + (1/beta) * sin^2(alpha * x)
	 *
	 * <p>For input shape (batch, channels, length), alpha and beta should have shape (channels,).
	 * The activation is applied element-wise with channel-specific parameters.</p>
	 *
	 * <p>This implementation precomputes the broadcasted alpha/beta tensors to avoid
	 * creating large expression trees during compilation, which significantly improves
	 * compilation performance for long sequences.</p>
	 *
	 * @param shape Input shape, typically (batch, channels, length)
	 * @param alpha Per-channel frequency parameter, shape (channels,)
	 * @param beta Per-channel scaling parameter, shape (channels,)
	 * @param requirements Optional compute requirements
	 * @return CellularLayer implementing learnable Snake activation
	 */
	default CellularLayer snake(TraversalPolicy shape, PackedCollection alpha,
								PackedCollection beta, ComputeRequirement... requirements) {
		int channels = shape.length(1);
		int seqLen = shape.length(2);
		int batch = shape.length(0);

		// Precompute broadcasted alpha and beta tensors to avoid creating large
		// expression trees during compilation. This is much faster than using
		// repeat() chains in the expression graph for long sequences.
		PackedCollection alphaExpanded = new PackedCollection(shape(batch, channels, seqLen));
		PackedCollection betaExpanded = new PackedCollection(shape(batch, channels, seqLen));

		// Use direct memory access for efficient broadcasting
		double[] alphaData = alphaExpanded.toArray(0, (int) alphaExpanded.getMemLength());
		double[] betaData = betaExpanded.toArray(0, (int) betaExpanded.getMemLength());

		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < channels; c++) {
				double alphaVal = alpha.valueAt(c);
				double betaVal = beta.valueAt(c);
				int baseIdx = (b * channels + c) * seqLen;
				java.util.Arrays.fill(alphaData, baseIdx, baseIdx + seqLen, alphaVal);
				java.util.Arrays.fill(betaData, baseIdx, baseIdx + seqLen, betaVal);
			}
		}

		// Copy back to PackedCollections
		alphaExpanded.setMem(0, alphaData, 0, alphaData.length);
		betaExpanded.setMem(0, betaData, 0, betaData.length);

		return layer("snakeLearnable", shape, shape, input -> {
			CollectionProducer x = c(input);

			// Use precomputed broadcasted tensors - no repeat() needed in expression graph
			CollectionProducer alphaBC = cp(alphaExpanded);
			CollectionProducer betaBC = cp(betaExpanded);

			// f(x) = x + (1/beta) * sin^2(alpha * x)
			CollectionProducer sinPart = sin(x.multiply(alphaBC));
			CollectionProducer sinSquared = pow(sinPart, c(2.0));
			return x.add(sinSquared.divide(betaBC)).traverseEach();
		}, requirements);
	}
}
