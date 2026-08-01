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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.RotationFeatures;

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
	public final CollectionProducer freqCis;

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
	public HeadGroupConfig(int headCount, CollectionProducer freqCis,
						   Producer<PackedCollection> position) {
		this.headCount = headCount;
		this.freqCis = freqCis;
		this.position = position;
	}

	/**
	 * Build head group configurations from individual parameters.
	 *
	 * <p>Creates one {@link HeadGroupConfig} per attribute group, each with its own
	 * precomputed RoPE frequencies for its theta value. Delegates frequency computation
	 * to {@link org.almostrealism.ml.RotationFeatures#computeRopeFreqs}.</p>
	 *
	 * @param ropeThetas        per-group RoPE base frequencies
	 * @param headDim           per-head dimension
	 * @param maxSeqLen         maximum sequence length to precompute
	 * @param headsPerGroup     number of heads in each group
	 * @param attributePositions producers providing per-attribute position values
	 * @return array of head group configurations, one per group
	 */
	public static HeadGroupConfig[] fromParams(double[] ropeThetas, int headDim, int maxSeqLen,
											   int[] headsPerGroup,
											   Producer<PackedCollection>[] attributePositions) {
		HeadGroupConfig[] groups = new HeadGroupConfig[ropeThetas.length];
		for (int g = 0; g < ropeThetas.length; g++) {
			CollectionProducer freqCis = RotationFeatures.computeRopeFreqs(
					ropeThetas[g], headDim, maxSeqLen);
			groups[g] = new HeadGroupConfig(headsPerGroup[g], freqCis, attributePositions[g]);
		}
		return groups;
	}
}
