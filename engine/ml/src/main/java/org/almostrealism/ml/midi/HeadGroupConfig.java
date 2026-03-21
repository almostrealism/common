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

package org.almostrealism.ml.midi;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Configuration for a single head group in Multidimensional Relative Attention (MRA).
 *
 * <p>In MRA, attention heads are partitioned into groups, each applying RoPE with
 * a different theta basis and attribute-derived position IDs. Each head group
 * bundles the number of heads, precomputed RoPE frequencies for its theta value,
 * and a producer that provides the attribute value as position.</p>
 *
 * <h2>Moonbeam Configuration</h2>
 * <p>Moonbeam partitions 12 heads into 6 groups of 2, each with a different RoPE theta:</p>
 * <table>
 * <caption>Head Group Assignments</caption>
 *   <tr><th>Group</th><th>Heads</th><th>Attribute</th><th>Theta</th></tr>
 *   <tr><td>0</td><td>0-1</td><td>Onset</td><td>199,999</td></tr>
 *   <tr><td>1</td><td>2-3</td><td>Duration</td><td>1,031</td></tr>
 *   <tr><td>2</td><td>4-5</td><td>Octave</td><td>19</td></tr>
 *   <tr><td>3</td><td>6-7</td><td>Pitch class</td><td>20</td></tr>
 *   <tr><td>4</td><td>8-9</td><td>Instrument</td><td>199,999</td></tr>
 *   <tr><td>5</td><td>10-11</td><td>Velocity</td><td>131</td></tr>
 * </table>
 *
 * @see org.almostrealism.ml.AttentionFeatures
 * @see MoonbeamConfig
 */
public class HeadGroupConfig {
	/** Number of query attention heads in this group. */
	public final int headCount;

	/**
	 * Precomputed RoPE frequency tensor for this group's theta value.
	 * Shape: (maxSeqLen, headDim/2, 2) containing [cos, sin] pairs.
	 */
	public final PackedCollection freqCis;

	/**
	 * Producer providing the attribute value as position index into freqCis.
	 * For example, for the onset group this provides the onset tick value;
	 * for the octave group, the octave number.
	 */
	public final Producer<PackedCollection> position;

	/**
	 * Create a head group configuration.
	 *
	 * @param headCount number of query attention heads in this group
	 * @param freqCis precomputed RoPE frequencies for this group's theta,
	 *                shape (maxSeqLen, headDim/2, 2) containing [cos, sin]
	 * @param position producer providing the attribute value as RoPE position
	 */
	public HeadGroupConfig(int headCount, PackedCollection freqCis,
						   Producer<PackedCollection> position) {
		this.headCount = headCount;
		this.freqCis = freqCis;
		this.position = position;
	}

	/**
	 * Compute RoPE frequency tensor for a given theta and head dimension.
	 *
	 * <p>Precomputes cos and sin values for all positions in [0, maxSeqLen)
	 * using the given theta as the RoPE base frequency. This reuses the same
	 * mathematical formula as Qwen3's computeRopeFreqs:</p>
	 * <pre>
	 * invFreq[i] = 1.0 / theta^(2*i / headDim)
	 * angle = position * invFreq[i]
	 * freqCis[pos, i, 0] = cos(angle)
	 * freqCis[pos, i, 1] = sin(angle)
	 * </pre>
	 *
	 * @param theta RoPE base frequency (e.g., 199999 for onset, 19 for octave)
	 * @param headDim per-head dimension (e.g., 160 for Moonbeam)
	 * @param maxSeqLen maximum position value to precompute
	 * @return frequency tensor of shape (maxSeqLen, headDim/2, 2) with [cos, sin] pairs
	 */
	public static PackedCollection computeFreqCis(double theta, int headDim, int maxSeqLen) {
		int freqDim = headDim / 2;
		double[] invFreqs = FundamentalMusicEmbedding.computeInvFreqs(theta, headDim);

		PackedCollection freqCis = new PackedCollection(maxSeqLen, freqDim, 2);
		for (int pos = 0; pos < maxSeqLen; pos++) {
			for (int f = 0; f < freqDim; f++) {
				double angle = pos * invFreqs[f];
				int idx = (pos * freqDim + f) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}

		return freqCis;
	}

	/**
	 * Build head group configurations for all attribute groups from a {@link MoonbeamConfig}.
	 *
	 * <p>Creates one {@link HeadGroupConfig} per attribute, each with its own
	 * precomputed RoPE frequencies based on the attribute's theta value.</p>
	 *
	 * @param config the Moonbeam model configuration
	 * @param attributePositions array of 6 producers providing per-attribute position values
	 * @return array of 6 head group configurations
	 */
	public static HeadGroupConfig[] fromConfig(MoonbeamConfig config,
											   Producer<PackedCollection>[] attributePositions) {
		int numGroups = config.ropeThetas.length;
		HeadGroupConfig[] groups = new HeadGroupConfig[numGroups];

		for (int g = 0; g < numGroups; g++) {
			PackedCollection freqCis = computeFreqCis(
					config.ropeThetas[g], config.headDim, config.maxSeqLen);
			groups[g] = new HeadGroupConfig(
					config.headsPerGroup[g], freqCis, attributePositions[g]);
		}

		return groups;
	}
}
