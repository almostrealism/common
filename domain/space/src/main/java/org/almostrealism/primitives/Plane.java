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
import org.almostrealism.physics.Volume;

/**
 * An infinite-extent physical plane used as a base for absorption and pinhole-camera
 * surfaces.
 *
 * <p>The plane is characterised by its surface normal, an "up" orientation vector,
 * a physical thickness (the absorption slab depth), and width/height extents that
 * bound the active region.  It implements {@link org.almostrealism.physics.Volume}
 * so that photon-absorption physics can query whether a point lies inside the volume.</p>
 *
 * @author  Michael Murray
 */
public class Plane implements Volume<RGB>, CodeFeatures {
	/** Stores the last computed distance value for debugging purposes. */
	public static double d = 0.0;

	/** Width of the active region (typically in micrometres). */
	protected double w;

	/** Height of the active region (typically in micrometres). */
	protected double h;

	/** Half-thickness of the plane slab used for photon absorption tests. */
	protected double thick = 0.5;

	/** Unit vector pointing "up" across the surface; defines the V axis. */
	protected double[] up;

	/** Unit vector pointing "across" the surface; defined as {@code up} cross {@code normal}. */
	protected double[] across;

	/** Producer that evaluates to the outward surface-normal vector. */
	protected Producer<PackedCollection> normal;
	
	/**
	 * Sets the thickness of the absorption slab.
	 *
	 * @param t  The thickness of the plane (usually measured in micrometers).
	 */
	public void setThickness(double t) { this.thick = t; }

	/**
	 * Returns the thickness of the absorption slab.
	 *
	 * @return  The thickness of the plane (usually measured in micrometers).
	 */
	public double getThickness() { return this.thick; }

	/**
	 * Sets the width of the active region.
	 *
	 * @param w  The width of the plane (usually measured in micrometers).
	 */
	public void setWidth(double w) { this.w = w; }

	/**
	 * Returns the width of the active region (usually measured in micrometers).
	 */
	public double getWidth() { return this.w; }

	/**
	 * Sets the height of the active region.
	 *
	 * @param h  The height of the plane (usually measured in micrometers).
	 */
	public void setHeight(double h) { this.h = h; }

	/**
	 * Returns the height of the active region (usually measured in micrometers).
	 */
	public double getHeight() { return this.h; }

	/**
	 * Sets the outward surface-normal vector for this plane.
	 *
	 * @param p  {x, y, z} - The vector normal to the absorption plane.
	 */
	public void setSurfaceNormal(Producer<PackedCollection> p) {
		this.normal = p; this.across = null;
	}

	/**
	 * Returns the outward surface-normal vector for this plane.
	 *
	 * @return  {x, y, z} - The vector normal to the plane.
	 */
	public Producer<PackedCollection> getSurfaceNormal() { return this.normal; }

	/**
	 * Sets the orientation vector pointing upward across the surface.
	 *
	 * @param p  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane. This vector must be orthagonal to the surface normal.
	 */
	public void setOrientation(double[] p) { this.up = p; this.across = null; }

	/**
	 * Returns the orientation vector pointing upward across the surface.
	 *
	 * @return  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane.
	 */
	public double[] getOrientation() { return this.up; }
	
	/**
	 * Returns the vector that points across the surface (i.e., the U axis),
	 * computing it from {@link #up} and {@link #normal} on first call.
	 *
	 * @return the cached across vector
	 */
	public double[] getAcross() {
		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();

		return this.across;
	}

	@Override
	public boolean inside(Producer<PackedCollection> x) {
		double d = Math.abs(dotProduct(x, normal).get().evaluate().toDouble(0));
		Plane.d = d;
		if (d > this.thick) return false;

		double y = Math.abs(dotProduct(x, vector(up[0], up[1], up[2])).get().evaluate().toDouble(0));
		if (y > this.h / 2.0) return false;

		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();

		double z = Math.abs(dotProduct(x, vector(across[0], across[1], across[2])).get().evaluate().toDouble(0));
		return !(z > this.w / 2.0);
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
			if (!this.inside(value(x))) d1 = Double.MAX_VALUE - 1.0;
		}

		if (d2 < 0.0) {
			d2 = Double.MAX_VALUE - 1.0;
		} else {
			Vector x = d.multiply(d2 - this.thick / 2.0).add(p);
			if (!this.inside(value(x))) d2 = Double.MAX_VALUE - 1.0;
		}
		
		
		
		return Math.min(d1, d2);
	}

	@Override
	public Producer getValueAt(Producer point) { return null; }

	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) { return normal; }

	@Override
	public double[] getSpatialCoords(double[] uv) {
		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();

		return new Vector(this.across).multiply((uv[0] - 0.5) * this.w)
				.add(new Vector(this.up).multiply((0.5 - uv[1]) * this.h)).toArray();
	}

	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> v) {
		double[] xyz = v.get().evaluate().toArray();

		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();
		
		return new double[] { 0.5 + new Vector(this.across).dotProduct(new Vector(xyz)) / this.w,
							0.5 - new Vector(this.up).dotProduct(new Vector(xyz)) / this.h };
	}
}
