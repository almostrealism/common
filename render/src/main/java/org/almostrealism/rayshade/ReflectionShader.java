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

import org.almostrealism.raytrace.LightingEngineAggregator;
import io.almostrealism.relation.Editable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.ShaderSet;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.ReflectedRay;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.texture.Texture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link ReflectionShader} provides a shading method for reflective surfaces.
 * The ReflectionShader class uses a shading algorithm based on Shlick's
 * approximation to the Fresnel equations.
 * 
 * @author  Michael Murray
 */
public class ReflectionShader extends ShaderSet<ShaderContext> implements
		Shader<ShaderContext>, Editable, RGBFeatures, RayFeatures {
  public static int maxReflections = 4;
  
  private static final String propNames[] = {"Reflectivity", "Reflective Color",
  										"Blur factor", "Environment map"};
  private static final String propDesc[] = {"The reflectivity of the surface at a direct (normal) viewing angle, usually in the range [0,1].",
										"The base color of the reflection.", "Blur factor.",
										"Texture to use as an environment map."};
  private static final Class propTypes[] = {Double.class, Producer.class, Double.class, Texture.class};
  
  private double reflectivity, blur;
  private Producer<RGB> reflectiveColor;
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
	public ReflectionShader(double reflectivity, Producer<RGB> reflectiveColor) {
		this.setReflectivity(reflectivity);
		this.setReflectiveColor(reflectiveColor);
		this.setBlur(0.0);
	}
	
	/** Method specified by the Shader interface. */
	@Override
	public Producer<RGB> shade(ShaderContext p, DiscreteField normals) {
		if (p.getReflectionCount() > ReflectionShader.maxReflections) {
			return new DynamicCollectionProducer<>(RGB.shape(), args -> {
					Vector point = p.getIntersection().get(0).get().evaluate(args).getOrigin();
					return reflectiveColor.get().evaluate(p)
							.multiply(p.getSurface().getValueAt(v(point)).get().evaluate());
				});
		}
		
		p.addReflection();
		
		List<Curve<RGB>> allSurfaces = new ArrayList<>();
		allSurfaces.add(p.getSurface());
		for (int i = 0; i < p.getOtherSurfaces().length; i++) { allSurfaces.add(p.getOtherSurfaces()[i]); }
		
		List<Light> allLights = new ArrayList<>();
		allLights.add(p.getLight());
		for (Light l : p.getOtherLights()) { allLights.add(l); }

		Producer<RGB> r = getReflectiveColor();
		if (size() > 0) {
			r = multiply(r, ReflectionShader.super.shade(p, normals));
		}

		final Producer<RGB> fr = r;

		CollectionProducer<Vector> point = origin(p.getIntersection().get(0));
		Producer<Vector> n = direction(normals.iterator().next());
		Producer<Vector> nor = p.getIntersection().getNormalAt(point);

		Producer<Ray> transform = transform(((AbstractSurface) p.getSurface()).getTransform(true), p.getIntersection().get(0));
		CollectionProducer<Vector> loc = origin(transform);

		Producer<Scalar> cp = length(nor).multiply(length(n));

		Producer<RGB> tc = null;

		// TODO Should surface color be factored in to reflection?
//		RGB surfaceColor = p.getSurface().getColorAt(p.getPoint());

		f: if (p.getSurface() instanceof ShadableSurface == false || ((ShadableSurface) p.getSurface()).getShadeFront()) {
			Producer<Ray> reflectedRay = new ReflectedRay(loc, nor, n, blur);

			// TODO  Environment map should be a feature of the aggregator
			Evaluable<RGB> aggegator = new LightingEngineAggregator(reflectedRay, Arrays.asList(p.getOtherSurfaces()), allLights, p).getAccelerated();
			Producer<RGB> color = () -> aggegator;
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

			Producer<Scalar> c = scalar(1).subtract(dotProduct(minus(n), nor).divide(cp));
			CollectionProducer<?> reflective = add(c(reflectivity),
					c(1 - reflectivity).multiply(pow(c, c(5.0))));
			Producer<RGB> fcolor = color;
			color = reflective.multiply(fr).multiply(fcolor);

			if (tc == null) {
				tc = color;
			} else {
				tc = add(tc, color);
			}
		}

		b: if (p.getSurface() instanceof ShadableSurface == false || ((ShadableSurface) p.getSurface()).getShadeBack()) {
			n = minus(n);

			Producer<Ray> reflectedRay = new ReflectedRay(loc, nor, n, blur);

			// TODO  Environment map should be a feature of the aggregator
			LightingEngineAggregator aggregator = new LightingEngineAggregator(reflectedRay, allSurfaces, allLights, p);;
			Producer<RGB> color = () -> aggregator;
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

			Producer<Scalar> c = scalar(1).subtract(dotProduct(minus(n), nor).divide(cp));
			Producer<Scalar> reflective = scalar(reflectivity).add(
					scalar(1 - reflectivity).multiply(pow(c, scalar(5.0))));
			Producer<RGB> fcolor = color;
			color = multiply(fcolor, multiply(fr, cfromScalar(reflective)));

			if (tc == null) {
				tc = color;
			} else {
				tc = add(tc, color);
			}
		}

		Producer<RGB> lightColor = p.getLight().getColorAt(point);
		Producer<RGB> ftc = tc;
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
	public void setReflectiveColor(Producer<RGB> color) { this.reflectiveColor = color; }
	
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
	public Producer<RGB> getReflectiveColor() { return this.reflectiveColor; }
	
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
	public void setPropertyValues(Object values[]) {
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
