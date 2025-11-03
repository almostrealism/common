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

package org.almostrealism.primitives;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import io.almostrealism.relation.Producer;
import io.almostrealism.code.Operator;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.geometry.ShadableIntersection;

/** A {@link PointLightGrid} stores a grid of {@link PointLight}s. */
public class PointLightGrid extends AbstractSurface implements Light {
	private double intensity;
	private RGB color;

	private PointLight lights[];

	/**
	  Constructs a new PointLightGrid object with the specified width, height,
	  and x and y spacing between lights and uses a default PointLight object
	  for each light in the grid. After the grid is created the total intensity
	  will be set to 1.0.
	*/
	
	public PointLightGrid(int width, int height, double xSpace, double ySpace) {
		this.updateGrid(width, height, xSpace, ySpace, new PointLight());
		this.setIntensity(1.0);
	}
	
	/**
	  Constructs a new PointLightGrid object with the specified width, height,
	  and x and y spacing between lights using the data from the specified
	  PointLight object for each light in the grid.
	*/
	
	public PointLightGrid(int width, int height, double xSpace, double ySpace, PointLight prototype) {
		this.updateGrid(width, height, xSpace, ySpace, prototype);
	}
	
	/**
	  Updates the light grid stored by this PointLightGrid object to have the specified width, height,
	  and x and y spaceing between lights using the data from the specified Point Light object for
	  each light in the grid.
	*/
	
	public void updateGrid(int width, int height, double xSpace, double ySpace, PointLight prototype) {
		this.lights = new PointLight[width * height];
		
		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				Vector location = new Vector(i * xSpace, j * ySpace, 0.0);
				location = super.getTransform(true).transformAsLocation(location);
				
				this.lights[i * width + j] = new PointLight(location, prototype.getIntensity(), new RGB(prototype.getColor().getRed(), prototype.getColor().getGreen(), prototype.getColor().getBlue()));
			}
		}
		
		this.intensity = prototype.getIntensity() * width * height;
		
		if (this.color != null)
			this.setColor(color);
		else
			this.setColor(new RGB(1.0, 1.0, 1.0));
	}
	
	/**
	  Sets the total intensity of this PointLightGrid object (the sum of intensities of
	  all lights in the grid) to the specified double value.
	*/
	@Override
	public void setIntensity(double intensity) {
		this.intensity = intensity;
		
		for (int i = 0; i < this.lights.length; i++) {
			this.lights[i].setIntensity(this.intensity / this.lights.length);
		}
	}
	
	/**
	  Sets the color of the PointLight objects stored by this PointLightGrid object
	  to the color represented by the specified RGB object.
	*/
	@Override
	public void setColor(RGB color) {
		super.setColor(color);
		
		if (this.lights == null)
			return;
		
		for (int i = 0; i < this.lights.length; i++) {
			this.lights[i].setColor(super.getColor());
		}
	}
	
	/**
	 * Returns the total intensity of this PointLightGrid
	 * (the sum of the intensities of all lights in the grid).
	 */
	@Override
	public double getIntensity() { return this.intensity; }
	
	/** Returns the array of {@link PointLight}s stored by this {@link PointLightGrid}. */
	public PointLight[] getLights() { return this.lights; }
	
	/** Returns a zero vector. */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		return ZeroVector.getInstance();
	}

	/**
	 * Delegates to {@link #getValueAt(Producer)}.
	 */
	@Override
	public Producer<RGB> getColorAt(Producer<Vector> point) { return getValueAt(point); }

	/** Returns null. */
	@Override
	public ShadableIntersection intersectAt(Producer ray) {
		return null;
	}

	@Override
	public Operator<Scalar> expect() {
		return null;
	}

	@Override
	public Operator<Scalar> get() {
		return null;
	}
}
