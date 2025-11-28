/*
 * Copyright 2023 Michael Murray
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

import java.util.HashSet;
import java.util.Iterator;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.DiscreteField;
import io.almostrealism.relation.Producer;

/**
 * A composite shader that combines multiple shaders by summing their contributions.
 *
 * <p>{@code ShaderSet} implements the Composite pattern for shaders, allowing multiple
 * lighting effects to be combined into a single shader. When {@link #shade} is called,
 * each contained shader computes its contribution and the results are summed together.</p>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li>Combining diffuse and specular components for Phong-like materials</li>
 *   <li>Adding ambient + diffuse + specular for complete material shading</li>
 *   <li>Layering multiple highlight effects</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a material with diffuse and specular components
 * ShaderSet<ShaderContext> material = new ShaderSet<>();
 * material.add(new DiffuseShader());
 * material.add(new HighlightShader(white(), 32.0)); // Sharp specular
 *
 * // Apply the combined shader
 * Producer<PackedCollection> finalColor = material.shade(context, normalField);
 * }</pre>
 *
 * <h2>Color Combination</h2>
 * <p>Colors from all contained shaders are added together. This can result in
 * colors with channel values exceeding 1.0, which may need to be tone-mapped
 * or clamped before display.</p>
 *
 * @param <C> the type of {@link LightingContext} used by the contained shaders
 * @see Shader
 * @see DiffuseShader
 * @see HighlightShader
 * @author Michael Murray
 */
public class ShaderSet<C extends LightingContext> extends HashSet<Shader<C>> implements Shader<C>, RGBFeatures {
	/**
	 * Computes the combined color from all shaders in this set.
	 *
	 * <p>Each shader's {@code shade} method is called with the given parameters,
	 * and the resulting colors are summed together.</p>
	 *
	 * @param p the lighting context for shading calculations
	 * @param normals the surface normal field
	 * @return a {@link Producer} yielding the sum of all shader contributions,
	 *         or {@code null} if the set is empty
	 */
	@Override
	public Producer<PackedCollection> shade(C p, DiscreteField normals) {
		Iterator<Shader<C>> itr = super.iterator();
		Producer<PackedCollection> colors = null;

		while (itr.hasNext()) {
			colors = colors == null ? itr.next().shade(p, normals) : add(colors, itr.next().shade(p, normals));
		}

		return colors;
	}

	/**
	 * Always returns {@code false} to ensure ShaderSets are never considered equal.
	 *
	 * <p>This prevents duplicate detection issues when adding ShaderSets to other collections.</p>
	 *
	 * @param o the object to compare
	 * @return always {@code false}
	 */
	@Override
	public boolean equals(Object o) { return false; }

	/**
	 * Returns a string representation of this shader set.
	 *
	 * @return "ShaderSet[n]" where n is the number of contained shaders
	 */
	@Override
	public String toString() { return "ShaderSet[" + size() + "]"; }
}
