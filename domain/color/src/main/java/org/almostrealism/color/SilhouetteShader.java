/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.DiscreteField;

/**
 * Provides flat-color shading that ignores lighting, rendering surfaces as solid silhouettes.
 *
 * <p>A {@code SilhouetteShader} applies a single, uniform color to all pixels where
 * a surface appears, regardless of lighting conditions, surface orientation, or
 * any other factors. This is useful for:</p>
 * <ul>
 *   <li>Creating silhouette masks for compositing</li>
 *   <li>Object ID rendering for selection</li>
 *   <li>Flat/graphic design aesthetics</li>
 *   <li>Debug visualization (e.g., seeing object extents)</li>
 *   <li>Stencil/outline effects when combined with edge detection</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Black silhouette for shadow casting
 * SilhouetteShader shadow = new SilhouetteShader(black());
 *
 * // Colored silhouette for object identification
 * SilhouetteShader objectId = new SilhouetteShader(rgb(1.0, 0.0, 0.0));
 *
 * // Apply the shader
 * Producer<PackedCollection> flatColor = silhouette.shade(context, normalField);
 * }</pre>
 *
 * <h2>Implementation Note</h2>
 * <p>This shader completely ignores the light direction, surface normal, and
 * all other context information. The same color is returned regardless of
 * rendering parameters.</p>
 *
 * @see Shader
 * @see DiffuseShader
 * @author Michael Murray
 */
public class SilhouetteShader implements Evaluable<PackedCollection>, Shader<LightingContext>, RGBFeatures {
	/** The uniform color returned for all shaded pixels. */
	private Producer<PackedCollection> color;

	/**
	 * Constructs a new {@link SilhouetteShader} using black as a color.
	 */
	public SilhouetteShader() { this.color = black(); }

	/**
	 * Constructs a new {@link SilhouetteShader} using the specified {@link RGB}
	 * {@link Evaluable} as a color.
	 *
	 * @param color  RGB Producer to use.
	 */
	public SilhouetteShader(Producer<PackedCollection> color) { this.color = color; }

	/**
	 * @see  Shader#shade(LightingContext, DiscreteField)
	 */
	@Override
	public Producer<PackedCollection> shade(LightingContext p, DiscreteField normals) {
		return color; // GeneratedColorProducer.fromProducer(this, color);
	}

	/**
	 * @see Evaluable#evaluate(java.lang.Object[])
	 */
	@Override
	public PackedCollection evaluate(Object[] args) { return this.color.get().evaluate(args); }

	/**
	 * @return  "Silhouette Shader".
	 */
	@Override
	public String toString() { return "Silhouette Shader"; }
}
