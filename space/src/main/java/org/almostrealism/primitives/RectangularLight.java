/*
 * Copyright 2018 Michael Murray
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
import org.almostrealism.color.Shader;
import io.almostrealism.relation.Producer;
import org.almostrealism.space.Plane;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

/**
 * A {@link RectangularLight} provides {@link PointLight} samples that are randomly distributed
 * across a {@link Plane} surface. The location of the {@link Plane} is used for the upper left
 * corner of the area to sample, not the center of the area to sample.
 * 
 * @author  Michael Murray
 */
public class RectangularLight extends Plane implements SurfaceLight {
  private double width, height;
  private double intensity;
  
  private int samples;
	
  	/**
  	 * Constructs a new RectangularLight object.
  	 */
	public RectangularLight() {
		this.samples = 1;
		
		super.setShaders(new Shader[0]);
	}
	
	/**
	 * Constructs a new RectangularLight object.
	 * 
	 * @param width  Width of rectangle.
	 * @param height  Hieght of rectangle.
	 */
	public RectangularLight(double width, double height) {
		this.width = width;
		this.height = height;
		
		this.samples = 1;
		
		super.setShaders(new Shader[0]);
	}

	/**
	 * Delegates to {@link #getValueAt(Producer)}.
	 */
	@Override
	public Producer<PackedCollection> getColorAt(Producer<PackedCollection> point) { return getValueAt(point); }

	/**
	 * Sets the number of samples to use for this RectangularLight object.
	 * 
	 * @param samples
	 */
	public void setSampleCount(int samples) { this.samples = samples; }
	
	/**
	 * @return  The number of samples to use for this RectangularLight object.
	 */
	public int getSampleCount() { return this.samples; }
	
	/**
	 * @see SurfaceLight#getSamples(int)
	 */
	public Light[] getSamples(int total) {
		Light l[] = new Light[total];
		
		double in = this.intensity / total;
		
		for (int i = 0; i < total; i++) {
			double x = 0.0, y = 0.0, z = 0.0;
			
			if (super.getType() == Plane.XY) {
				x = Math.random() * this.width;
				y = Math.random() * this.height;
				z = 0.0;
			} else if (super.getType() == Plane.XZ) {
				x = Math.random() * this.width;
				y = 0.0;
				z = Math.random() * this.height;
			} else if (super.getType() == Plane.YZ) {
				x = 0.0;
				y = Math.random() * this.width;
				z = Math.random() * this.height;
			}

			Supplier<Evaluable<? extends Vector>> p = getTransform(true).transform((Producer) vector(x, y, z),
									TransformMatrix.TRANSFORM_AS_LOCATION);

			// TODO This should hand off the color producer directly
			PackedCollection colorResult = getColorAt(vector()).get().evaluate();
			RGB color = colorResult instanceof RGB ? (RGB) colorResult : new RGB(colorResult.toDouble(0), colorResult.toDouble(1), colorResult.toDouble(2));
			l[i] = new PointLight(p.get().evaluate(), in, color);
		}
		
		return l;
	}

	/** @see SurfaceLight#getSamples() */
	@Override
	public Light[] getSamples() { return this.getSamples(this.samples); }
	
	/** Sets the width of the rectangular area of this {@link RectangularLight}. */
	public void setWidth(double width) { this.width = width; }
	
	/** Sets the height of the rectangular area of this {@link RectangularLight}. */
	public void setHeight(double height) { this.height = height; }
	
	/** @return  The width of the rectangular area of this {@link RectangularLight}. */
	public double getWidth() { return this.width; }
	
	/** @return  The width of the rectangular area of this {@link RectangularLight}. */
	public double getHeight() { return this.height; }
	
	/** @see org.almostrealism.color.Light#setIntensity(double) */
	@Override
	public void setIntensity(double intensity) { this.intensity = intensity; }
	
	/** @see org.almostrealism.color.Light#getIntensity() */
	@Override
	public double getIntensity() { return this.intensity; }
	
	/** @return  "Rectangular Light". */
	public String toString() { return "Rectangular Light"; }
}
