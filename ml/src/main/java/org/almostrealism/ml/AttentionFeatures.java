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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;
import java.util.function.Function;

public interface AttentionFeatures extends RotationFeatures {

	default Function<TraversalPolicy, CellularLayer> attentionKeys(Producer<PackedCollection<?>> keys,
																   ComputeRequirement... requirements) {
		return inputShape -> attentionKeys(inputShape, keys, requirements);
	}

	default CellularLayer attentionKeys(TraversalPolicy inputShape,
										Producer<PackedCollection<?>> keys,
										ComputeRequirement... requirements) {
		TraversalPolicy keyShape = shape(keys); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 2 || keyShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = inputShape.length(1);
		int dim = heads * headSize;

		int seqLength = keyShape.length(0);
		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		if (keyShape.length(1) != heads || keyShape.length(2) != headSize)
			throw new IllegalArgumentException();

		// TODO  divide(c(Math.sqrt(headSize))) is better to include
		// TODO  outside this method rather than within the layer
		return layer("attentionKeys", inputShape, outputShape, input ->
				traverse(1, keys).map(v -> v.multiply(input))
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads))
						.enumerate(1, 1)
						.reshape(outputShape), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> attentionValues(Producer<PackedCollection<?>> values,
																     ComputeRequirement... requirements) {
		return inputShape -> attentionValues(inputShape, values, requirements);
	}

	default CellularLayer attentionValues(TraversalPolicy inputShape,
										  Producer<PackedCollection<?>> values,
										  ComputeRequirement... requirements) {
		TraversalPolicy valueShape = shape(values); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 2 || valueShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = valueShape.length(2);
		int dim = heads * headSize;

		int seqLength = inputShape.length(1);
		TraversalPolicy outputShape = shape(dim);

		if (valueShape.length(1) != heads || valueShape.length(0) != seqLength)
			throw new IllegalArgumentException();

		return layer("attentionValues", inputShape, outputShape, input -> {
			Producer<PackedCollection<?>> v = reshape(shape(seqLength, dim), values);
			v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

			CollectionProducer<PackedCollection<?>> a = traverse(1, input).expand(headSize, x -> x.repeat(headSize));
			CollectionProducer<PackedCollection<?>> o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();
			return o.reshape(shape(dim).traverseEach());
		}, requirements);
	}

	default Block attention(int heads,
							PackedCollection<?> rmsAttWeight,
							PackedCollection<?> wk, PackedCollection<?> wv,
							PackedCollection<?> wq, PackedCollection<?> wo,
							PackedCollection<?> freqCis,
							Producer<PackedCollection<?>> position,
							ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);
		int headSize = freqCis.getShape().size(1);
		int seqLen = freqCis.getShape().length(0);

		SequentialBlock attention = new SequentialBlock(shape(dim));

		PackedCollection<?> keyCache = new PackedCollection<>(seqLen, heads, headSize);
		PackedCollection<?> valueCache = new PackedCollection<>(seqLen, heads, headSize);

		attention.add(rmsnorm(rmsAttWeight, requirements));

		SequentialBlock keys = attention.branch();
		SequentialBlock values = attention.branch();

		TraversalPolicy headShapeComplex = shape(heads, headSize / 2, 2);

		/* KEYS **/
		keys.add(dense(wk));
		keys.add(reshape(shape(dim), headShapeComplex));
		keys.add(ropeRotation(headShapeComplex, freqCis, position));
		keys.andThen(into(keyCache.reshape(shape(seqLen, dim)), position));
		/* ---- **/

		/* VALUES **/
		values.add(dense(wv));
		values.andThen(into(valueCache.reshape(shape(seqLen, dim)), position));
		/* ---- **/

		/* QUERY **/
		TraversalPolicy headShape = shape(heads, headSize);
		TraversalPolicy attentionShape = shape(heads, seqLen);

		attention.add(dense(wq));
		attention.add(reshape(shape(dim), headShapeComplex));
		attention.add(ropeRotation(headShapeComplex, freqCis, position));
		attention.add(reshape(headShapeComplex, headShape));
		attention.add(attentionKeys(headShape, p(keyCache)));
		attention.add(softmax(attentionShape, true));
		attention.add(attentionValues(attentionShape, p(valueCache)));
		attention.add(dense(wo));
		/* ---- **/

