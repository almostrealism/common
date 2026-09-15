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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.t5gemma.T5GemmaEncoder;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The conditioner of Stable Audio 3: a prompt encoded by a {@link T5GemmaEncoder}, with every
 * padded position replaced by a learned padding embedding, followed by one duration token from a
 * {@link NumberConditioner}, forms the cross-attention context; the duration embedding alone is
 * the global conditioning.
 *
 * <p>The prompt encoder, the padding substitution and the duration token are one compiled model
 * whose inputs are the token ids and, as graph leaves, the encoder's validity mask and the
 * normalized duration. {@link #runConditioners(long[], double)} loads those and runs it once; the
 * global conditioning is a view of the last context token, so nothing is evaluated on the host.
 * The returned tensors are the model's output buffers and are overwritten by the next call.</p>
 */
public class StableAudio3Conditioner implements AudioAttentionConditioner, CodeFeatures {

	/** Every conditioner handles one prompt at a time. */
	private static final int BATCH = 1;

	/** The prompt encoder. */
	private final T5GemmaEncoder encoder;

	/** The duration embedder. */
	private final NumberConditioner duration;

	/** Normalized duration, shape {@code [1, 1]}; a leaf of the graph. */
	private final PackedCollection normalizedDuration;

	/** Validity of every context token: all ones, since the padding is substituted rather than masked. */
	private final PackedCollection contextMask;

	/** The compiled conditioner. */
	private final CompiledModel compiled;

	/**
	 * Creates the conditioner.
	 *
	 * @param encoder          the prompt encoder
	 * @param paddingEmbedding the learned embedding substituted at padded prompt positions, shape
	 *                         {@code [hiddenSize]}
	 * @param duration         the duration embedder, producing the encoder's hidden width
	 */
	public StableAudio3Conditioner(T5GemmaEncoder encoder, PackedCollection paddingEmbedding,
								   NumberConditioner duration) {
		int hidden = encoder.getConfig().getHiddenSize();
		int length = encoder.getConfig().getMaxLength();

		if (paddingEmbedding.getShape().getTotalSize() != hidden) {
			throw new IllegalArgumentException("The padding embedding must have " + hidden + " values");
		}

		if (duration.getOutputDim() != hidden) {
			throw new IllegalArgumentException("The duration embedding must have " + hidden + " values");
		}

		this.encoder = encoder;
		this.duration = duration;
		this.normalizedDuration = new PackedCollection(shape(BATCH, 1));
		this.normalizedDuration.clear();
		this.contextMask = new PackedCollection(shape(BATCH, length + 1)).fill(1.0);

		Model model = new Model(shape(BATCH, length));
		model.add(encoder.block());
		model.add(learnedPadding(shape(BATCH, length, hidden), paddingEmbedding));
		model.add(appendDuration(shape(BATCH, length, hidden)));
		this.compiled = model.compile(false);
	}

	/**
	 * Replaces the hidden state of every padded position with the learned padding embedding,
	 * keeping the prompt positions as encoded.
	 *
	 * @param shape            the hidden state shape {@code [batch, maxLength, hiddenSize]}
	 * @param paddingEmbedding the padding embedding, shape {@code [hiddenSize]}
	 * @return the substitution layer
	 */
	private Block learnedPadding(TraversalPolicy shape, PackedCollection paddingEmbedding) {
		int length = shape.length(1);
		int hidden = shape.length(2);

		return layer("learnedPadding", shape, shape, hiddenStates -> {
			CollectionProducer valid = broadcast(shape, 1, cp(encoder.getAttentionMask()));
			CollectionProducer padded = cp(paddingEmbedding).reshape(BATCH, hidden).repeat(1, length).reshape(shape);
			return c(hiddenStates).multiply(valid).add(padded.multiply(valid.multiply(-1.0).add(1.0)));
		}, List.of(paddingEmbedding));
	}

	/**
	 * Appends the duration token to the prompt context.
	 *
	 * @param shape the prompt context shape {@code [batch, maxLength, hiddenSize]}
	 * @return the layer producing {@code [batch, maxLength + 1, hiddenSize]}
	 */
	private Block appendDuration(TraversalPolicy shape) {
		int length = shape.length(1);
		int hidden = shape.length(2);
		TraversalPolicy extended = shape(BATCH, length + 1, hidden);
		CollectionProducer token = duration.embed(cp(normalizedDuration)).reshape(BATCH, 1, hidden);
		return layer("appendDuration", shape, extended, context -> concat(1, c(context), token));
	}

	@Override
	public ConditionerOutput runConditioners(long[] tokenIds, double durationSeconds) {
		PackedCollection ids = encoder.loadPrompt(tokenIds);

		ByteBuffer value = ByteBuffer.allocate(Double.BYTES);
		value.putDouble(duration.normalize(durationSeconds));
		normalizedDuration.read(value.flip());

		PackedCollection context = compiled.forward(ids);
		int hidden = encoder.getConfig().getHiddenSize();
		int length = encoder.getConfig().getMaxLength();
		PackedCollection global = context.range(shape(BATCH, hidden), length * hidden);
		return new ConditionerOutput(context, contextMask, global);
	}

	@Override
	public void destroy() {
		compiled.destroy();
		normalizedDuration.destroy();
		contextMask.destroy();
		encoder.destroy();
	}
}
