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

import io.almostrealism.relation.Editable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import io.almostrealism.relation.Producer;

/**
 * Provides specular highlight shading using the Phong reflection model.
 *
 * <p>The {@code HighlightShader} computes specular highlights that appear on shiny
 * surfaces when the viewing angle approaches the angle of perfect reflection. This
 * creates the characteristic "hot spots" seen on glossy materials like plastic,
 * polished metal, or wet surfaces.</p>
 *
 * <h2>Phong Reflection Model</h2>
 * <p>The highlight intensity is computed as:</p>
 * <pre>
 * intensity = (H dot N) ^ exponent
 * </pre>
 * <p>Where:</p>
 * <ul>
 *   <li><b>H</b>: The half-vector between light direction and view direction</li>
 *   <li><b>N</b>: The surface normal</li>
 *   <li><b>exponent</b>: Controls highlight sharpness (higher = sharper, smaller highlight)</li>
 * </ul>
 *
 * <h2>Exponent Values</h2>
 * <ul>
 *   <li>1-10: Very broad, diffuse highlights (rough surfaces)</li>
 *   <li>10-50: Medium highlights (semi-glossy plastics)</li>
 *   <li>50-200: Sharp highlights (polished surfaces)</li>
 *   <li>200+: Very sharp highlights (mirrors, chrome)</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a sharp white highlight for a glossy surface
 * HighlightShader specular = new HighlightShader(white(), 64.0);
 *
 * // Create a colored highlight for metallic materials
 * HighlightShader goldHighlight = new HighlightShader(rgb(1.0, 0.8, 0.3), 100.0);
 *
 * // Combine with diffuse for complete Phong shading
 * ShaderSet<ShaderContext> phong = new ShaderSet<>();
 * phong.add(new DiffuseShader());
 * phong.add(specular);
 * }</pre>
 *
 * <p>This shader also extends {@link ShaderSet}, allowing additional sub-shaders
 * to modulate the highlight color.</p>
 *
 * @see DiffuseShader
 * @see ShaderSet
 * @see Shader
 * @author Michael Murray
 */
