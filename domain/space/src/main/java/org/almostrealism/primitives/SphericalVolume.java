/*
 * Copyright 2019 Michael Murray
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
import org.almostrealism.physics.Volume;

/**
 * A solid ball centred on the origin, for confining an absorber; points on the surface are inside.
 * Unlike {@link Sphere}, it is not a renderable surface.
 *
 * <p>Surface coordinates are latitude and longitude, each scaled to [0, 1]: {@code u} runs from the
 * south pole ({@code z = -radius}) to the north pole, and {@code v} runs once around the
 * {@code z} axis starting from the negative {@code x} axis.</p>
 *
 * @author  Michael Murray
 */
public class SphericalVolume implements Volume<Object>, CodeFeatures {
	/** The radius of the ball. */
	private double radius;

	/** Creates a ball of radius one. */
	public SphericalVolume() { this(1.0); }

	/**
	 * Creates a ball of the given radius.
	 *
	 * @param radius the radius
	 */
	public SphericalVolume(double radius) { this.radius = radius; }

	/**
	 * Sets the radius of the ball.
	 *
	 * @param r the radius
	 */
	public void setRadius(double r) { this.radius = r; }

	/**
	 * Returns the radius of the ball.
	 *
	 * @return the radius
	 */
	public double getRadius() { return this.radius; }

	/**
	 * A ball carries no value field, so this returns null.
	 *
	 * @param point ignored
	 * @return null
	 */
	@Override
	public Producer getValueAt(Producer point) {
		return null;
	}

	/**
	 * Returns the unit vector from the centre towards the given position.
	 *
	 * @param x the position
	 * @return the outward unit normal
	 */
	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) {
		return normalize(x);
	}

	@Override
	public boolean inside(Producer<PackedCollection> x) {
		return new Vector(x.get().evaluate(), 0).length() <= this.radius;
	}

	/**
	 * Returns the distance along the given direction, in multiples of its length, from the given
	 * point to the nearest point ahead of it on the surface, or {@link #MISS} if there is none.
	 *
	 * @param p the starting point
	 * @param d the direction
	 * @return the distance to the surface, or {@link #MISS}
	 */
	@Override
	public double intersect(Vector p, Vector d) {
		double b = p.dotProduct(d);
		double c = p.dotProduct(p) - radius * radius;
		double g = d.dotProduct(d);

		double discriminant = b * b - g * c;
		if (discriminant < 0.0) return MISS;

		double root = Math.sqrt(discriminant);
		double near = (-b - root) / g;
		double far = (-b + root) / g;

		if (near >= 0.0) return near;
		if (far >= 0.0) return far;
		return MISS;
	}

	@Override
	public double[] getSpatialCoords(double[] uv) {
		double latitude = (uv[0] - 0.5) * Math.PI;
		double longitude = (uv[1] - 0.5) * 2.0 * Math.PI;

		return new double[] {
				radius * Math.cos(latitude) * Math.cos(longitude),
				radius * Math.cos(latitude) * Math.sin(longitude),
				radius * Math.sin(latitude) };
	}

	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> v) {
		double[] xyz = new Vector(v.get().evaluate(), 0).toArray();
		double r = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2]);
		if (r == 0.0) return new double[] { 0.5, 0.5 };

		return new double[] {
				0.5 + Math.asin(xyz[2] / r) / Math.PI,
				0.5 + Math.atan2(xyz[1], xyz[0]) / (2.0 * Math.PI) };
	}
}
