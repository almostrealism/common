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
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Factor;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.LoopedWeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Factory interface for convolution and pooling layers.
 *
 * <p>This collaborator interface groups the methods that build convolutional kernels
 * — 1-D convolution, 1-D transposed convolution, 2-D convolution, 2-D max pooling,
 * and weight-normalised variants of the 1-D forms. They are lifted out of
 * {@link LayerFeatures} so that the umbrella interface stays focused on
 * fundamental layer-construction primitives.</p>
 *
 * <p>{@link LayerFeatures} extends this interface, so any class that implements
 * {@code LayerFeatures} continues to see every convolution method without
 * modification. Default methods here call {@link #layer(String, TraversalPolicy,
 * TraversalPolicy, Factor, List, Supplier, ComputeRequirement...) layer(...)},
 * {@link #pad(TraversalPolicy, TraversalPolicy, int...) pad(...)} and
 * {@link #randnInit(PackedCollection, double) randnInit(...)} — the abstract
 * declarations below — which {@link LayerFeatures} satisfies via its existing
 * default implementations.</p>
 *
 * @see LayerFeatures
 * @see NormalizationLayerFeatures
 * @author Michael Murray
 */
public interface ConvolutionLayerFeatures extends MatrixFeatures, ActivationFeatures, ConsoleFeatures {

	/**
	 * Creates a cellular layer with learnable weights and an explicit setup operation.
	 *
	 * <p>This signature is declared abstractly so that the convolution default methods
	 * below can wire weights and parameter-initialisation operations into the layer.
	 * The default implementation lives on {@link LayerFeatures}.</p>
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the operator
	 * @param operator     the differentiable forward operator
	 * @param weights      the learnable parameter collections
	 * @param setup        the setup operation to run before the first forward pass
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 */
	CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
						Factor<PackedCollection> operator,
						List<PackedCollection> weights,
						Supplier<Runnable> setup,
						ComputeRequirement... requirements);

	/**
	 * Creates a padding block that embeds the input at a given position within a larger shape.
	 * Declared abstractly so {@link #convolution2d(TraversalPolicy, int, int, int, boolean,
	 * ComputeRequirement...) convolution2d} can wrap its kernel in a pad block when padding is
	 * non-zero. The default implementation lives on {@link LayerFeatures}.
	 *
	 * @param inputShape  the shape of the data to embed
	 * @param paddedShape the target padded shape
	 * @param pos         the offset position within the padded output
	 * @return a block whose forward cell pads to {@code paddedShape} and backward subsets back
	 */
	Block pad(TraversalPolicy inputShape, TraversalPolicy paddedShape,
			  int... pos);

	/**
	 * Returns a setup operation that initializes the given weight collection with random
	 * normal values scaled by the given factor. Declared abstractly so 2-D convolutions can
	 * initialise filter weights at layer construction time. The default implementation lives
	 * on {@link LayerFeatures}.
	 *
	 * @param weights the weight collection to initialize
	 * @param scale   the scalar multiplier applied to each sampled normal value
	 * @return a {@link Supplier} of the initialization {@link Runnable}
	 */
	Supplier<Runnable> randnInit(PackedCollection weights,
								 double scale);

	/**
	 * Creates a 1D convolution block with kernel size 1 (pointwise convolution).
	 * This is a convenience method that delegates to the full convolution1d with stride=1.
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels (filters)
	 * @param seqLength Sequence length
	 * @param kernelSize Must be 1 for this overload
	 * @param padding Must be 0 for this overload
	 * @param weights Weight tensor with shape [outputChannels, inputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @return Block performing the 1D convolution
	 */
	default Block convolution1d(int batchSize, int inputChannels, int outputChannels,
								int seqLength, int kernelSize, int padding,
								PackedCollection weights, PackedCollection bias) {
		return convolution1d(batchSize, inputChannels, outputChannels, seqLength,
							kernelSize, 1, padding, weights, bias);
	}

	/**
	 * Creates a 1D convolution block with arbitrary kernel size, stride, and padding.
	 *
	 * <p>Implements the standard 1D convolution operation commonly used in audio processing
	 * and sequence modeling. The output length is computed as:
	 * out_length = (seq_length + 2*padding - kernel_size) / stride + 1</p>
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels (filters)
	 * @param seqLength Input sequence length
	 * @param kernelSize Size of the convolution kernel
	 * @param stride Stride of the convolution (for downsampling, use stride &gt; 1)
	 * @param padding Zero-padding added to both sides of the input
	 * @param weights Weight tensor with shape [outputChannels, inputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @param requirements Optional compute requirements
	 * @return Block performing the 1D convolution
	 */
	default Block convolution1d(int batchSize, int inputChannels, int outputChannels,
								int seqLength, int kernelSize, int stride, int padding,
								PackedCollection weights, PackedCollection bias,
								ComputeRequirement... requirements) {
		// For kernel size 1 and stride 1, use optimized pointwise implementation
		if (kernelSize == 1 && stride == 1 && padding == 0) {
			weights = weights.reshape(weights.getShape().trim());
			return new SequentialBlock(shape(batchSize, inputChannels, seqLength))
					.enumerate(1, 2, 1)
					.reshape(batchSize * seqLength, inputChannels)
					.andThenDense(weights, bias)
					.reshape(batchSize, seqLength, outputChannels)
					.enumerate(1, 2, 1)
					.reshape(batchSize, outputChannels, seqLength);
		}

		// Calculate output length
		int paddedLength = seqLength + 2 * padding;
		int outLength = (paddedLength - kernelSize) / stride + 1;

		TraversalPolicy inputShape = shape(batchSize, inputChannels, seqLength);
		TraversalPolicy outputShape = shape(batchSize, outputChannels, outLength);
		TraversalPolicy filterShape = shape(outputChannels, inputChannels, kernelSize);

		// Ensure weights have correct shape
		if (weights.getShape().getTotalSize() != filterShape.getTotalSize()) {
			throw new IllegalArgumentException("Weight shape mismatch: expected " +
					filterShape + " but got " + weights.getShape());
		}
		PackedCollection filters = weights.reshape(filterShape);

		Factor<PackedCollection> operator = input -> {
			CollectionProducer in = c(input);

			// Apply padding if needed
			if (padding > 0) {
				in = pad(shape(batchSize, inputChannels, paddedLength), in, 0, 0, padding);
			}

			// Reshape for convolution: (batch, 1, channels, paddedLength) - 4D
			CollectionProducer conv = in.reshape(-1, 1, inputChannels, paddedLength);
			CollectionProducer filter = cp(filters.reshape(1, outputChannels, inputChannels, kernelSize));

			// Define positions for weighted sum
			// Use batch from reshaped producer to match conv2d's pattern
			int bs = conv.getShape().length(0);
			TraversalPolicy resultShape = shape(bs, outputChannels, 1, outLength);
			TraversalPolicy inputPositions = resultShape
					.withRate(1, 1, outputChannels)
					.withRate(2, inputChannels, 1)
					.withRate(3, stride, 1);  // Add stride rate for position variation
			TraversalPolicy filterPositions = resultShape
					.withRate(0, 1, bs)
					.withRate(2, inputChannels, 1)
					.withRate(3, kernelSize, outLength);
			TraversalPolicy groupShape = shape(1, 1, inputChannels, kernelSize);

			CollectionProducer result = weightedSum("conv1dFilter",
					inputPositions, filterPositions,
					groupShape, conv, filter);

			// Add bias if provided
			if (bias != null) {
				int t = outLength;
				result = result.reshape(bs, outputChannels, t)
						.add(cp(bias).repeat(bs).traverse(2).repeat(t));
			}

			return result.reshape(-1, outputChannels, outLength).traverseEach();
		};

		return layer("conv1d", inputShape.traverse(1), outputShape.traverse(1),
					operator, bias != null ? List.of(filters, bias) : List.of(filters),
					new OperationList(), requirements);
	}

	/**
	 * Creates a 1D transposed convolution (deconvolution) block for upsampling.
	 *
	 * <p>Transposed convolution is the gradient operation of a normal convolution,
	 * commonly used in decoder networks for upsampling. The output length is computed as:
	 * {@code out_length = (seq_length - 1) * stride - 2 * padding + kernel_size}</p>
	 *
	 * <p>The implementation works by upsampling the input (inserting zeros between elements),
	 * padding for convolution boundaries, then performing standard convolution with the
	 * transposed weight layout. Specifically:</p>
	 * <ol>
	 *   <li>Upsample input by placing each element in a cell of size {@code stride},
	 *       with zeros filling the remaining positions</li>
	 *   <li>Trim to the correct expanded length: {@code (seqLength - 1) * stride + 1}</li>
	 *   <li>Pad with {@code kernelSize - 1 - padding} zeros on each side</li>
	 *   <li>Perform standard convolution using {@code weightedSum}</li>
	 * </ol>
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Size of the convolution kernel
	 * @param stride Stride (upsampling factor)
	 * @param padding Padding to remove from output
	 * @param weights Weight tensor with shape [inputChannels, outputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @param requirements Optional compute requirements
	 * @return Block performing the transposed 1D convolution
	 */
	default Block convTranspose1d(int batchSize, int inputChannels, int outputChannels,
								  int seqLength, int kernelSize, int stride, int padding,
								  PackedCollection weights, PackedCollection bias,
								  ComputeRequirement... requirements) {
		return convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
				kernelSize, stride, padding, 0, weights, bias, requirements);
	}

	/**
	 * Creates a 1D transposed convolution (deconvolution) layer with output padding.
	 *
	 * @param batchSize Batch size
	 * @param inputChannels Number of input channels
	 * @param outputChannels Number of output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Size of the convolution kernel
	 * @param stride Stride (upsampling factor)
	 * @param padding Padding to remove from output
	 * @param outputPadding Additional size added to output
	 * @param weights Weight tensor with shape [inputChannels, outputChannels, kernelSize]
	 * @param bias Optional bias tensor with shape [outputChannels], may be null
	 * @param requirements Optional compute requirements
	 * @return Block performing the transposed 1D convolution
	 */
	default Block convTranspose1d(int batchSize, int inputChannels, int outputChannels,
								  int seqLength, int kernelSize, int stride, int padding,
								  int outputPadding, PackedCollection weights, PackedCollection bias,
								  ComputeRequirement... requirements) {
		int outLength = (seqLength - 1) * stride - 2 * padding + kernelSize + outputPadding;

		TraversalPolicy inputShape = shape(batchSize, inputChannels, seqLength);
		TraversalPolicy outputShape = shape(batchSize, outputChannels, outLength);
		TraversalPolicy filterShape = shape(inputChannels, outputChannels, kernelSize);

		if (weights.getShape().getTotalSize() != filterShape.getTotalSize()) {
			throw new IllegalArgumentException("Weight shape mismatch: expected " +
					filterShape + " but got " + weights.getShape());
		}
		PackedCollection filters = weights.reshape(filterShape);

		Factor<PackedCollection> operator = input -> {
			CollectionProducer in = c(input);

			int expandedLength = (seqLength - 1) * stride + 1;
			int leftPadding = kernelSize - 1 - padding;
			int paddedExpandedLength = outLength + kernelSize - 1;

			TraversalPolicy upsampleCellShape = shape(batchSize * inputChannels, seqLength, stride);
			CollectionProducer upsampled = pad(upsampleCellShape,
					in.reshape(batchSize * inputChannels, seqLength, 1),
					0, 0, 0);

			CollectionProducer upsampledFlat = upsampled.reshape(batchSize * inputChannels, seqLength * stride);

			if (seqLength * stride > expandedLength) {
				upsampledFlat = upsampledFlat.subset(shape(batchSize * inputChannels, expandedLength), 0, 0);
			}

			if (paddedExpandedLength > expandedLength) {
				upsampledFlat = pad(shape(batchSize * inputChannels, paddedExpandedLength),
						upsampledFlat, 0, leftPadding);
			}

			upsampledFlat.reshape(batchSize, inputChannels, 1, paddedExpandedLength);
			cp(filters).reshape(1, inputChannels, outputChannels, kernelSize);

			CollectionProducer result;

			{
				TraversalPolicy loopedOutputShape = shape(batchSize, outputChannels, outLength).traverseEach();
				TraversalPolicy loopedInputShape = shape(batchSize * inputChannels, paddedExpandedLength);

				final int ocLen = outLength;
				final int icChannels = inputChannels;
				final int ocChannels = outputChannels;
				final int kSize = kernelSize;
				final int paddedLen = paddedExpandedLength;

				LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIdx, outerIdx, innerIdx) -> {
					Expression<?> b = outputIdx.divide(ocChannels * ocLen);
					Expression<?> o = outputIdx.imod(ocLen);
					return b.multiply(icChannels).add(outerIdx).multiply(paddedLen).add(o).add(innerIdx);
				};

				LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIdx, outerIdx, innerIdx) -> {
					Expression<?> oc = outputIdx.divide(ocLen).imod(ocChannels);
					Expression<?> flippedK = innerIdx.multiply(-1).add(kSize - 1);
					return outerIdx.multiply(ocChannels * kSize).add(oc.multiply(kSize)).add(flippedK);
				};

				LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
						"convTranspose1dLooped",
						loopedOutputShape,
						inputChannels,
						kernelSize,
						loopedInputShape,
						filterShape,
						inputIndexer,
						weightIndexer,
						upsampledFlat,
						cp(filters));

				result = c(computation).reshape(batchSize, outputChannels, outLength);
			}

			if (bias != null) {
				result = result.reshape(batchSize, outputChannels, outLength)
						.add(cp(bias).repeat(batchSize).traverse(2).repeat(outLength));
			}

			return result.traverseEach();
		};

		return layer("convTranspose1d", inputShape.traverseEach(), outputShape.traverseEach(),
					operator, bias != null ? List.of(filters, bias) : List.of(filters),
					new OperationList(), requirements);
	}

	/**
	 * Creates a 2-D convolution block factory with an explicit channel count and a learnable bias.
	 *
	 * @param inputChannels the number of input channels; when not 1, the input shape is validated
	 * @param filterCount   the number of convolutional filters
	 * @param size          the spatial size of each filter
	 * @param padding       the amount of zero-padding added to each spatial dimension
	 * @param requirements  optional compute requirements
	 * @return a function that creates a 2-D convolution block for any input shape
	 */
	default Function<TraversalPolicy, Block> convolution2d(int inputChannels, int filterCount, int size, int padding,
																   ComputeRequirement... requirements) {
		return convolution2d(inputChannels, filterCount, size, padding, true, requirements);
	}

	/**
	 * Creates a 2-D convolution block factory with an explicit channel count and optional bias.
	 *
	 * @param inputChannels the number of input channels; when not 1, the input shape is validated
	 * @param filterCount   the number of convolutional filters
	 * @param size          the spatial size of each filter
	 * @param padding       the amount of zero-padding added to each spatial dimension
	 * @param bias          when {@code true}, a learnable bias is added to each filter output
	 * @param requirements  optional compute requirements
	 * @return a function that creates a 2-D convolution block for any input shape
	 */
	default Function<TraversalPolicy, Block> convolution2d(int inputChannels, int filterCount, int size, int padding,
														   boolean bias, ComputeRequirement... requirements) {
		if (inputChannels != 1) {
			return shape -> {
				shape = padDimensions(shape, 2, 4);
				int c = shape.getDimensions() > 2 ? shape.length(1) : 1;
				if (c != inputChannels) {
					throw new IllegalArgumentException();
				}

				return convolution2d(shape, filterCount, size, padding, bias, requirements);
			};
		}

		return shape -> convolution2d(shape, filterCount, size, padding, bias, requirements);
	}

	/**
	 * Creates a 2-D convolution block factory with no padding and a learnable bias.
	 *
	 * @param filterCount  the number of convolutional filters
	 * @param size         the spatial size of each filter (height and width)
	 * @param requirements optional compute requirements
	 * @return a function that creates a 2-D convolution block for any input shape
	 */
	default Function<TraversalPolicy, Block> convolution2d(int filterCount, int size, ComputeRequirement... requirements) {
		return convolution2d(filterCount, size, 0, requirements);
	}

	/**
	 * Creates a 2-D convolution block factory with the given padding and a learnable bias.
	 *
	 * @param filterCount  the number of convolutional filters
	 * @param size         the spatial size of each filter
	 * @param padding      the amount of zero-padding added to each spatial dimension
	 * @param requirements optional compute requirements
	 * @return a function that creates a 2-D convolution block for any input shape
	 */
	default Function<TraversalPolicy, Block> convolution2d(int filterCount, int size, int padding, ComputeRequirement... requirements) {
		return shape -> convolution2d(shape, filterCount, size, padding, true, requirements);
	}

	/**
	 * Creates a 2-D convolution block with no padding and a learnable bias.
	 *
	 * @param inputShape   the input shape (must be 4-D: batch × channels × height × width)
	 * @param filterCount  the number of convolutional filters
	 * @param size         the spatial size of each filter
	 * @param requirements optional compute requirements
	 * @return the constructed convolution block
	 */
	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, true, requirements);
	}

	/**
	 * Creates a 2-D convolution block with no padding and optional bias.
	 *
	 * @param inputShape   the input shape (must be 4-D)
	 * @param filterCount  the number of convolutional filters
	 * @param size         the spatial size of each filter
	 * @param bias         when {@code true}, a learnable bias vector is added to each filter output
	 * @param requirements optional compute requirements
	 * @return the constructed convolution block
	 */
	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, boolean bias, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, bias, requirements);
	}

	/**
	 * Core 2-D convolution block factory.
	 *
	 * <p>Initialises filter and bias weights, builds the forward operator using the weighted-sum
	 * convolution pattern, and wires backpropagation through {@link DefaultGradientPropagation}.</p>
	 *
	 * @param inputShape   the input shape (must be 4-D or auto-padded to 4-D)
	 * @param filterCount  the number of convolutional filters
	 * @param size         the spatial size of each filter
	 * @param padding      the amount of zero-padding added to each spatial dimension
	 * @param bias         when {@code true}, a learnable bias is added to each filter output
	 * @param requirements optional compute requirements
	 * @return the constructed convolution block
	 */
	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
								int size, int padding,
								boolean bias, ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2, 4);

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int h = inputShape.length(2);
		int w = inputShape.length(3);

		int batch = inputShape.length(0);
		int channels = inputShape.length(1);
		int height = h + 2 * padding;
		int width = w + 2 * padding;

		int diff = size - 1;
		int outHeight = height - diff;
		int outWidth = width - diff;
		TraversalPolicy outputShape = shape(batch, filterCount, outHeight, outWidth);

		TraversalPolicy filterShape = shape(filterCount, channels, size, size);
		PackedCollection filters = new PackedCollection(filterShape);

		TraversalPolicy biasShape = shape(filterCount);
		PackedCollection biases = bias ? new PackedCollection(biasShape) : null;

		Factor<PackedCollection> operator = input -> {
			CollectionProducer in = c(input);
			CollectionProducer conv =
					in.reshape(-1, 1, channels, height, width);
			CollectionProducer filter =
					cp(filters.reshape(1, filterCount, channels, size, size));

			int bs = conv.getShape().length(0);

			TraversalPolicy resultShape = shape(batch, filterCount, 1, outHeight, outWidth);
			TraversalPolicy inputPositions = resultShape
					.withRate(1, 1, filterCount)
					.withRate(2, channels, 1);
			TraversalPolicy filterPositions = resultShape
					.withRate(0, 1, batch)
					.withRate(2, channels, 1)
					.withRate(3, size, outHeight)
					.withRate(4, size, outWidth);
			TraversalPolicy groupShape =
					shape(1, 1, channels, size, size);
			CollectionProducer result =
					weightedSum("convolutionFilter",
							inputPositions, filterPositions,
							groupShape, conv, filter);

			if (biases != null) {
				int t = outHeight * outWidth;
				result = result.reshape(bs, filterCount, t)
						.add(cp(biases).repeat(bs).traverse(2).repeat(t));
			}

			return result
					.reshape(-1, filterCount, outHeight, outWidth)
					.traverseEach();
		};

		OperationList setup = new OperationList();
		setup.add(randnInit(filters, 1.0 / (channels * size * size)));
		if (biases != null) {
			setup.add(randnInit(biases, 1.0 / (channels * size * size)));
		}

		TraversalPolicy convInputShape = shape(batch, channels, height, width);
		CellularLayer layer = layer("convolution2d",
								convInputShape.traverse(1), outputShape.traverse(1),
								operator,
								biases == null ? List.of(filters) : List.of(filters, biases),
								setup, requirements);

		if (padding > 0) {
			SequentialBlock block = new SequentialBlock(inputShape);
			block.add(pad(inputShape, convInputShape, 0, 0, padding, padding));
			block.add(layer);
			return block;
		} else {
			return layer;
		}
	}

	/**
	 * Creates a 2D max-pooling layer factory that applies the given pooling window size
	 * to any input shape supplied at layer construction time.
	 *
	 * @param size the height and width of the square pooling window
	 * @return a function that creates a {@link CellularLayer} for any 4-D input shape
	 */
	default Function<TraversalPolicy, CellularLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	/**
	 * Creates a 2D max-pooling layer for a specific 4-D input shape.
	 *
	 * <p>The input is expected to have dimensions {@code (N, C, H, W)}.
	 * The output shape is {@code (N, C, H/size, W/size)}.</p>
	 *
	 * @param inputShape   the 4-D input shape {@code (batch, channels, height, width)}
	 * @param size         the height and width of the square pooling window
	 * @param requirements optional compute requirements
	 * @return the constructed max-pooling {@link CellularLayer}
	 */
	default CellularLayer pool2d(TraversalPolicy inputShape, int size, ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2, 4);

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int n = inputShape.length(0);
		int c = inputShape.length(1);
		int h = inputShape.length(2);
		int w = inputShape.length(3);

		TraversalPolicy outputShape =
					shape(n, c, h / size, w / size).alignCount(inputShape);

		Factor<PackedCollection> operator = input ->
				c(input)
						.reshape(-1, c, h, w)
						.traverse(2)
						.enumerate(3, size)
						.enumerate(3, size)
						.max(4)
						.reshape(outputShape.traverseEach());
		return layer("pool2d", inputShape, outputShape, operator, requirements);
	}

	/**
	 * Creates a Conv1d layer with weight normalization.
	 *
	 * <p>Weight normalization decomposes the weight matrix W into a direction component v
	 * and a magnitude component g: W = g * v / ||v||</p>
	 *
	 * <p>This is used by Stable Audio Open / DAC autoencoders.</p>
	 *
	 * @param batchSize Batch size
	 * @param inChannels Input channels
	 * @param outChannels Output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Convolution kernel size
	 * @param stride Convolution stride
	 * @param padding Padding amount
	 * @param weightG Magnitude parameter, shape (outChannels, 1, 1)
	 * @param weightV Direction parameter, shape (outChannels, inChannels, kernelSize)
	 * @param bias Bias, shape (outChannels,) or null
	 * @param requirements Optional compute requirements
	 * @return Block implementing weight-normalized Conv1d
	 */
	default Block wnConv1d(int batchSize, int inChannels, int outChannels, int seqLength,
						   int kernelSize, int stride, int padding,
						   PackedCollection weightG, PackedCollection weightV,
						   PackedCollection bias, ComputeRequirement... requirements) {
		// Compute normalized weights: W = g * v / ||v||
		// ||v|| is computed per output channel (norm over inChannels * kernelSize)
		PackedCollection normalizedWeights = computeWeightNormWeights(weightG, weightV,
				outChannels, inChannels, kernelSize);

		// Use standard conv1d with the normalized weights
		return convolution1d(batchSize, inChannels, outChannels, seqLength,
				kernelSize, stride, padding, normalizedWeights, bias, requirements);
	}

	/**
	 * Computes normalized weights from weight normalization parameters.
	 * W = g * v / ||v|| where ||v|| is computed per output channel.
	 */
	default PackedCollection computeWeightNormWeights(PackedCollection weightG,
													  PackedCollection weightV,
													  int outChannels, int inChannels, int kernelSize) {
		// weightG shape: (outChannels, 1, 1)
		// weightV shape: (outChannels, inChannels, kernelSize)
		// Output shape: (outChannels, inChannels, kernelSize)

		int vSize = inChannels * kernelSize;
		PackedCollection result = new PackedCollection(outChannels, inChannels, kernelSize);

		for (int oc = 0; oc < outChannels; oc++) {
			// Compute L2 norm of v for this output channel
			double normSq = 0.0;
			for (int ic = 0; ic < inChannels; ic++) {
				for (int k = 0; k < kernelSize; k++) {
					double v = weightV.toDouble(oc * vSize + ic * kernelSize + k);
					normSq += v * v;
				}
			}
			double norm = Math.sqrt(normSq);

			// Get magnitude g for this output channel
			double g = weightG.toDouble(oc);

			// Compute W = g * v / ||v||
			double scale = g / (norm + 1e-12);
			for (int ic = 0; ic < inChannels; ic++) {
				for (int k = 0; k < kernelSize; k++) {
					int idx = oc * vSize + ic * kernelSize + k;
					double v = weightV.toDouble(idx);
					result.setMem(idx, v * scale);
				}
			}
		}

		return result;
	}

	/**
	 * Creates a ConvTranspose1d layer with weight normalization.
	 *
	 * @param batchSize Batch size
	 * @param inChannels Input channels
	 * @param outChannels Output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Convolution kernel size
	 * @param stride Convolution stride
	 * @param padding Padding amount
	 * @param weightG Magnitude parameter, shape (inChannels, 1, 1) for transposed
	 * @param weightV Direction parameter, shape (inChannels, outChannels, kernelSize)
	 * @param bias Bias, shape (outChannels,) or null
	 * @param requirements Optional compute requirements
	 * @return Block implementing weight-normalized ConvTranspose1d
	 */
	default Block wnConvTranspose1d(int batchSize, int inChannels, int outChannels, int seqLength,
									int kernelSize, int stride, int padding,
									PackedCollection weightG, PackedCollection weightV,
									PackedCollection bias, ComputeRequirement... requirements) {
		return wnConvTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernelSize, stride, padding, 0, weightG, weightV, bias, requirements);
	}

	/**
	 * Creates a ConvTranspose1d layer with weight normalization and output padding.
	 *
	 * @param batchSize Batch size
	 * @param inChannels Input channels
	 * @param outChannels Output channels
	 * @param seqLength Input sequence length
	 * @param kernelSize Convolution kernel size
	 * @param stride Convolution stride
	 * @param padding Padding amount
	 * @param outputPadding Additional size added to output
	 * @param weightG Magnitude parameter, shape (inChannels, 1, 1) for transposed
	 * @param weightV Direction parameter, shape (inChannels, outChannels, kernelSize)
	 * @param bias Bias, shape (outChannels,) or null
	 * @param requirements Optional compute requirements
	 * @return Block implementing weight-normalized ConvTranspose1d
	 */
	default Block wnConvTranspose1d(int batchSize, int inChannels, int outChannels, int seqLength,
									int kernelSize, int stride, int padding, int outputPadding,
									PackedCollection weightG, PackedCollection weightV,
									PackedCollection bias, ComputeRequirement... requirements) {
		// For transposed conv, weight shape is (inChannels, outChannels, kernelSize)
		// Normalize over outChannels * kernelSize per input channel
		PackedCollection normalizedWeights = computeWeightNormWeightsTransposed(weightG, weightV,
				inChannels, outChannels, kernelSize);

		return convTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernelSize, stride, padding, outputPadding, normalizedWeights, bias, requirements);
	}

	/**
	 * Computes normalized weights for transposed convolution.
	 * W = g * v / ||v|| where ||v|| is computed per input channel (first dimension).
	 */
	default PackedCollection computeWeightNormWeightsTransposed(PackedCollection weightG,
																PackedCollection weightV,
																int inChannels, int outChannels, int kernelSize) {
		int vSize = outChannels * kernelSize;
		PackedCollection result = new PackedCollection(inChannels, outChannels, kernelSize);

		for (int ic = 0; ic < inChannels; ic++) {
			// Compute L2 norm of v for this input channel
			double normSq = 0.0;
			for (int oc = 0; oc < outChannels; oc++) {
				for (int k = 0; k < kernelSize; k++) {
					double v = weightV.toDouble(ic * vSize + oc * kernelSize + k);
					normSq += v * v;
				}
			}
			double norm = Math.sqrt(normSq);

			// Get magnitude g for this input channel
			double g = weightG.toDouble(ic);

			// Compute W = g * v / ||v||
			double scale = g / (norm + 1e-12);
			for (int oc = 0; oc < outChannels; oc++) {
				for (int k = 0; k < kernelSize; k++) {
					int idx = ic * vSize + oc * kernelSize + k;
					double v = weightV.toDouble(idx);
					result.setMem(idx, v * scale);
				}
			}
		}

		return result;
	}
}