public class HighlightShader extends ShaderSet<ShaderContext> implements
		Shader<ShaderContext>, Editable, RGBFeatures, RayFeatures {
  private static final String propNames[] = { "Highlight Color", "Highlight Exponent" };
  private static final String propDesc[] = { "The base color for the highlight", "The exponent used to dampen the highlight (phong exponent)" };
  private static final Class propTypes[] = { Producer.class, Double.class };
  
  private Producer<RGB> highlightColor;
  private double highlightExponent;

	/**
	 * Constructs a new HighlightShader object using white as a highlight color
	 * and 1.0 as a highlight exponent.
	 */
	public HighlightShader() {
		this.setHighlightColor(white());
		this.setHighlightExponent(1.0);
	}
	
	/**
	 * Constructs a new HighlightShader object using the specified highlight color
	 * and highlight exponent.
	 */
	public HighlightShader(Producer<RGB> color, double exponent) {
		this.setHighlightColor(color);
		this.setHighlightExponent(exponent);
	}
	
	/** Method specified by the Shader interface. */
	@Override
	public Producer<RGB> shade(ShaderContext p, DiscreteField normals) {
		Vector point;
		
		try {
			point = p.getIntersection().get(0).get().evaluate().getOrigin();
		} catch (Exception ex) {
			ex.printStackTrace();
			return null;
		}
		
		RGB lightColor = p.getLight().getColorAt(v(p.getIntersection().getNormalAt(v(point)).get().evaluate())).get().evaluate();
		
		Producer<Vector> n;
		
		try {
			n = direction(normals.iterator().next());
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		
		n = multiply(n, length(n).pow(-1.0));
		CollectionProducer<Vector> h = vector(add(p.getIntersection().getNormalAt(v(point)), p.getLightDirection()));
		h = multiply(h, length(h).pow(-1.0));

		Producer<RGB> hc = v(this.getHighlightColor().get().evaluate(p));
		if (super.size() > 0) hc = multiply(hc, super.shade(p, normals));

		CollectionProducer<PackedCollection<?>> cFront = dotProduct(h, n);
		CollectionProducer<PackedCollection<?>> cBack = dotProduct(h, minus(n));

		Producer<RGB> fhc = hc;

		return GeneratedColorProducer.fromProducer(this, new DynamicProducerForMemoryData<>(args -> {
			Producer<RGB> color = null;

			f: if (p.getSurface() instanceof ShadableSurface == false || ((ShadableSurface) p.getSurface()).getShadeFront()) {
				double c = cFront.get().evaluate(args).toDouble();
				if (c < 0) break f;
				c = Math.pow(c, this.getHighlightExponent());

				Producer<RGB> pr = multiply(v(lightColor), v(fhc.get().evaluate(args))).multiply(v(new RGB(c, c, c)));
				if (color == null) {
					color = pr;
				} else {
					color = add(color, pr);
				}
			}

			f: if (p.getSurface() instanceof ShadableSurface == false || ((ShadableSurface) p.getSurface()).getShadeBack()) {
				double c = cBack.get().evaluate(args).toDouble();
				if (c < 0) break f;
				c = Math.pow(c, this.getHighlightExponent());

				Producer<RGB> pr = multiply(v(lightColor), v(fhc.get().evaluate(args))).multiply(v(new RGB(c, c, c)));
				if (color == null) {
					color = pr;
				} else {
					color = add(color, pr);
				}
			}

			return color.get().evaluate();
		}));
	}
	
	/**
	 * Sets the color used for the highlight shaded by this HighlightShader object
	 * to the color represented by the specifed RGB object.
	 */
	public void setHighlightColor(Producer<RGB> color) { this.highlightColor = color; }
	
	/**
	 * Sets the highlight exponent (phong exponent) used by this {@link HighlightShader}.
	 */
	public void setHighlightExponent(double exp) { this.highlightExponent = exp; }
	
	/**
	 * Returns the color used for the highlight shaded by this {@link HighlightShader}
	 * as a {@link Producer}.
	 */
	public Producer<RGB> getHighlightColor() { return this.highlightColor; }
	
	/**
	 * Returns the highlight exponent (phong exponent) used by this HighlightShader object.
	 */
	public double getHighlightExponent() { return this.highlightExponent; }
	
	/**
	 * Returns an array of String objects with names for each editable property of this HighlightShader object.
	 */
	public String[] getPropertyNames() { return HighlightShader.propNames; }
	
	/**
	 * Returns an array of String objects with descriptions for each editable property of this HighlightShader object.
	 */
	public String[] getPropertyDescriptions() { return HighlightShader.propDesc; }
	
	/**
	 * Returns an array of Class objects representing the class types of each editable property of this HighlightShader object.
	 */
	public Class[] getPropertyTypes() { return HighlightShader.propTypes; }
	
	/**
	 * Returns the values of the properties of this HighlightShader object as an Object array.
	 */
	public Object[] getPropertyValues() {
		return new Object[] {this.highlightColor, Double.valueOf(this.highlightExponent)};
	}
	
	/**
	 * Sets the value of the property of this HighlightShader object at the specified index to the specified value.
	 * 
	 * @throws IllegalArgumentException  If the object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the index specified does not correspond to an editable property
	 *                                    of this HighlightShader object.
	 */
	public void setPropertyValue(Object value, int index) {
		if (index == 0) {
			if (value instanceof Producer)
				this.setHighlightColor((Producer<RGB>) value);
			else
				throw new IllegalArgumentException("Illegal argument: " + value.toString());
		} else if (index == 1) {
			if (value instanceof Double)
				this.setHighlightExponent(((Double)value).doubleValue());
			else
				throw new IllegalArgumentException("Illegal argument: " + value.toString());
		} else {
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
		}
	}
	
	/**
	 * Sets the values of properties of this HighlightShader object to those specified.
	 * 
	 * @throws IllegalArgumentException  If one of the objects specified is not of the correct type.
	 *                                   (Note: none of the values after the erroneous value will be set)
	 * @throws IndexOutOfBoundsException  If the length of the specified array is longer than permitted.
	 */
	public void setPropertyValues(Object values[]) {
		for (int i = 0; i < values.length; i++) {
			this.setPropertyValue(values[i], i);
		}
	}
	
	/**
	 * @return  {highlight color}.
	 */
	public Producer[] getInputPropertyValues() { return new Producer[] { this.highlightColor }; }
	
	/**
	 * Sets the values of properties of this HighlightShader object to those specified.
	 * 
	 * @throws IllegalArgumentException  If the Producer object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the lindex != 0;
	 */
	@Override
	public void setInputPropertyValue(int index, Producer p) {
		if (index == 0)
			this.setPropertyValue(p, 0);
		else
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
	}
	
	/**
	 * Returns "Highlight Shader".
	 */
	@Override
	public String toString() { return "Highlight Shader"; }
}
