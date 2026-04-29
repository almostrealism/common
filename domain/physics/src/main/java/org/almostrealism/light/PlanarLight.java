/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.light;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.VectorMath;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.SurfaceLight;
import org.almostrealism.geometry.Locatable;
import org.almostrealism.geometry.UniformSphericalRandom;

/**
 * A planar area light source that emits photons from a rectangular surface.
 * <p>
 * The {@code PlanarLight} models a flat luminaire with configurable width, height,
 * surface normal, and orientation vector. Emission positions are sampled uniformly
 * across the plane, and emission directions are either normal to the surface or
 * distributed over a hemisphere (based on the {@code lightProp} flag).
 * </p>
 */
public class PlanarLight extends LightBulb implements SurfaceLight, Locatable, VectorFeatures, RGBFeatures {
	/** Width of the emission plane in scene units (typically micrometers). */
	private double w;

	/** Height of the emission plane in scene units (typically micrometers). */
	private double h;

	/** Unit vector normal to the plane surface, pointing in the primary emission direction. */
	private Vector normal;

	/** Vector pointing upward across the plane surface; {@code across} is derived from this. */
	private Vector up;

	/** Vector pointing across the plane surface, computed as the cross product of up and normal. */
	private Vector across;

	/** When {@code true}, emission directions are distributed over a hemisphere; otherwise normal only. */
	private boolean lightProp = false;

	/** Alignment blend factor between the random hemisphere direction and the surface normal. */
	private final double align = 0.0;

	/** Optional color tint applied to all light samples produced by this planar light. */
	private RGB color;

	/** World-space location of the center of this planar light. */
	private Vector location;
	
	/**
	 * Sets the width of the planar light (usually measured in micrometers).
	 *
	 * @param w  The width of the planar light (usually measured in micrometers).
	 */
	public void setWidth(double w) { this.w = w; }

	/**
	 * Returns the width of the planar light (usually measured in micrometers).
	 */
	public double getWidth() { return this.w; }

	/**
	 * Sets the height of the planar light (usually measured in micrometers).
	 *
	 * @param h  The height of the planar light (usually measured in micrometers).
	 */
	public void setHeight(double h) { this.h = h; }

	/**
	 * Returns the height of the planar light (usually measured in micrometers).
	 */
	public double getHeight() { return this.h; }

	/**
	 * Sets the vector normal to the absorption plane.
	 *
	 * @param p  {x, y, z} - The vector normal to the absorption plane.
	 */
	public void setSurfaceNormal(Vector p) { this.normal = p;	this.across = null; }

	/**
	 * Returns the vector normal to the absorption plane.
	 *
	 * @return  {x, y, z} - The vector normal to the absorption plane.
	 */
	public Vector getSurfaceNormal() { return this.normal; }

	/**
	 * Sets the vector pointing upwards across the surface of this absorption plane.
	 *
	 * @param p  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane. This vector must be orthagonal to the surface normal.
	 */
	public void setOrientation(Vector p) { this.up = p; this.across = null; }

	/**
	 * Returns the vector pointing upwards across the surface of this absorption plane.
	 *
	 * @return  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane.
	 */
	public Vector getOrientation() { return this.up; }

	/**
	 * Sets whether light emission direction is distributed over a hemisphere or is normal only.
	 *
	 * @param t  true sets the direction of light to be in a uniform semisphere
	 * 			 normal to the plane. false sets the direction to be normal to
	 *           the plane.
	 */
	public void setLightPropagation(boolean t) { this.lightProp = t; }
	
	public boolean getLightPropagation() { return this.lightProp; }

	@Override
	public Producer<PackedCollection> getColorAt(Producer<PackedCollection> point) {
		return v(getColor());
	}

	@Override
	public Producer<PackedCollection> emit() {
		super.last += super.delta;

		if (!this.lightProp)
			return v(this.normal);

		Vector v = UniformSphericalRandom.getInstance().evaluate(new Object[0]);
		if (v.dotProduct(this.normal) < 0)
			v.multiplyBy(-1.0);

		return normalize(vector(VectorMath.addMultiple(v.toArray(), this.normal.toArray(), this.align)));
	}

	@Override
	public Producer<PackedCollection> getEmitPosition() {
		if (this.across == null)
			across = up.crossProduct(normal);

		// TODO  Put random computation into vector producer evaluate method
		Vector x = across.multiply((Math.random() - 0.5) * this.w);
		x.addTo(up.multiply((Math.random() - 0.5) * this.h));
		return v(x);
	}

	@Override
	public void setIntensity(double intensity) { }

	@Override
	public double getIntensity() { return 1.0; }

	@Override
	public void setColor(RGB color) { this.color = color; }

	@Override
	public RGB getColor() { return this.color; }

	@Override
	public void setLocation(Vector l) { this.location = l; }

	@Override
	public Light[] getSamples(int total) {
		Light[] l = new Light[total];
		
		double in = 1.0 / total;
		
		for (int i = 0; i < total; i++) {
			double[] x = this.getSpatialCoords(new double[] {Math.random(), Math.random()});
			Vector p = new Vector(x[0], x[1], x[2]);
			p.addTo(this.location);
			RGB sampleColor = this.color != null ? this.color : new RGB(1.0, 1.0, 1.0);
			l[i] = new PointLight(p, in, sampleColor);
		}
		
		return l;
	}

	@Override
	public Light[] getSamples() { return this.getSamples(20); }
}
