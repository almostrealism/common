/*
 * Copyright 2017 Michael Murray
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

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;

/**
 * Represents an object that can be shaded to produce a color based on lighting conditions.
 *
 * <p>A {@code Shadable} is typically a surface or geometry that can compute its
 * visible color given a {@link ShaderContext} containing lighting information.
 * This interface bridges the gap between geometric objects and the shading system,
 * allowing surfaces to delegate to their associated shaders.</p>
 *
 * <h2>Relationship to Shader</h2>
 * <p>While {@link Shader} defines a standalone shading algorithm, {@code Shadable}
 * represents an object that <em>has</em> shading behavior. A typical implementation
 * stores one or more {@link Shader} instances and delegates to them:</p>
 * <pre>{@code
 * public class MySurface implements Shadable {
 *     private Shader<ShaderContext> shader = new DiffuseShader();
 *
 *     public Producer<PackedCollection> shade(ShaderContext ctx) {
 *         return shader.shade(ctx, getNormalField());
 *     }
 * }
 * }</pre>
 *
 * <h2>Common Implementations</h2>
 * <ul>
 *   <li>{@link ShadableSurface}: Extends Shadable with front/back shading control</li>
 *   <li>{@link ShadableCurve}: Parametric curves with shading support</li>
 * </ul>
 *
 * @see Shader
 * @see ShaderContext
 * @see ShadableSurface
 * @author Michael Murray
 */
public interface Shadable {
	/**
	 * Computes the color of this object given lighting parameters.
	 *
	 * @param parameters the shader context containing light, surface, and intersection info
	 * @return a {@link Producer} that yields the computed {@link RGB} color
	 * @see Shader
	 */
	Producer<PackedCollection> shade(ShaderContext parameters);
}
