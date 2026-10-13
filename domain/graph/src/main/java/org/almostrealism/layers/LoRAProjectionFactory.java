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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link ProjectionFactory} that wraps targeted projections with LoRA
 * (Low-Rank Adaptation) adapters and delegates every other projection to a
 * fallback factory.
 *
 * <p>For a projection whose {@link AdapterConfig.TargetLayer} is targeted by the
 * configured {@link AdapterConfig}, this factory creates a {@link LoRALinear} and
 * hands it to the registration {@link Consumer} so the caller can track the new
 * adapter's trainable parameters. For every untargeted projection &mdash; and for
 * all projections when the config is {@code null} &mdash; it delegates to the
 * supplied fallback {@link ProjectionFactory}. Routing untargeted projections
 * through a caller-supplied fallback (composition) rather than an inherited
 * {@code dense(...)} lets the caller decide what an untargeted projection is; in
 * particular it lets {@link LowRankAdapterSupport} keep routing them through the
 * implementor's own {@code dense(...)}.</p>
 *
 * @see ProjectionFactory#lora(AdapterConfig, java.util.List)
 * @see DenseProjectionFactory
 * @see LowRankAdapterSupport
 * @author Michael Murray
 */
public class LoRAProjectionFactory implements ProjectionFactory {

	/** Adapter configuration specifying which layers to wrap; {@code null} means never adapt. */
	private final AdapterConfig config;
	/** Receives each {@link LoRALinear} this factory creates. */
	private final Consumer<LoRALinear> registry;
	/** Factory used for untargeted projections (and for all projections when {@code config} is null). */
	private final ProjectionFactory fallback;

	/**
	 * Creates a LoRA projection factory.
	 *
	 * @param config Adapter configuration specifying which layers to wrap; may be
	 *               {@code null} to never adapt (every projection goes to the fallback)
	 * @param registry Receives each {@link LoRALinear} this factory creates; required.
	 *                 To skip registration pass an explicit no-op ({@code layer -> { }}),
	 *                 not {@code null}
	 * @param fallback Factory used for untargeted projections (and for all projections
	 *                 when {@code config} is {@code null}); required
	 * @throws NullPointerException if {@code registry} or {@code fallback} is {@code null}
	 */
	public LoRAProjectionFactory(AdapterConfig config,
								 Consumer<LoRALinear> registry,
								 ProjectionFactory fallback) {
		this.config = config;
		this.registry = Objects.requireNonNull(registry, "registry");
		this.fallback = Objects.requireNonNull(fallback, "fallback");
	}

	/**
	 * Creates a projection layer, wrapping it with a LoRA adapter when targeted.
	 *
	 * <p>When the configured {@link AdapterConfig} targets {@code targetLayer},
	 * this builds a {@link LoRALinear} adapter and registers it through the
	 * registration {@link Consumer} supplied at construction. Otherwise &mdash;
	 * including when the config is {@code null} &mdash; it delegates to the
	 * fallback {@link ProjectionFactory}. The shared parameter contract is
	 * documented on {@link ProjectionFactory#create(TraversalPolicy, PackedCollection, PackedCollection, AdapterConfig.TargetLayer)}.</p>
	 *
	 * @return A {@link LoRALinear} for a targeted projection, otherwise the fallback factory's layer
	 */
	@Override
	public CellularLayer create(TraversalPolicy inputShape,
								PackedCollection weights,
								PackedCollection bias,
								AdapterConfig.TargetLayer targetLayer) {
		if (config == null || !config.isTargeted(targetLayer)) {
			return fallback.create(inputShape, weights, bias, targetLayer);
		}

		LoRALinear layer = new LoRALinear(inputShape, weights, bias,
				config.getRank(), config.getAlpha());
		registry.accept(layer);
		return layer;
	}
}
