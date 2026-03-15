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

import java.nio.ByteBuffer;

/**
 * Configuration for a Llama2 model, read from the binary checkpoint header.
 *
 * <p>The header format is seven consecutive 32-bit little-endian integers:
 * dim, hiddenDim, layerCount, headCount, kvHeadCount, vocabSize, seqLen.
 * A negative vocabSize indicates that the embedding weights are not shared
 * with the output classifier.</p>
 *
 * @author Michael Murray
 */
public class Llama2Config {
	/** Transformer dimension. */
	public final int dim;

	/** FFN hidden layer dimension. */
	public final int hiddenDim;

	/** Number of transformer layers. */
	public final int layerCount;

	/** Number of query attention heads. */
	public final int headCount;

	/** Number of key/value attention heads. */
	public final int kvHeadCount;

	/** Vocabulary size (always positive after parsing). */
	public final int vocabSize;

	/** Maximum sequence length. */
	public final int seqLen;

	/** Whether the token embedding table is shared with the classifier. */
	public final boolean sharedWeights;

	/** Size of each attention head ({@code dim / headCount}). */
	public final int headSize;

	/**
	 * Reads configuration from a checkpoint header.
	 *
	 * @param buffer the byte buffer positioned at the start of the header
	 */
	public Llama2Config(ByteBuffer buffer) {
		this.dim = buffer.getInt();
		this.hiddenDim = buffer.getInt();
		this.layerCount = buffer.getInt();
		this.headCount = buffer.getInt();
		this.kvHeadCount = buffer.getInt();

		int vocabSize = buffer.getInt();
		this.vocabSize = Math.abs(vocabSize);
		this.seqLen = buffer.getInt();
		this.sharedWeights = vocabSize > 0;
		this.headSize = dim / headCount;
	}
}
