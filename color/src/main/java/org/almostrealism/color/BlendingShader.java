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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Editable;
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
public class BlendingShader implements Shader<LightingContext>, Editable, RGBFeatures, RayFeatures {
  private static final String names[] = { "Hot color", "Cold color" };
  private static final String desc[] = { "Color for hot (lit) area.", "Color for cold (dim) area." };
  private static final Class types[] = { Producer.class, Producer.class };
  
  private Producer<RGB> hotColor, coldColor;

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
	public BlendingShader(Producer<RGB> hot, Producer<RGB> cold) {
		this.hotColor = hot;
		this.coldColor = cold;
	}
	
	/**
	 * @see  Shader#shade(LightingContext, DiscreteField)
	 */
	public Producer<RGB> shade(LightingContext p, DiscreteField normals) {
		// TODO  Put evaluation into producer

		Producer<Ray> n;
		
		try {
			n = normals.iterator().next();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		
		Producer<Vector> l = p.getLightDirection();

		CollectionProducer<PackedCollection<?>> dp = dotProduct(direction(n), l);
		Producer<PackedCollection<?>> k = dp.add(c(1.0));
		Producer<PackedCollection<?>> oneMinusK = c(1.0).subtract(k);
		
		RGB hc = this.hotColor.get().evaluate(p);
		RGB cc = this.coldColor.get().evaluate(p);
		
		Producer<RGB> c = multiply(v(hc), cfromScalar(k));
		c = add(c, multiply(v(cc), cfromScalar(oneMinusK)));

		return GeneratedColorProducer.fromProducer(this, c);
	}

	/**
	 * @see Editable#getPropertyNames()
	 */
	public String[] getPropertyNames() { return BlendingShader.names; }

	/**
	 * @see Editable#getPropertyDescriptions()
	 */
	public String[] getPropertyDescriptions() { return BlendingShader.desc; }

	/**
	 * @see Editable#getPropertyTypes()
	 */
	public Class[] getPropertyTypes() { return BlendingShader.types; }

	/**
	 * @see Editable#getPropertyValues()
	 */
	public Object[] getPropertyValues() { return this.getInputPropertyValues(); }

	/**
	 * @see Editable#setPropertyValue(java.lang.Object, int)
	 */
	public void setPropertyValue(Object o, int index) { this.setInputPropertyValue(index, (Producer) o); }

	/**
	 * @see Editable#setPropertyValues(java.lang.Object[])
	 */
	public void setPropertyValues(Object values[]) {
		for (int i = 0; i < values.length; i++) this.setPropertyValue(values[i], i);
	}

	/** @see Editable#getInputPropertyValues() */
	public Producer[] getInputPropertyValues() { return new Producer[] {this.hotColor, this.coldColor}; }

	/**
	 * @see Editable#setInputPropertyValue(int, Producer)
	 * @throws IndexOutOfBoundsException  If the property index is out of bounds.
	 */
	public void setInputPropertyValue(int index, Producer p) {
		if (index == 0)
			this.hotColor = (Producer) p;
		else if (index == 1)
			this.coldColor = (Producer) p;
		else
			throw new IndexOutOfBoundsException("Property index out of bounds: " + index);
	}
	
	/** @return  "Blending Shader". */
	public String toString() { return "Blending Shader"; }
}
