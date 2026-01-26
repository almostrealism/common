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

package org.almostrealism.layers;

import java.util.EnumSet;
import java.util.Set;

/**
 * Configuration for LoRA (Low-Rank Adaptation) adapters.
 *
 * <p>This class specifies which layers should be wrapped with LoRA adapters
 * and the hyperparameters for the adaptation. It follows the builder pattern
 * for fluent configuration.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Default configuration for audio diffusion (attention projections only)
 * AdapterConfig config = AdapterConfig.forAudioDiffusion();
 *
 * // Custom configuration
 * AdapterConfig config = new AdapterConfig()
 *     .rank(16)
 *     .alpha(32.0)
 *     .targets(TargetLayer.SELF_ATTENTION_QKV, TargetLayer.SELF_ATTENTION_OUT,
 *              TargetLayer.CROSS_ATTENTION_Q, TargetLayer.CROSS_ATTENTION_KV);
 * }</pre>
 *
 * <h2>Target Layers</h2>
 * <p>LoRA can be applied to different types of layers:</p>
 * <ul>
 *   <li><b>Attention projections</b>: Q, K, V, and output projections (most common)</li>
 *   <li><b>Feed-forward layers</b>: MLP/FFN layers (less common, larger adapters)</li>
 * </ul>
 *
 * <h2>Hyperparameter Guidelines</h2>
 * <ul>
 *   <li><b>rank</b>: Typical values are 4, 8, 16, 32. Higher = more capacity but more parameters</li>
 *   <li><b>alpha</b>: Typical value is 2*rank. Controls the scaling of LoRA contribution</li>
 * </ul>
 *
 * @see LoRALinear
 * @author Michael Murray
 */
public class AdapterConfig {

	/**
	 * Enumeration of layer types that can be targeted for LoRA adaptation.
	 */
	public enum TargetLayer {
		/** Self-attention Q, K, V projections (often fused as QKV) */
		SELF_ATTENTION_QKV,
		/** Self-attention output projection */
		SELF_ATTENTION_OUT,
		/** Cross-attention Q projection */
		CROSS_ATTENTION_Q,
		/** Cross-attention K, V projections (often fused as KV) */
		CROSS_ATTENTION_KV,
		/** Cross-attention output projection */
		CROSS_ATTENTION_OUT,
		/** Feed-forward/MLP first layer (gate projection) */
		FFN_GATE,
		/** Feed-forward/MLP second layer (output projection) */
		FFN_OUT
	}

	private int rank = 8;
	private double alpha = 16.0;
	private Set<TargetLayer> targets = EnumSet.of(
			TargetLayer.SELF_ATTENTION_QKV,
			TargetLayer.SELF_ATTENTION_OUT
	);

	/**
	 * Creates an AdapterConfig with default settings.
	 * Default: rank=8, alpha=16.0, targets=self-attention QKV and output projections.
	 */
	public AdapterConfig() {
	}

	/**
	 * Sets the low-rank dimension for LoRA matrices.
	 *
	 * @param rank The rank (typical values: 4, 8, 16, 32)
	 * @return This config for chaining
	 */
	public AdapterConfig rank(int rank) {
		if (rank < 1) {
			throw new IllegalArgumentException("Rank must be at least 1");
		}
		this.rank = rank;
		return this;
	}

	/**
	 * Sets the alpha scaling factor for LoRA.
	 * The LoRA contribution is scaled by (alpha / rank).
	 *
	 * @param alpha The alpha value (typical: 2 * rank)
	 * @return This config for chaining
	 */
	public AdapterConfig alpha(double alpha) {
		if (alpha <= 0) {
			throw new IllegalArgumentException("Alpha must be positive");
		}
		this.alpha = alpha;
		return this;
	}

	/**
	 * Sets which layer types should have LoRA adapters.
	 *
	 * @param targets The target layer types
	 * @return This config for chaining
	 */
	public AdapterConfig targets(TargetLayer... targets) {
		this.targets = EnumSet.noneOf(TargetLayer.class);
		for (TargetLayer target : targets) {
			this.targets.add(target);
		}
		return this;
	}

	/**
	 * Sets which layer types should have LoRA adapters.
	 *
	 * @param targets The target layer types
	 * @return This config for chaining
	 */
	public AdapterConfig targets(Set<TargetLayer> targets) {
		this.targets = EnumSet.copyOf(targets);
		return this;
	}

	/**
	 * Returns the low-rank dimension.
	 */
	public int getRank() {
		return rank;
	}

	/**
	 * Returns the alpha scaling factor.
	 */
	public double getAlpha() {
		return alpha;
	}

	/**
	 * Returns the target layer types.
	 */
	public Set<TargetLayer> getTargets() {
		return EnumSet.copyOf(targets);
	}

	/**
	 * Checks if a specific layer type is targeted for LoRA.
	 *
	 * @param target The layer type to check
	 * @return true if this layer type should have LoRA adapters
	 */
	public boolean isTargeted(TargetLayer target) {
		return targets.contains(target);
	}

	/**
	 * Creates a configuration optimized for audio diffusion models.
	 * Targets attention projections only (QKV and output for both self and cross attention).
	 *
	 * @return Configuration with rank=8, alpha=16, attention projections targeted
	 */
	public static AdapterConfig forAudioDiffusion() {
		return new AdapterConfig()
				.rank(8)
				.alpha(16.0)
				.targets(
						TargetLayer.SELF_ATTENTION_QKV,
						TargetLayer.SELF_ATTENTION_OUT,
						TargetLayer.CROSS_ATTENTION_Q,
						TargetLayer.CROSS_ATTENTION_KV,
						TargetLayer.CROSS_ATTENTION_OUT
				);
	}

	/**
	 * Creates a configuration for full LoRA (all layers).
	 * This provides maximum adaptation capacity but uses more parameters.
	 *
	 * @return Configuration with rank=8, alpha=16, all layers targeted
	 */
	public static AdapterConfig full() {
		return new AdapterConfig()
				.rank(8)
				.alpha(16.0)
				.targets(TargetLayer.values());
	}

	/**
	 * Creates a minimal configuration (self-attention only, small rank).
	 * Good for quick experimentation or limited compute.
	 *
	 * @return Configuration with rank=4, alpha=8, self-attention only
	 */
	public static AdapterConfig minimal() {
		return new AdapterConfig()
				.rank(4)
				.alpha(8.0)
				.targets(
						TargetLayer.SELF_ATTENTION_QKV,
						TargetLayer.SELF_ATTENTION_OUT
				);
	}

	@Override
	public String toString() {
		return "AdapterConfig{rank=" + rank + ", alpha=" + alpha + ", targets=" + targets + "}";
	}
}
