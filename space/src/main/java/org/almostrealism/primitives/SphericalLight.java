/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.primitives;
import org.almostrealism.collect.PackedCollection;

import org.almostrealism.color.PointLight;
import org.almostrealism.color.SurfaceLight;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;

import org.almostrealism.color.RGB;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

/**
 * A SphericalLight object provides PointLight samples that are randomly distributed
 * across the surface of a sphere.
 * 
 * @author  Michael Murray
 */
public class SphericalLight extends Sphere implements SurfaceLight {
  private double intensity, atta, attb, attc;
  
  private int samples;

	/** Constructs a new {@link SphericalLight}. */
	public SphericalLight() {
		super(new Vector(0.0, 0.0, 0.0), 0.0);
		
		this.intensity = 1.0;
		this.samples = 1;
		
		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}
	
	/**
	 * Constructs a new {@link SphericalLight}.
	 * 
	 * @param location  Location for sphere.
	 * @param radius  Radius of sphere.
	 */
	public SphericalLight(Vector location, double radius) {
		super(location, radius);
		
		this.intensity = 1.0;
		this.samples = 1;
		
		this.setAttenuationCoefficients(0.0, 0.0, 1.0);
	}

	/**
	 * Delegates to {@link #getValueAt(Producer)}.
	 */
	@Override
	public Producer<PackedCollection> getColorAt(Producer<PackedCollection> point) { return getValueAt(point); }

	/**
	 * Sets the number of samples to use for this SphericalLight object.
	 * 
	 * @param samples
	 */
	public void setSampleCount(int samples) { this.samples = samples; }
	
	/**
	 * @return  The number of samples to use for this SphericalLight object.
	 */
	public int getSampleCount() { return this.samples; }
	
	/**
	 * @see SurfaceLight#getSamples(int)
	 */
	@Override
	public Light[] getSamples(int total) {
		PointLight l[] = new PointLight[total];
		
		double in = this.intensity / total;
		
		for (int i = 0; i < total; i++) {
			double r = super.getSize();
			double u = Math.random() * 2.0 * Math.PI;
			double v = Math.random() * 2.0 * Math.PI;
			
			double x = r * Math.sin(u) * Math.cos(v);
			double y = r * Math.sin(u) * Math.sin(v);
			double z = r * Math.cos(u);
			
			Supplier<Evaluable<? extends Vector>> p = getTransform(true).transform((Producer) vector(x, y, z),
									TransformMatrix.TRANSFORM_AS_LOCATION);

			// TODO  This should pass along the ColorProucer directly rather than evaluating it
			PackedCollection colorResult = getColorAt(() -> (Evaluable<PackedCollection>) (Evaluable<?>) p.get()).get().evaluate();
			RGB color = colorResult instanceof RGB ? (RGB) colorResult : new RGB(colorResult.toDouble(0), colorResult.toDouble(1), colorResult.toDouble(2));
			l[i] = new PointLight(p.get().evaluate(), in, color);
			l[i].setAttenuationCoefficients(this.atta, this.attb, this.attc);
		}
		
		return l;
	}
	
	/** @see SurfaceLight#getSamples() */
	@Override
	public Light[] getSamples() { return this.getSamples(this.samples); }

	/** @see org.almostrealism.color.Light#setIntensity(double) */
	@Override
	public void setIntensity(double intensity) { this.intensity = intensity; }

	/** @see org.almostrealism.color.Light#getIntensity() */
	@Override
	public double getIntensity() { return this.intensity; }
	
	/** Sets the attenuation coefficients to be used when light samples are created. */
	public void setAttenuationCoefficients(double a, double b, double c) {
		this.atta = a;
		this.attb = b;
		this.attc = c;
	}
	
	/** @return  An array containing the attenuation coefficients used when light samples are created. */
	public double[] getAttenuationCoefficients() { return new double[] { this.atta, this.attb, this.attc }; }
	
	/** @see org.almostrealism.algebra.ParticleGroup#getParticleVertices() */
	public double[][] getParticleVertices() { return new double[0][0]; }
	
	/** @return  "Spherical Light". */
	@Override
	public String toString() { return "Spherical Light"; }
}
