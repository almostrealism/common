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

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.Curve;
import io.almostrealism.relation.Producer;

/**
 * Represents an ambient light that illuminates all surfaces uniformly regardless of position.
 *
 * <p>An {@code AmbientLight} provides constant, non-directional illumination to simulate
 * the effect of indirect light bouncing around an environment. Unlike point lights or
 * directional lights, ambient light has no specific direction and illuminates all surfaces
 * equally, regardless of their position or orientation.</p>
 *
 * <h2>Purpose</h2>
 * <p>Ambient light serves several purposes in rendering:</p>
 * <ul>
 *   <li>Prevents completely black shadows (global illumination approximation)</li>
 *   <li>Provides base illumination level for scenes</li>
 *   <li>Simulates scattered environmental light</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a dim ambient light
 * AmbientLight ambient = new AmbientLight(0.2, new RGB(0.8, 0.85, 1.0));
 *
 * // Use in lighting calculations
 * Producer<RGB> surfaceColor = ambient.lightingCalculation(surface, pointProducer);
 * }</pre>
 *
 * <h2>Typical Values</h2>
 * <ul>
 *   <li>Outdoor scenes: intensity 0.1-0.3, bluish tint (sky contribution)</li>
 *   <li>Indoor scenes: intensity 0.2-0.4, warm tint (bounced light)</li>
 *   <li>Stylized rendering: higher values for flat shading effects</li>
 * </ul>
 *
 * @see Light
 * @see PointLight
 * @see DirectionalAmbientLight
 * @author Michael Murray
 */
public class AmbientLight implements Light, RGBFeatures {
	private double intensity;
	private RGB color;

	private Producer<RGB> colorProducer = GeneratedColorProducer.fromProducer(this, multiply(() -> args -> color, c(intensity)));

	/**
	 * Constructs an AmbientLight object with the default intensity and color.
	 */
	public AmbientLight() {
		this.setIntensity(1.0);
		this.setColor(new RGB(1.0, 1.0, 1.0));
	}
	
	/**
	 * Constructs an AmbientLight object with the specified intensity and default color.
	 */
	public AmbientLight(double intensity) {
		this.setIntensity(intensity);
		this.setColor(new RGB(1.0, 1.0, 1.0));
	}
	
	/**
	 * Constructs an AmbientLight object with the specified intensity and color.
	 */
	public AmbientLight(double intensity, RGB color) {
		this.setIntensity(intensity);
		this.setColor(color);
	}
	
	/**
	 * Sets the intensity of this AmbientLight object.
	 */
	@Override
	public void setIntensity(double intensity) { this.intensity = intensity; }
	
	/**
	 * Sets the color of this AmbientLight object to the color represented by the specified RGB object.
	 */
	@Override
	public void setColor(RGB color) { this.color = color; }
	
	/** Returns the intensity of this AmbientLight object as a double value. */
	@Override
	public double getIntensity() { return this.intensity; }
	
	/** Returns the color of this AmbientLight object as an RGB object. */
	@Override
	public RGB getColor() { return this.color; }

	/** Returns the {@link RGB} {@link Producer} for this {@link AmbientLight}. */
	@Override
	public Producer<RGB> getColorAt(Producer<Vector> point) {
		return GeneratedColorProducer.fromProducer(this,
				multiply(v(color), rgb(intensity)));
	}

	/**
	 * Performs the lighting calculations for the specified surface at the specified point of
	 * intersection on that surface using the lighting data from the specified AmbientLight
	 * object and returns an RGB object that represents the color of the point. A list of all
	 * other surfaces in the scene must be specified for reflection/shadowing. This list does
	 * not include the specified surface for which the lighting calculations are to be done.
	 */
	public Producer<RGB> lightingCalculation(Curve<RGB> surface, Producer<Vector> point) {
		Producer<RGB> color = multiply(v(getColor()), c(getIntensity()));
		return multiply(color, surface.getValueAt(point));
	}
	
	/** Returns "Ambient Light". */
	@Override
	public String toString() { return "Ambient Light"; }
}
