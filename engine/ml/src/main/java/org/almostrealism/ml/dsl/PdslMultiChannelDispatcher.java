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

package org.almostrealism.ml.dsl;

import org.almostrealism.model.Block;

import java.util.List;

/**
 * Builds the {@link Block} that backs the PDSL {@code for each channel} statement.
 *
 * <p>{@code for each channel} is a control-flow construct (a {@link PdslNode.Statement})
 * rather than a primitive call, so its interpretation lives in {@link PdslInterpreter}.
 * The actual block-construction step — taking N per-channel sub-blocks and wiring them
 * into a parallel-dispatch block — is delegated through this dispatcher so that the
 * interpreter does not have to know about audio-domain block factories. Higher-level
 * modules supply the implementation via
 * {@link PdslInterpreter#setMultiChannelDispatcher(PdslMultiChannelDispatcher)}.</p>
 */
@FunctionalInterface
public interface PdslMultiChannelDispatcher {

	/**
	 * Combine N per-channel sub-blocks into a single multi-channel dispatch block.
	 *
	 * @param channelBlocks one block per channel, in channel order
	 * @param channels      total channel count (equals {@code channelBlocks.size()})
	 * @param signalSize    per-channel sample count
	 * @return a block whose input shape is {@code [channels, signalSize]} and whose
	 *         output shape is the concatenation of the per-channel outputs
	 */
	Block perChannel(List<Block> channelBlocks, int channels, int signalSize);
}
