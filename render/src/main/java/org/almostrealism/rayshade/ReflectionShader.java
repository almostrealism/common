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

package org.almostrealism.rayshade;

import io.almostrealism.relation.Editable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.ShaderSet;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.ReflectedRay;
import org.almostrealism.raytrace.LightingEngineAggregator;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.texture.Texture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ReflectionShader} provides realistic reflection rendering for surfaces using
 * Schlick's approximation to the Fresnel equations.
 *
 * <p>The Fresnel effect describes how reflectivity varies with viewing angle - surfaces
 * become more reflective at grazing angles. Schlick's approximation provides an efficient
 * calculation:</p>
 * <pre>
 * R(theta) = R0 + (1 - R0) * (1 - cos(theta))^5
 * </pre>
 * <p>where R0 is the base reflectivity at normal incidence and theta is the angle between
 * the view direction and surface normal.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><b>Fresnel reflection:</b> Physically-based view-dependent reflectivity</li>
 *   <li><b>Recursive tracing:</b> Supports multiple reflection bounces (up to {@link #maxReflections})</li>
 *   <li><b>Reflection blur:</b> Optional blurred reflections for rough surfaces</li>
 *   <li><b>Environment mapping:</b> Can use textures as environment maps</li>
 *   <li><b>Editable properties:</b> Configurable through the {@link Editable} interface</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@link #setReflectivity(double)} - Base reflectivity at normal viewing angle (0.0-1.0)</li>
 *   <li>{@link #setReflectiveColor(Producer)} - Tint color applied to reflections</li>
 *   <li>{@link #setBlur(double)} - Blur factor for diffuse/rough reflections</li>
 *   <li>{@link #setEnvironmentMap(Texture)} - Fallback texture when rays miss all surfaces</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ReflectionShader mirror = new ReflectionShader();
 * mirror.setReflectivity(0.95);           // 95% reflective at normal
 * mirror.setReflectiveColor(white());     // No tint
 * mirror.setBlur(0.0);                    // Perfect mirror
 *
 * sphere.addShader(mirror);
 * }</pre>
 *
 * <h2>Recursion Control</h2>
 * <p>Recursive reflections are limited by {@link #maxReflections} (default 4) to prevent
 * infinite loops in hall-of-mirrors scenarios. When the limit is reached, the base
 * reflective color is returned without further tracing.</p>
 *
 * @see org.almostrealism.color.Shader
 * @see RefractionShader
 * @see org.almostrealism.raytrace.LightingEngineAggregator
 * @author Michael Murray
 */
public class ReflectionShader extends ShaderSet<ShaderContext> implements
		Shader<ShaderContext>, Editable, RGBFeatures, RayFeatures {

	/**
	 * Maximum number of recursive reflection bounces allowed.
	 * <p>Limits recursion to prevent infinite loops (e.g., parallel mirrors).
	 * Default is 4. Higher values produce more accurate results but are slower.</p>
	 */
	public static int maxReflections = 4;
  
  private static final String[] propNames = {"Reflectivity", "Reflective Color",
  										"Blur factor", "Environment map"};
  private static final String[] propDesc = {"The reflectivity of the surface at a direct (normal) viewing angle, usually in the range [0,1].",
										"The base color of the reflection.", "Blur factor.",
										"Texture to use as an environment map."};
  private static final Class[] propTypes = {Double.class, Producer.class, Double.class, Texture.class};
  
  private double reflectivity, blur;
  private Producer<PackedCollection> reflectiveColor;
  private Texture eMap;

	/**
	 * Constructs a new ReflectionShader object with a reflectivity of 0.0
	 * and white as a reflective color.
	 */
	public ReflectionShader() {
		this.setReflectivity(0.0);
		this.setBlur(0.0);
		this.setReflectiveColor(white());
	}
	
	/**
	 * Constructs a new ReflectionShader object with the specified reflectivity
	 * and reflective color.
	 */
	public ReflectionShader(double reflectivity, Producer<PackedCollection> reflectiveColor) {
		this.setReflectivity(reflectivity);
		this.setReflectiveColor(reflectiveColor);
		this.setBlur(0.0);
	}
	
	/** Method specified by the Shader interface. */
	@Override
	public Producer<PackedCollection> shade(ShaderContext p, DiscreteField normals) {
		if (p.getReflectionCount() > ReflectionShader.maxReflections) {
			return new DynamicCollectionProducer(RGB.shape(), args -> {
					Vector point = new Ray(p.getIntersection().get(0).get().evaluate(args), 0).getOrigin();
					PackedCollection surfaceValue = p.getSurface().getValueAt(v(point)).get().evaluate();
					PackedCollection reflColor = reflectiveColor.get().evaluate(p);
					return new RGB(
						reflColor.toDouble(0) * surfaceValue.toDouble(0),
						reflColor.toDouble(1) * surfaceValue.toDouble(1),
						reflColor.toDouble(2) * surfaceValue.toDouble(2)
					);
				});
		}
		
		p.addReflection();
		
		List<Curve<PackedCollection>> allSurfaces = new ArrayList<>();
		allSurfaces.add(p.getSurface());
		for (int i = 0; i < p.getOtherSurfaces().length; i++) { allSurfaces.add(p.getOtherSurfaces()[i]); }
		
		List<Light> allLights = new ArrayList<>();
		allLights.add(p.getLight());
		for (Light l : p.getOtherLights()) { allLights.add(l); }

		Producer<PackedCollection> r = getReflectiveColor();
		if (size() > 0) {
			r = multiply(r, ReflectionShader.super.shade(p, normals));
		}

		final Producer<PackedCollection> fr = r;

		Producer point = origin(p.getIntersection().get(0));
		Producer n = direction(normals.iterator().next());
		Producer nor = p.getIntersection().getNormalAt(point);

		Producer<Ray> transform = (Producer) transform(((AbstractSurface) p.getSurface()).getTransform(true), p.getIntersection().get(0));
		Producer loc = origin(transform);

		Producer<PackedCollection> cp = length(nor).multiply(length(n));

		Producer<PackedCollection> tc = null;

		// TODO Should surface color be factored in to reflection?
//		RGB surfaceColor = p.getSurface().getColorAt(p.getPoint());

		if (!(p.getSurface() instanceof ShadableSurface) || ((ShadableSurface) p.getSurface()).getShadeFront()) {
			Producer<Ray> reflectedRay = new ReflectedRay(loc, nor, n, blur);

			// TODO  Environment map should be a feature of the aggregator
			Evaluable<PackedCollection> aggegator = new LightingEngineAggregator(reflectedRay, Arrays.asList(p.getOtherSurfaces()), allLights, p).getAccelerated();
			Producer<PackedCollection> color = () -> aggegator;
			/*
			if (color == null || color.evaluate(args) == null) { // TODO  Avoid evaluation here
				if (eMap == null) {
					break f;
				} else {
					throw new RuntimeException("Not implemented");
					// TODO  Use AdaptProducer
					// color = eMap.getColorAt(null).evaluate(new Object[]{reflectedRay.evaluate(args).getDirection()});
				}
			}
			 */

			Producer<PackedCollection> c = scalar(1).subtract(dotProduct(minus(n), nor).divide(cp));
			CollectionProducer reflective = add(c(reflectivity),
					c(1 - reflectivity).multiply(pow(c, c(5.0))));
			Producer<PackedCollection> fcolor = color;
			color = reflective.multiply(fr).multiply(fcolor);

			if (tc == null) {
				tc = color;
			} else {
				tc = add(tc, color);
			}
		}

		if (!(p.getSurface() instanceof ShadableSurface) || ((ShadableSurface) p.getSurface()).getShadeBack()) {
			n = minus(n);

			Producer<Ray> reflectedRay = new ReflectedRay(loc, nor, n, blur);

			// TODO  Environment map should be a feature of the aggregator
			LightingEngineAggregator aggregator = new LightingEngineAggregator(reflectedRay, allSurfaces, allLights, p);
			Producer<PackedCollection> color = () -> aggregator;
			/*
			if (color == null) {
				if (eMap == null) {
					break b;
				} else {
					throw new RuntimeException("Not implemented");
					// TODO  Use AdaptProducer
					// color = eMap.getColorAt(null).evaluate(new Object[] { reflectedRay.evaluate(args).getDirection() });
				}
			}
			 */

			Producer<PackedCollection> c = c(1.0).subtract(dotProduct(minus(n), nor).divide(cp));
			CollectionProducer powResult = pow(c, c(5.0));
			Producer<PackedCollection> reflective = c(reflectivity).add(
					c(1 - reflectivity).multiply(powResult));
			Producer<PackedCollection> fcolor = color;
			color = multiply(fcolor, multiply(fr, cfromScalar(reflective)));

			if (tc == null) {
				tc = color;
			} else {
				tc = add(tc, color);
			}
		}

		Producer<PackedCollection> lightColor = p.getLight().getColorAt(point);
		Producer<PackedCollection> ftc = tc;
		return GeneratedColorProducer.fromProducer(this, multiply(ftc, lightColor));
	}
	
	/**
	 * Sets the reflectivity value used by this ReflectionShader object.
	 */
	public void setReflectivity(double reflectivity) { this.reflectivity = reflectivity; }
	
	/**
	 * Sets the blur factor used by this ReflectionShader object.
	 * 
	 * @param blur  Blur factor to use.
	 */
	public void setBlur(double blur) { this.blur = blur; }
	
	/**
	 * Sets the reflective color used by this ReflectionShader object
	 * to the color represented by the specified ColorProducer object.
	 */
	public void setReflectiveColor(Producer<PackedCollection> color) { this.reflectiveColor = color; }
	
	/**
	 * Sets the Texture object used as an environment map for this ReflectionShader object.
	 * 
	 * @param map  The Texture object to use.
	 */
	public void setEnvironmentMap(Texture map) { this.eMap = map; }
	
	/**
	 * @return  The reflectivity value used by this ReflectionShader object.
	 */
	public double getReflectivity() { return this.reflectivity; }
	
	/**
	 * @return  The blur factor used by this ReflectionShader object.
	 */
	public double getBlur() { return this.blur; }
	
	/**
	 * Returns the reflective color used by this {@link ReflectionShader}
	 * as an {@link RGB} {@link Producer}.
	 */
	public Producer<PackedCollection> getReflectiveColor() { return this.reflectiveColor; }
	
	/**
	 * @return  The Texture object used as an environment map for this ReflectionShader object.
	 */
	public Texture getEnvironmentMap() { return this.eMap; }
	
	/**
	 * Returns an array of String objects with names for each editable property of this ReflectionShader object.
	 */
	@Override
	public String[] getPropertyNames() { return ReflectionShader.propNames; }
	
	/**
	 * Returns an array of String objects with descriptions for each editable property of this ReflectionShader object.
	 */
	public String[] getPropertyDescriptions() { return ReflectionShader.propDesc; }
	
	/**
	 * Returns an array of Class objects representing the class types of each editable property of this ReflectionShader object.
	 */
	public Class[] getPropertyTypes() { return ReflectionShader.propTypes; }
	
	/**
	 * Returns the values of the properties of this ReflectionShader object as an Object array.
	 */
	public Object[] getPropertyValues() {
		return new Object[] {Double.valueOf(this.reflectivity), this.reflectiveColor, Double.valueOf(this.blur), this.eMap};
	}
	
	/**
	 * Sets the value of the property of this ReflectionShader object at the specified index to the specified value.
	 * 
	 * @throws IllegalArgumentException  If the object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the index specified does not correspond to an editable property of this 
	 *                                    ReflectionShader object.
	 */
	public void setPropertyValue(Object value, int index) {
		if (index == 0) {
			if (value instanceof Double)
				this.setReflectivity(((Double)value).doubleValue());
			else
				throw new IllegalArgumentException("Illegal argument: " + value);
		} else if (index == 1) {
			if (value instanceof Producer)
				this.setReflectiveColor((Producer) value);
			else
				throw new IllegalArgumentException("Illegal argument: " + value);
		} else if (index == 2) {
			if (value instanceof Double)
				this.setBlur(((Double)value).doubleValue());
			else
				throw new IllegalArgumentException("Illegal argument: " + value);
		} else if (index == 3) {
			if (value instanceof Texture)
				this.setEnvironmentMap((Texture)value);
			else
				throw new IllegalArgumentException("Illegal argument: " + value);
		} else {
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
		}
	}
	
	/**
	 * Sets the values of editable properties of this ReflectionShader object to those specified.
	 * 
	 * @throws IllegalArgumentException  If one of the objects specified is not of the correct type.
     *                                       (Note: none of the values after the erroneous value will be set)
	 * @throws IndexOutOfBoundsException  If the length of the specified array is longer than permitted.
	 */
	public void setPropertyValues(Object[] values) {
		for (int i = 0; i < values.length; i++)
			this.setPropertyValue(values[i], i);
	}
	
	/**
	 * @return  {reflective color}.
	 */
	public Producer[] getInputPropertyValues() { return new Producer[] {this.reflectiveColor}; }
	
	/**
	 * Sets the values of properties of this HighlightShader object to those specified.
	 * 
	 * @throws IllegalArgumentException  If the Producer object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the lindex != 0;
	 */
	public void setInputPropertyValue(int index, Producer p) {
		if (index == 0)
			this.setPropertyValue(p, 1);
		else
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
	}
	
	/**
	 * Returns "Reflection Shader".
	 */
	public String toString() { return "Reflection Shader"; }
}
