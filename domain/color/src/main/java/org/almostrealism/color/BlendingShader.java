/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.RayFeatures;

/**
 * Provides stylized shading by blending between two colors based on lighting intensity.
 *
 * <p>A {@code BlendingShader} interpolates between a "hot" color (for lit areas) and
 * a "cold" color (for shadowed areas) based on the dot product between the surface
 * normal and light direction. This technique is commonly used for:</p>
 * <ul>
 *   <li>Cool-to-warm shading (artistic rendering)</li>
 *   <li>Cartoon/cel shading effects</li>
 *   <li>Non-photorealistic rendering (NPR)</li>
 *   <li>Stylized game graphics</li>
 * </ul>
 *
 * <h2>Blending Formula</h2>
 * <pre>
 * k = (N dot L) + 1.0    // Range: [0, 2]
 * color = hotColor * k + coldColor * (1 - k)
 * </pre>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Orange to dark brown for warm sunset lighting
 * BlendingShader warm = new BlendingShader(
 *     rgb(1.0, 0.6, 0.2),   // Hot: bright orange
 *     rgb(0.2, 0.1, 0.05)   // Cold: dark brown
 * );
 *
 * // Cel shading with hard color transitions
 * BlendingShader cel = new BlendingShader(
 *     rgb(1.0, 1.0, 1.0),   // Lit areas: white
 *     rgb(0.3, 0.3, 0.4)    // Shadows: cool gray
 * );
 * }</pre>
 *
 * <h2>Artistic Considerations</h2>
 * <ul>
 *   <li>Use complementary colors for dramatic contrast</li>
 *   <li>Cool shadows with warm highlights create depth</li>
 *   <li>Saturated hot colors with desaturated cold colors add focus</li>
 * </ul>
 *
 * @see DiffuseShader
 * @see Shader
 * @author Michael Murray
 */
public class BlendingShader implements Shader<LightingContext>, RGBFeatures, RayFeatures {
  /** The color applied to fully-lit areas (where N dot L is maximum). */
  private Producer<PackedCollection> hotColor;

  /** The color applied to fully-shadowed areas (where N dot L is minimum). */
  private Producer<PackedCollection> coldColor;

	/**
	 * Constructs a new BlendingShader using white as a hot color
	 * and black as a cold color.
	 */
	public BlendingShader() {
		this.hotColor = white();
		this.coldColor = black();
	}
	
	/**
	 * Constructs a new BlendingShader using the specified hot and cold colors.
	 * 
	 * @param hot  ColorProducer to use for hot color.
	 * @param cold  ColorProducer to use for cold color.
	 */
	public BlendingShader(Producer<PackedCollection> hot, Producer<PackedCollection> cold) {
		this.hotColor = hot;
		this.coldColor = cold;
	}
	
	/**
	 * @see  Shader#shade(LightingContext, DiscreteField)
	 */
	@Override
	public Producer<PackedCollection> shade(LightingContext p, DiscreteField normals) {
		// TODO  Put evaluation into producer

		Producer<PackedCollection> n;
		
		try {
			n = normals.iterator().next();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		
		Producer<PackedCollection> l = p.getLightDirection();

		CollectionProducer dp = dotProduct(direction(n), l);
		Producer<PackedCollection> k = dp.add(c(1.0));
		Producer<PackedCollection> oneMinusK = c(1.0).subtract(k);
		
		PackedCollection hc = this.hotColor.get().evaluate(p);
		PackedCollection cc = this.coldColor.get().evaluate(p);

		Producer c = multiply(value(hc), cfromScalar(k));
		c = add(c, multiply(value(cc), cfromScalar(oneMinusK)));

		return GeneratedColorProducer.fromProducer(this, c);
	}

	/** Returns "Blending Shader". */
	@Override
	public String toString() { return "Blending Shader"; }
}