		return attention;
	}

	default Function<TraversalPolicy, CellularLayer> sequenceAttentionKeys(Producer<PackedCollection<?>> keys,
																   ComputeRequirement... requirements) {
		return inputShape -> sequenceAttentionKeys(inputShape, keys, requirements);
	}

	default CellularLayer sequenceAttentionKeys(TraversalPolicy inputShape,
										Producer<PackedCollection<?>> keys,
										ComputeRequirement... requirements) {
		TraversalPolicy keyShape = shape(keys); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 3 || keyShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(1);
		int headSize = inputShape.length(2);

		int seqLength = keyShape.length(0);
		if (inputShape.length(0) != seqLength) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		if (keyShape.length(1) != heads || keyShape.length(2) != headSize)
			throw new IllegalArgumentException();

		// TODO  divide(c(Math.sqrt(headSize))) is better to include
		// TODO  outside this method rather than within the layer
		return layer("attentionKeys", inputShape, outputShape, input ->
				multiply(traverseEach(keys), input)
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads))
						.enumerate(1, 1)
						.reshape(outputShape), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> sequenceAttentionValues(Producer<PackedCollection<?>> values,
																	 ComputeRequirement... requirements) {
		return inputShape -> sequenceAttentionValues(inputShape, values, requirements);
	}

	default CellularLayer sequenceAttentionValues(TraversalPolicy inputShape,
												  Producer<PackedCollection<?>> values,
												  ComputeRequirement... requirements) {
		TraversalPolicy valueShape = shape(values);

		if (inputShape.getDimensions() != 2 || valueShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = valueShape.length(2);
		int dim = heads * headSize;

		int batchSeqLen = inputShape.length(1);
		TraversalPolicy outputShape = shape(batchSeqLen, dim);

		if (valueShape.length(1) != heads || valueShape.length(0) != batchSeqLen)
			throw new IllegalArgumentException();

		return layer("attentionValues", inputShape, outputShape, input -> {
			// We need to transpose the attention weights
			// from (heads, batchSeqLen) to (batchSeqLen, heads)
			CollectionProducer<PackedCollection<?>> attnWeights = c(input)
					.reshape(heads, batchSeqLen)
					.enumerate(1, 1)
					.reshape(batchSeqLen, heads);

			// Now for each sequence position, apply attention weights to values
			// values has shape (batchSeqLen, heads, headSize)
			CollectionProducer<PackedCollection<?>> v = c(values).traverse(2);

			// Expand attention weights to match value dimensions for broadcasting
			// (batchSeqLen, heads) -> (batchSeqLen, heads, headSize)
			attnWeights = attnWeights.traverse(2).repeat(headSize);

			// Apply attention weights to values
			return multiply(v, attnWeights).reshape(batchSeqLen, dim);
		}, requirements);
	}

	/**
	 * Implements self-attention that processes entire sequences at once,
	 * rather than token-by-token processing.
	 */
	default Block sequenceAttention(int batchSize, int seqLen, int heads,
									PackedCollection<?> rmsWeight,
									PackedCollection<?> wk, PackedCollection<?> wv,
									PackedCollection<?> wq, PackedCollection<?> wo,
									PackedCollection<?> freqCis) {
		int dim = rmsWeight.getShape().length(0);
		int dimHead = dim / heads;

		SequentialBlock sequenceAttention = new SequentialBlock(shape(batchSize, seqLen, dim));

		// Create caches for keys and values
		PackedCollection<?> keysCache = new PackedCollection<>(shape(batchSize, seqLen, heads, dimHead));
		PackedCollection<?> valuesCache = new PackedCollection<>(shape(batchSize, seqLen, heads, dimHead));

		// Normalize input
		sequenceAttention.add(rmsnorm(sequenceAttention.getOutputShape(), rmsWeight));

		// Project to keys and store in cache
		SequentialBlock keyBranch = sequenceAttention.branch();
		keyBranch.add(dense(wk));
		keyBranch.reshape(batchSize, seqLen, heads, dimHead);

		// Apply rotary embeddings to keys
		if (freqCis != null) {
			keyBranch.add(sequenceRotaryEmbedding(shape(batchSize, seqLen, heads, dimHead), freqCis));
		}

		keyBranch.andThen(into(keysCache));

		/* VALUES **/
		SequentialBlock valueBranch = sequenceAttention.branch();
		valueBranch.add(dense(wv));
		valueBranch.reshape(batchSize, seqLen, heads, dimHead);
		valueBranch.andThen(into(valuesCache));
		/* ---- **/

		/* QUERY **/
		sequenceAttention.add(dense(wq));
		sequenceAttention.reshape(batchSize, seqLen, heads, dimHead);

		// Apply rotary embeddings to queries
		if (freqCis != null) {
			sequenceAttention.add(sequenceRotaryEmbedding(
					shape(batchSize, seqLen, heads, dimHead),
					freqCis));
		}

		sequenceAttention.reshape(batchSize * seqLen, heads, dimHead);
		sequenceAttention.add(sequenceAttentionKeys(cp(keysCache.reshape(batchSize * seqLen, heads, dimHead))));
		sequenceAttention.add(softmax(true));
		sequenceAttention.add(sequenceAttentionValues(cp(valuesCache.reshape(batchSize * seqLen, heads, dimHead))));
		sequenceAttention.add(dense(wo));

		return sequenceAttention;
	}

	default Function<TraversalPolicy, CellularLayer> crossAttentionKeys(Producer<PackedCollection<?>> keys,
																		ComputeRequirement... requirements) {
		return inputShape -> crossAttentionKeys(inputShape, keys, requirements);
	}

	default CellularLayer crossAttentionKeys(TraversalPolicy inputShape,
											 Producer<PackedCollection<?>> keys,
											 ComputeRequirement... requirements) {
		// inputShape is (batchSize, querySeqLen, heads, dimHead)
		TraversalPolicy keyShape = shape(keys); // (batchSize, contextSeqLen, heads, dimHead)

		if (inputShape.getDimensions() != 4 || keyShape.getDimensions() != 4)
			throw new IllegalArgumentException("Invalid dimensions for cross-attention keys");

		int batchSize = inputShape.length(0);
		if (keyShape.length(0) != batchSize)
			throw new IllegalArgumentException("Batch size mismatch in cross-attention keys");

		int heads = inputShape.length(2);
		int dimHead = inputShape.length(3);

		if (keyShape.length(2) != heads || keyShape.length(3) != dimHead)
			throw new IllegalArgumentException("Heads or dimHead mismatch in cross-attention keys");

		int querySeqLen = inputShape.length(1);
		int contextSeqLen = keyShape.length(1);

		// Reshape the tensors for weightedSum operation
		TraversalPolicy queryShape = shape(batchSize, querySeqLen, heads, dimHead);
		TraversalPolicy keysReshape = shape(batchSize, contextSeqLen, heads, dimHead);

		// Result shape for the weighted sum operation
		TraversalPolicy resultShape = shape(batchSize, heads, querySeqLen, contextSeqLen);

		// Define positions for the query and key tensors
		TraversalPolicy queryPositions = resultShape
				.withRate(2, 1, querySeqLen)
				.withRate(3, dimHead, contextSeqLen);

		TraversalPolicy keyPositions = resultShape
				.withRate(1, 1, heads)
				.withRate(2, dimHead, querySeqLen);

		// Group shape for the summation (summing over dimHead)
		TraversalPolicy groupShape = shape(1, 1, 1, dimHead);

		return layer("crossAttentionKeys", inputShape, resultShape, input -> {
			// Apply scaling factor 1/sqrt(dimHead)
			double scaleFactor = 1.0 / Math.sqrt(dimHead);

			// Use weightedSum for the dot product calculation
			return weightedSum("crossAttentionKeys",
					resultShape,
					queryPositions, keyPositions,
					groupShape, groupShape,
					reshape(queryShape, input),
					reshape(keysReshape, keys))
					.multiply(c(scaleFactor));
		}, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> crossAttentionValues(Producer<PackedCollection<?>> values,
																		  ComputeRequirement... requirements) {
		return inputShape -> crossAttentionValues(inputShape, values, requirements);
	}

	default CellularLayer crossAttentionValues(TraversalPolicy inputShape,
											   Producer<PackedCollection<?>> values,
											   ComputeRequirement... requirements) {
		// inputShape is (batchSize, heads, querySeqLen, contextSeqLen) - attention weights
		TraversalPolicy valueShape = shape(values); // (batchSize, contextSeqLen, heads, dimHead)

		if (inputShape.getDimensions() != 4 || valueShape.getDimensions() != 4)
			throw new IllegalArgumentException("Invalid dimensions for cross-attention values");

		int batchSize = inputShape.length(0);
		if (valueShape.length(0) != batchSize)
			throw new IllegalArgumentException("Batch size mismatch in cross-attention values");

		int heads = inputShape.length(1);
		int querySeqLen = inputShape.length(2);
		int contextSeqLen = inputShape.length(3);

		if (valueShape.length(1) != contextSeqLen || valueShape.length(2) != heads)
			throw new IllegalArgumentException("Context length or heads mismatch in cross-attention values");

		int dimHead = valueShape.length(3);
		int dim = heads * dimHead;

		// Output shape will be (batchSize, querySeqLen, heads, dimHead)
		TraversalPolicy outputShape = shape(batchSize, querySeqLen, heads, dimHead);

		// Define shapes for the weighted sum operation
		TraversalPolicy attnShape = shape(batchSize, heads, querySeqLen, contextSeqLen);
		TraversalPolicy valuesReshape = shape(batchSize, contextSeqLen, heads, dimHead);

		// Define positions for attention weights and values
		TraversalPolicy attnPositions = outputShape
				.withRate(1, 1, querySeqLen)
				.withRate(3, contextSeqLen, dimHead);

		TraversalPolicy valuePositions = outputShape
				.withRate(0, 1, batchSize)
				.withRate(2, 1, heads);

		// Group shape for the summation (summing over contextSeqLen)
		TraversalPolicy groupShape = shape(1, 1, 1, contextSeqLen);

		return layer("crossAttentionValues", inputShape, outputShape, attnWeights -> {
			// Use weightedSum to apply attention weights to values
			return weightedSum("crossAttentionValues",
					outputShape,
					attnPositions, valuePositions,
					groupShape, groupShape,
					reshape(attnShape, attnWeights),
					reshape(valuesReshape, values));
		}, requirements);
	}

	default Block crossAttention(int batchSize, int querySeqLen, int contextSeqLen,
								 int heads, int dimHead,
								 PackedCollection<?> rmsWeight,
								 PackedCollection<?> wk, PackedCollection<?> wv,
								 PackedCollection<?> wq, PackedCollection<?> wo,
								 Block context) {
		int dim = rmsWeight.getShape().length(0);

		SequentialBlock crossAttention = new SequentialBlock(shape(batchSize, querySeqLen, dim));

		// Create caches for context keys and values
		PackedCollection<?> keysCache = new PackedCollection<>(shape(batchSize, contextSeqLen, heads, dimHead));
		PackedCollection<?> valuesCache = new PackedCollection<>(shape(batchSize, contextSeqLen, heads, dimHead));

		crossAttention.add(rmsnorm(crossAttention.getOutputShape(), rmsWeight));

		/* KEYS **/
		SequentialBlock keyBranch = context.branch();
		keyBranch.add(dense(wk));
		keyBranch.reshape(batchSize, contextSeqLen, heads, dimHead);
		keyBranch.andThen(into(keysCache));
		/* ---- **/

		/* VALUES **/
		SequentialBlock valueBranch = context.branch();
		valueBranch.add(dense(wv));
		valueBranch.reshape(batchSize, contextSeqLen, heads, dimHead);
		valueBranch.andThen(into(valuesCache));
		/* ---- **/

		/* QUERY **/
		crossAttention.add(dense(wq));
		crossAttention.reshape(batchSize, querySeqLen, heads, dimHead);
		crossAttention.add(crossAttentionKeys(cp(keysCache)));
		crossAttention.add(softmax(true));
		crossAttention.add(crossAttentionValues(cp(valuesCache)));
		crossAttention.reshape(batchSize, querySeqLen, heads * dimHead);
		crossAttention.add(dense(wo));
		/* ---- **/

		return crossAttention;
	}

	default Block feedForward(
			PackedCollection<?> rms,
			PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		return feedForward(shape(dim), rms, w1, w2, w3, requirements);
	}

	default Block feedForward(
			TraversalPolicy shape,
			PackedCollection<?> rms,
			PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
			ComputeRequirement... requirements) {
		SequentialBlock feedForward = new SequentialBlock(shape);
		feedForward.add(rmsnorm(shape, rms, requirements));

		SequentialBlock hidden = new SequentialBlock(shape);
		hidden.add(dense(w1));
		hidden.add(silu());

		feedForward.product(dense(w3), hidden);
		feedForward.add(dense(w2));
		return feedForward;
	}

	default Block transformer(int heads,
							  PackedCollection<?> rmsAttWeight,
							  PackedCollection<?> wk, PackedCollection<?> wv,
							  PackedCollection<?> wq, PackedCollection<?> wo,
							  PackedCollection<?> freqCis,
							  PackedCollection<?> rmsFfnWeight,
							  PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
							  Producer<PackedCollection<?>> position,
							  ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);
		SequentialBlock transformer = new SequentialBlock(shape(dim));
		transformer.accum(attention(heads, rmsAttWeight, wk, wv, wq, wo, freqCis,
				position, requirements), requirements);
		transformer.accum(feedForward(rmsFfnWeight, w1, w2, w3, requirements), requirements);
		return transformer;
	}

	default Function<TraversalPolicy, CellularLayer> context(Block v, int batchSize, int heads, int dimHead, int size) {
		if (v.getOutputShape().getDimensions() != 4 ||
				v.getOutputShape().length(1) != heads ||
				v.getOutputShape().length(2) != dimHead ||
				v.getOutputShape().length(3) != size) {
			throw new IllegalArgumentException();
		}

		return compose("context", v, shape(batchSize, heads, dimHead, dimHead), (a, b) -> {
			CollectionProducer<PackedCollection<?>> pa = c(a)
					.traverse(3)
					.repeat(dimHead);
			CollectionProducer<PackedCollection<?>> pb = c(b)
					.traverse(2)
					.repeat(dimHead);
			return multiply(pa, pb).sum(4);
		});
	}

	default Function<TraversalPolicy, Block> linearAttention(int dim) {
		return shape -> {
			int batchSize = shape.length(0);
			int inputChannels = shape.length(1);
			int rows = shape.length(2);
			int cols = shape.length(3);
			return linearAttention(batchSize, dim, inputChannels, rows, cols);
		};
	}

	default Block linearAttention(int batchSize, int dim, int inputChannels, int rows, int cols) {
		return linearAttention(batchSize, dim, 4, 32, inputChannels, rows, cols);
	}

	default Block linearAttention(int batchSize, int dim, int heads, int dimHead,
								 int inputChannels, int rows, int cols) {
		double scale = 1.0 / Math.sqrt(dimHead);
		int hiddenDim = dimHead * heads;
		int size = rows * cols;

		TraversalPolicy shape = shape(batchSize, inputChannels, rows, cols);
		TraversalPolicy componentShape = shape(batchSize, heads, dimHead, size);

		SequentialBlock attention = new SequentialBlock(shape);
		attention.add(convolution2d(dim, hiddenDim * 3, 1, 0, false));

		attention
				.reshape(batchSize, 3, hiddenDim * size)
				.enumerate(shape(batchSize, 1, hiddenDim * size))
				.reshape(3, batchSize, heads, dimHead, size);

		List<Block> qkv = attention.split(componentShape, 1);
		Block q = qkv.get(0)
//				.andThen(scale(scale))
				.reshape(batchSize, heads, dimHead * size)
				.andThen(softmax(true))
				.andThen(scale(scale))
				.reshape(batchSize, heads, dimHead, size);
		Block v = qkv.get(2);

		attention.add(softmax(true));
		attention.add(context(v, batchSize, heads, dimHead, size));
		attention.add(similarity(q, heads, dimHead, size));
		attention.reshape(batchSize, hiddenDim, rows, cols);
		attention.add(convolution2d(hiddenDim, dim, 1, 0));
		attention.add(norm());

		if (!attention.getOutputShape().equalsIgnoreAxis(shape)) {
			throw new IllegalArgumentException();
		}

		return attention;
	}

	static AttentionFeatures getInstance() {
		return new AttentionFeatures() { };
	}
}
