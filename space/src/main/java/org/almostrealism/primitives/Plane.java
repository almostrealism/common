/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.primitives;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.space.Volume;

public class Plane implements Volume<RGB>, CodeFeatures {
	public static double d = 0.0;
	
	protected double w, h;
	protected double thick = 0.5;
	protected double up[], across[];
	protected Producer<PackedCollection> normal;
	
	/** @param t  The thickness of the plane (usually measured in micrometers). */
	public void setThickness(double t) { this.thick = t; }
	
	/** @return  The thickness of the plane (usually measured in micrometers). */
	public double getThickness() { return this.thick; }
	
	/** @param w  The width of the plane (usually measured in micrometers). */
	public void setWidth(double w) { this.w = w; }
	
	/** Returns the width of the plane (usually measured in micrometers). */
	public double getWidth() { return this.w; }
	
	/**
	 * @param h  The height of the plane (usually measured in micrometers).
	 */
	public void setHeight(double h) { this.h = h; }
	
	/**
	 * Returns the height of the plane (usually measured in micrometers).
	 */
	public double getHeight() { return this.h; }

	/**
	 * @param p  {x, y, z} - The vector normal to the absorption plane.
	 */
	public void setSurfaceNormal(Producer<PackedCollection> p) {
		this.normal = p; this.across = null;
	}

	/**
	 * @return  {x, y, z} - The vector normal to the plane.
	 */
	public Producer<PackedCollection> getSurfaceNormal() { return this.normal; }
	
	/**
	 * @param p  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane. This vector must be orthagonal to the surface normal.
	 */
	public void setOrientation(double p[]) { this.up = p; this.across = null; }
	
	/**
	 * @return  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane.
	 */
	public double[] getOrientation() { return this.up; }
	
	public double[] getAcross() {
		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();

		return this.across;
	}

	@Override
	public boolean inside(Producer<PackedCollection> x) {
		double d = Math.abs(((PackedCollection) dotProduct((Producer) x, (Producer) normal).get().evaluate()).toDouble(0));
		Plane.d = d;
		if (d > this.thick) return false;

		double y = Math.abs(((PackedCollection) dotProduct((Producer) x, (Producer) vector(up[0], up[1], up[2])).get().evaluate()).toDouble(0));
		if (y > this.h / 2.0) return false;

		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();

		double z = Math.abs(((PackedCollection) dotProduct((Producer) x, (Producer) vector(across[0], across[1], across[2])).get().evaluate()).toDouble(0));
		if (z > this.w / 2.0) return false;

		return true;
	}

	@Override
	public double intersect(Vector p, Vector d) {
		double a = p.dotProduct(new Vector(normal.get().evaluate(), 0));
		double b = d.dotProduct(new Vector(normal.get().evaluate(), 0));
		
		double d1 = (this.thick - a) / b;
		double d2 = (-this.thick - a) / b;
		
		if (d1 < 0.0) {
			d1 = Double.MAX_VALUE - 1.0;
		} else {
			Vector x = d.multiply(d1 + this.thick / 2.0).add(p);
			if (!this.inside((Producer) value(x))) d1 = Double.MAX_VALUE - 1.0;
		}

		if (d2 < 0.0) {
			d2 = Double.MAX_VALUE - 1.0;
		} else {
			Vector x = d.multiply(d2 - this.thick / 2.0).add(p);
			if (!this.inside((Producer) value(x))) d2 = Double.MAX_VALUE - 1.0;
		}
		
		
		
		return Math.min(d1, d2);
	}

	@Override
	public Producer getValueAt(Producer point) { return null; }

	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) { return normal; }

	@Override
	public double[] getSpatialCoords(double uv[]) {
		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();

		return new Vector(this.across).multiply((uv[0] - 0.5) * this.w)
				.add(new Vector(this.up).multiply((0.5 - uv[1]) * this.h)).toArray();
	}

	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> v) {
		double xyz[] = v.get().evaluate().toArray();

		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();
		
		return new double[] { 0.5 + new Vector(this.across).dotProduct(new Vector(xyz)) / this.w,
							0.5 - new Vector(this.up).dotProduct(new Vector(xyz)) / this.h };
	}
}
