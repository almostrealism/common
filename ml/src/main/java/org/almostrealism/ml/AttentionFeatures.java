/*
 * Copyright 2024 Michael Murray
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

	default CellularLayer attentionKeys(TraversalPolicy inputShape, Producer<PackedCollection<?>> keys,
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

		return layer("attentionKeys", inputShape, outputShape, input ->
				traverse(1, keys).map(v -> v.multiply(input))
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads))
						.enumerate(1, 1)
						.reshape(outputShape), requirements);
	}

	default CellularLayer attentionValues(TraversalPolicy inputShape, Producer<PackedCollection<?>> values,
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

	default Block feedForward(
			PackedCollection<?> rms,
			PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		int hiddenDim = w1.getShape().length(0);

		SequentialBlock feedForward = new SequentialBlock(shape(dim));
		feedForward.add(rmsnorm(rms, requirements));

		SequentialBlock hidden = new SequentialBlock(shape(dim));
		hidden.add(dense(w1));
		hidden.add(silu(shape(hiddenDim)));

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
