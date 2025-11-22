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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;

/**
 * Represents a point light source that emanates light from a specific location in 3D space.
 *
 * <p>A {@code PointLight} simulates an omnidirectional light source (like a light bulb)
 * where light radiates equally in all directions from a single point. The light intensity
 * can decrease with distance using configurable attenuation coefficients.</p>
 *
 * <h2>Distance Attenuation</h2>
 * <p>Light intensity decreases with distance according to a quadratic function:</p>
 * <pre>
 * attenuated_color = color / (da * d^2 + db * d + dc)
 * </pre>
 * <p>Where:</p>
 * <ul>
 *   <li>{@code da} - quadratic coefficient (inverse-square falloff)</li>
 *   <li>{@code db} - linear coefficient</li>
 *   <li>{@code dc} - constant coefficient (no falloff)</li>
 *   <li>{@code d} - distance from light to surface point</li>
 * </ul>
 *
 * <h2>Common Attenuation Configurations</h2>
 * <ul>
 *   <li>{@code (0, 0, 1)} - No attenuation (constant brightness)</li>
 *   <li>{@code (1, 0, 0)} - Physically realistic inverse-square falloff</li>
 *   <li>{@code (0, 1, 0)} - Linear falloff</li>
 *   <li>{@code (1, 0.1, 0.01)} - Combined falloff for artistic control</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a white point light at position (5, 5, 5)
 * PointLight light = new PointLight(new Vector(5, 5, 5), 1.0, new RGB(1.0, 1.0, 1.0));
 *
 * // Set quadratic attenuation for realistic falloff
 * light.setAttenuationCoefficients(1.0, 0.0, 0.0);
 *
 * // Get the attenuated color at a specific point
 * Producer<RGB> colorAtSurface = light.getColorAt(surfacePointProducer);
 * }</pre>
 *
 * @see Light
 * @see AmbientLight
 * @see DirectionalAmbientLight
 * @author Michael Murray
 */
public class PointLight implements Light, Positioned, RayFeatures, RGBFeatures {
	private double intensity;
	private RGB color;

	private Vector location;

	private double da, db, dc;

	/** Constructs a PointLight object with the default intensity and color at the origin. */
	public PointLight() {
		this.setIntensity(1.0);
		this.setColor(new RGB(1.0, 1.0, 1.0));
		
		this.setLocation(new Vector(0.0, 0.0, 0.0));
		
		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}
	
	/** Constructs a {@link PointLight} with the specified location and default intensity and color. */
	public PointLight(Vector location) {
		this.setIntensity(1.0);
		this.setColor(new RGB(1.0, 1.0, 1.0));

		this.setLocation(location);

		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}
	
	/** Constructs a PointLight object with the specified intensity and default color at the origin. */
	public PointLight(double intensity) {
		this.setIntensity(intensity);
		this.setColor(new RGB(1.0, 1.0, 1.0));
		
		this.setLocation(new Vector(0.0, 0.0, 0.0));
		
		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}
	
	/** Constructs a PointLight object with the specified intensity and color at the origin. */
	public PointLight(double intensity, RGB color) {
		this.setIntensity(intensity);
		this.setColor(color);
		
		this.setLocation(new Vector(0.0, 0.0, 0.0));
		
		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}
	
	/** Constructs a PointLight object with the specified location, intensity, and color. */
	public PointLight(Vector location, double intensity, RGB color) {
		this.setIntensity(intensity);
		this.setColor(color);
		
		this.setLocation(location);
		
		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}
	
	/**
	 * Sets the intensity of this PointLight object to the specified double value.
	 */
	@Override
	public void setIntensity(double intensity) { this.intensity = intensity; }
	
	/**
	 * Sets the color of this PointLight object to the color represented by the specified RGB object.
	 */
	@Override
	public void setColor(RGB color) { this.color = color; }
	
	/**
	 * Sets the location of this PointLight object to the location represented by the specified Vector object.
	 */
	public void setLocation(Vector location) {
		this.location = location;
	}
	
	/**
	 * Sets the coefficients a, b, and c for the quadratic function used for distance attenuation
	 * of the light represented by this PointLight object to the specified double values.
	 */
	public void setAttenuationCoefficients(double a, double b, double c) {
		this.da = a;
		this.db = b;
		this.dc = c;
	}
	
	/**
	 * Sets the coefficients a, b, and c for the quadratic function used for distance attenuation
	 * of the light represented by this PointLight object to the specified double values.
	 */
	public void setAttenuationCoefficients(double a[]) {
		this.da = a[0];
		this.db = a[1];
		this.dc = a[2];
	}
	
	/**
	 * Sets the coefficients a, b, and c for the quadratic function used for distance attenuation
	 * of the light represented by this PointLight object to the specified double values.
	 */
	public void setAttenuationCoefficients(Vector a) {
		this.da = a.getX();
		this.db = a.getY();
		this.dc = a.getZ();
	}
	
	/** Returns the intensity of this PointLight object as a double value. */
	@Override
	public double getIntensity() { return this.intensity; }
	
	/** Returns the color of this PointLight object as an RGB object. */
	@Override
	public RGB getColor() { return this.color; }
	
	/**
	 * Returns the color of the light represented by this PointLight object at the
	 * specified point as an RGB object.
	 */
	@Override
	public Producer<RGB> getColorAt(Producer<Vector> point) {
		Producer<Scalar> d = lengthSq(add(point, minus(v(location))));

		RGB color = getColor().multiply(getIntensity());
		return GeneratedColorProducer.fromProducer(this, attenuation(da, db, dc, v(color), d));
	}

	/** Returns the location of this {@link PointLight} as a {@link Vector}. */
	public Vector getLocation() { return this.location; }
	
	/**
	 * Returns the coefficients a, b, and c for the quadratic function used for distance
	 * attenuation of the light represented by this PointLight object as an array of
	 * double values.
	 */
	public double[] getAttenuationCoefficients() {
		double d[] = {this.da, this.db, this.dc};
		
		return d;
	}
	
	/** Returns "Point Light". */
	@Override
	public String toString() { return "Point Light"; }

	/**
	 * Performs the lighting calculations for the specified surface at the specified point of
	 * intersection on that surface using the lighting data from this {@link PointLight}
	 * object and returns an {@link RGB} object that represents the color of the point.
	 * A list of all other surfaces in the scene must be specified for reflection/shadowing.
	 * This list does not include the specified surface for which the lighting calculations
	 * are to be done. If the premultiplyIntensity option is set to true the color of the
	 * point light will be adjusted by the intensity of the light and the intensity will
	 * then be set to 1.0. If the premultiplyIntensity option is set to false, the color will
	 * be left unattenuated and the shaders will be responsible for adjusting the color
	 * based on intensity.
	 */
	// TODO  This should be a method of the Light interface
	public Producer<RGB> forShadable(Shadable surface, Producer<Ray> intersection, ShaderContext context) {
		CollectionProducer<Vector> point = origin(intersection);
		Producer<Vector> direction = add(point, minus(v(getLocation())));
		direction = minus(normalize(direction));
		context.setLightDirection(direction);
		return surface.shade(context);
	}

	@Override
	public void setPosition(float x, float y, float z) {
		this.setLocation(new Vector(x, y, z));
	}

	@Override
	public float[] getPosition() {
		return new float[] { (float) location.getX(), (float) location.getY(), (float) location.getZ() };
	}
}
