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

package org.almostrealism.ml.llama2;

import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.ByteBufferTransfer;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.DirectMemory;
import org.almostrealism.hardware.mem.RAM;

import java.nio.ByteBuffer;

/**
 * Weight tensors for a Llama2 model, loaded from a binary checkpoint.
 *
 * <p>Weights are read sequentially from the checkpoint buffer in the order
 * defined by the original llama2.c format. Each tensor moves directly from
 * the checkpoint into a staging allocation via {@link ByteBufferTransfer} —
 * no host arrays — and the framework migrates it to a compute device when a
 * kernel first requires it. All tensors are stored as {@link PackedCollection}
 * instances for use with the AR compute pipeline.</p>
 *
 * @author Michael Murray
 */
public class Llama2Weights implements CodeFeatures {
	/** Token embedding table (vocab_size, dim). */
	public final PackedCollection tokenEmbeddings;

	/** RMS norm weights for attention layers (layer, dim). */
	public final PackedCollection rmsAttWeights;

	/** Query projection weights (layer, dim, dim). */
	public final PackedCollection wq;

	/** Key projection weights (layer, dim, dim). */
	public final PackedCollection wk;

	/** Value projection weights (layer, dim, dim). */
	public final PackedCollection wv;

	/** Output projection weights (layer, dim, dim). */
	public final PackedCollection wo;

	/** RMS norm weights for FFN layers (layer, dim). */
	public final PackedCollection rmsFfn;

	/** FFN gate weights (layer, hidden_dim, dim). */
	public final PackedCollection w1;

	/** FFN down-projection weights (layer, dim, hidden_dim). */
	public final PackedCollection w2;

	/** FFN up-projection weights (layer, hidden_dim, dim). */
	public final PackedCollection w3;

	/** Final RMS norm weight (dim). */
	public final PackedCollection rmsFinalWeight;

	/** RoPE frequency components (seq_len, head_size/2, 2). */
	public final PackedCollection freqCis;

	/** Classifier weights for logits (may alias tokenEmbeddings). */
	public final PackedCollection wcls;

	/**
	 * Reads all weight tensors from the checkpoint buffer.
	 *
	 * @param config the model configuration (defines tensor shapes)
	 * @param buffer the checkpoint buffer positioned after the header
	 */
	public Llama2Weights(Llama2Config config, ByteBuffer buffer) {
		this.tokenEmbeddings = stage(shape(config.vocabSize, config.dim), buffer);
		this.rmsAttWeights = stage(shape(config.layerCount, config.dim), buffer);

		this.wq = stage(shape(config.layerCount, config.dim, config.dim), buffer);
		this.wk = stage(shape(config.layerCount, config.dim, config.dim), buffer);
		this.wv = stage(shape(config.layerCount, config.dim, config.dim), buffer);
		this.wo = stage(shape(config.layerCount, config.dim, config.dim), buffer);

		this.rmsFfn = stage(shape(config.layerCount, config.dim), buffer);

		this.w1 = stage(shape(config.layerCount, config.hiddenDim, config.dim), buffer);
		this.w2 = stage(shape(config.layerCount, config.dim, config.hiddenDim), buffer);
		this.w3 = stage(shape(config.layerCount, config.hiddenDim, config.dim), buffer);

		this.rmsFinalWeight = stage(shape(config.dim), buffer);

		int freqCount = config.seqLen * (config.headSize / 2);
		this.freqCis = stage(shape(config.seqLen, config.headSize / 2, 2),
				take(buffer, freqCount), take(buffer, freqCount));

		this.wcls = config.sharedWeights ? tokenEmbeddings
				: stage(shape(config.vocabSize, config.dim), buffer);
	}

	/**
	 * Slices the next {@code count} elements off the buffer as an independent
	 * region, advancing the buffer past them. Used when a tensor combines
	 * multiple checkpoint regions, such as the interleaved RoPE frequencies.
	 *
	 * @param buffer the checkpoint buffer positioned at the region's data
	 * @param count  the number of elements in the region
	 * @return a buffer covering exactly the region, in the checkpoint's byte order
	 */
	static ByteBuffer take(ByteBuffer buffer, int count) {
		int bytes = count * Precision.FP32.bytes();
		ByteBuffer slice = buffer.slice().order(buffer.order());
		slice.limit(bytes);
		buffer.position(buffer.position() + bytes);
		return slice;
	}

	/**
	 * Stages the next tensor into a native buffer allocation, interleaving the
	 * given checkpoint regions element by element. With a single source this is
	 * a straight copy of the tensor's data; with several, one element is drawn
	 * from each source in turn, as for tensors stored as separate planes in the
	 * checkpoint but consumed interleaved.
	 *
	 * @param shape   the shape of the tensor being staged
	 * @param sources one or more checkpoint regions holding the tensor's values
	 * @return a collection rooted over the staging allocation
	 */
	static PackedCollection stage(TraversalPolicy shape, ByteBuffer... sources) {
		int total = shape.getTotalSize();
		if (total % sources.length != 0)
			throw new IllegalArgumentException();

		MemoryProvider<? extends RAM> provider =
				Hardware.getLocalHardware().getNativeBufferMemoryProvider();
		RAM mem = provider.allocate(total);

		ByteBuffer staging = ((DirectMemory) mem).asByteBuffer();
		Precision destination = Precision.ofBytes(provider.getNumberSize());

		ByteBufferTransfer transfers[] = new ByteBufferTransfer[sources.length];
		for (int i = 0; i < sources.length; i++) {
			transfers[i] = new ByteBufferTransfer(sources[i], Precision.FP32,
					staging, destination);
		}

		if (transfers.length == 1) {
			transfers[0].copy(total);
		} else {
			for (int i = 0; i < total; i += transfers.length) {
				for (ByteBufferTransfer transfer : transfers) {
					transfer.copyNext();
				}
			}
		}

		return new PackedCollection(shape, shape.getTraversalAxis(),
				Bytes.of(mem, total), 0);
	}
}
