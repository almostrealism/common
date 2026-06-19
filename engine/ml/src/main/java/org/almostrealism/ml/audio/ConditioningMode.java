/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.audio;

/**
 * Selects how a {@link DiffusionTransformer} injects timestep and global conditioning into the
 * transformer stack.
 *
 * <p>{@link #PREPEND} is the default and preserves the existing behaviour exactly: the combined
 * timestep and global conditioning is projected and prepended as an extra sequence token before the
 * transformer blocks. {@link #ADALN} instead drives adaptive layer-normalization (adaLN-Zero)
 * modulation, where the conditioning produces per-block scale/shift/gate vectors that modulate each
 * sub-layer in place without lengthening the sequence.</p>
 *
 * @author  Michael Murray
 * @see DiffusionTransformer
 * @see org.almostrealism.ml.AdaptiveLayerNormFeatures
 */
public enum ConditioningMode {
	/**
	 * Prepended conditioning: the timestep and global conditioning are summed, projected and prepended
	 * to the audio sequence as one extra token, then stripped after the transformer blocks. This is the
	 * default and is numerically unchanged from the original {@code DiffusionTransformer} behaviour.
	 */
	PREPEND,

	/**
	 * Adaptive layer-normalization (adaLN-Zero) conditioning: the conditioning vector produces, per
	 * block, scale/shift/gate modulation vectors that reshape the normalized activations of the
	 * self-attention and feed-forward sub-layers. The sequence length is unchanged.
	 */
	ADALN
}
