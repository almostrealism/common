/*
 * Copyright 2023 Michael Murray
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

import java.util.ArrayList;
import java.util.List;

/**
 * The six walls of a hollow box centred on the origin, each a {@link Plane} slab, for confining an
 * absorber. Only the walls are inside the volume, not the space they enclose.
 *
 * <p>The box is oriented by its {@link #setSurfaceNormal(double[]) surface normal}, the outward
 * normal of its bottom, and its {@link #setOrientation(double[]) orientation}, which points from its
 * back to its front and is made perpendicular to the normal. The height runs along the normal, the
 * depth along the orientation, and the width along their cross product. The walls are rebuilt
 * whenever a dimension or direction changes.</p>
 *
 * <p>Surface coordinates lay the walls side by side: {@code u} in {@code [i / 6, (i + 1) / 6)}
 * falls on wall {@code i}, in the order bottom, top, the two sides and the two ends, and within a
 * wall the coordinates are the wall's own {@link Plane#getSurfaceCoords(Producer)}.</p>
 *
 * @author Samuel Tepper
 */
public class BoxVolume implements Volume<Object>, CodeFeatures {
	/** One wall: a slab and the position of its centre relative to the centre of the box. */
	private static class Wall {
		/** The slab, centred on its own origin. */
		private final Plane plane;

		/** The outward unit normal of the wall. */
		private final Vector normal;

		/** The centre of the wall relative to the centre of the box. */
		private final Vector offset;

		/**
		 * Creates a wall.
		 *
		 * @param plane  the slab
		 * @param normal the outward unit normal
		 * @param offset the centre of the wall relative to the centre of the box
		 */
		Wall(Plane plane, Vector normal, Vector offset) {
			this.plane = plane;
			this.normal = normal;
			this.offset = offset;
		}

		/**
		 * Returns the given point relative to the centre of this wall.
		 *
		 * @param x the point relative to the centre of the box
		 * @return the point relative to the wall
		 */
		Vector local(Vector x) { return x.subtract(offset); }

		/**
		 * Returns the distance from the given point to the middle plane of this wall.
		 *
		 * @param x the point relative to the centre of the box
		 * @return the unsigned distance
		 */
		double distance(Vector x) { return Math.abs(local(x).dotProduct(normal)); }
	}

	/** The extent along the cross product of the orientation and the normal. */
	private double width;

	/** The extent along the surface normal. */
	private double height;

	/** The extent along the orientation. */
	private double depth;

	/** The thickness of each wall. */
	private double wallThickness;

	/** The direction from the back of the box to its front. */
	private double[] orientation;

	/** The outward normal of the bottom of the box. */
	private double[] normal;

	/** The walls, built when first needed after a change. */
	private List<Wall> walls;

	/**
	 * Sets the width, the extent along the cross product of the orientation and the normal.
	 *
	 * @param x the width
	 */
	public void setWidth(double x) { this.width = x; this.walls = null; }

	/**
	 * Sets the height, the extent along the surface normal.
	 *
	 * @param x the height
	 */
	public void setHeight(double x) { this.height = x; this.walls = null; }

	/**
	 * Sets the depth, the extent along the orientation.
	 *
	 * @param x the depth
	 */
	public void setDepth(double x) { this.depth = x; this.walls = null; }

	/**
	 * Sets the direction from the back of the box to its front.
	 *
	 * @param x {x, y, z}, not parallel to the surface normal
	 */
	public void setOrientation(double[] x) { this.orientation = x; this.walls = null; }

	/**
	 * Sets the outward normal of the bottom of the box.
	 *
	 * @param x {x, y, z}
	 */
	public void setSurfaceNormal(double[] x) { this.normal = x; this.walls = null; }

	/**
	 * Sets the thickness of each wall, as passed to {@link Plane#setThickness(double)}.
	 *
	 * @param x the wall thickness
	 */
	public void setWallThickness(double x) { this.wallThickness = x; this.walls = null; }

	/**
	 * Returns the direction from the back of the box to its front.
	 *
	 * @return {x, y, z}
	 */
	public double[] getOrientation() { return this.orientation; }

	/**
	 * Returns the outward normal of the bottom of the box.
	 *
	 * @return {x, y, z}
	 */
	public double[] getSurfaceNormal() { return this.normal; }

	/**
	 * Returns the width.
	 *
	 * @return the width
	 */
	public double getWidth() { return this.width; }

	/**
	 * Returns the height.
	 *
	 * @return the height
	 */
	public double getHeight() { return this.height; }

	/**
	 * Returns the depth.
	 *
	 * @return the depth
	 */
	public double getDepth() { return this.depth; }

	/**
	 * Returns the thickness of each wall.
	 *
	 * @return the wall thickness
	 */
	public double getWallThickness() { return this.wallThickness; }

	/**
	 * Returns the walls, building them from the current dimensions and directions if needed.
	 *
	 * @return the six walls
	 * @throws IllegalStateException if the normal or orientation is missing, or they are parallel
	 */
	private List<Wall> walls() {
		if (walls != null) return walls;

		if (normal == null || orientation == null) {
			throw new IllegalStateException("The box needs a surface normal and an orientation");
		}

		Vector n = new Vector(normal);
		n.normalize();

		Vector o = new Vector(orientation);
		o = o.subtract(n.multiply(o.dotProduct(n)));
		if (o.length() == 0.0) {
			throw new IllegalStateException("The box orientation is parallel to its surface normal");
		}
		o.normalize();

		Vector s = o.crossProduct(n);

		List<Wall> built = new ArrayList<>();
		addOpposingWalls(built, n, o, width, depth, height / 2.0);
		addOpposingWalls(built, s, o, height, depth, width / 2.0);
		addOpposingWalls(built, o, n, width, height, depth / 2.0);
		walls = built;
		return walls;
	}

	/**
	 * Adds the two walls facing outward along {@code axis} and against it.
	 *
	 * @param walls    the list to add to
	 * @param axis     the outward unit normal of the first wall
	 * @param up       the unit direction of each wall's height, perpendicular to {@code axis}
	 * @param w        the width of each wall
	 * @param h        the height of each wall
	 * @param distance the distance of each wall from the centre of the box
	 */
	private void addOpposingWalls(List<Wall> walls, Vector axis, Vector up, double w, double h, double distance) {
		walls.add(wall(axis, up, w, h, axis.multiply(distance)));
		walls.add(wall(axis.minus(), up, w, h, axis.multiply(-distance)));
	}

	/**
	 * Creates one wall.
	 *
	 * @param n      the outward unit normal
	 * @param up     the unit direction of the wall's height
	 * @param w      the width
	 * @param h      the height
	 * @param offset the centre of the wall relative to the centre of the box
	 * @return the wall
	 */
	private Wall wall(Vector n, Vector up, double w, double h, Vector offset) {
		Plane plane = new Plane();
		plane.setWidth(w);
		plane.setHeight(h);
		plane.setThickness(wallThickness);
		plane.setOrientation(up.toArray());
		plane.setSurfaceNormal(value(n));
		return new Wall(plane, n, offset);
	}

	/**
	 * Returns the index of the wall whose middle plane is nearest the given point, preferring the
	 * walls whose slab contains it.
	 *
	 * @param x the point
	 * @return the wall index
	 */
	private int nearestWall(Vector x) {
		List<Wall> all = walls();
		int nearest = -1;
		boolean nearestContains = false;
		double nearestDistance = Double.MAX_VALUE;

		for (int i = 0; i < all.size(); i++) {
			Wall wall = all.get(i);
			boolean contains = wall.plane.inside(v(wall.local(x)));
			double distance = wall.distance(x);

			if ((contains && !nearestContains) || (contains == nearestContains && distance < nearestDistance)) {
				nearest = i;
				nearestContains = contains;
				nearestDistance = distance;
			}
		}

		return nearest;
	}

	/**
	 * The box carries no value field, so this returns null.
	 *
	 * @param point ignored
	 * @return null
	 */
	@Override
	public Producer getValueAt(Producer point) {
		return null;
	}

	/**
	 * Returns the outward normal of the wall nearest the given point.
	 *
	 * @param x the point
	 * @return the unit normal
	 */
	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) {
		return () -> args -> {
			Vector p = new Vector(x.get().evaluate(args), 0);
			return walls().get(nearestWall(p)).normal.clone();
		};
	}

	@Override
	public boolean inside(Producer<PackedCollection> x) {
		Vector p = new Vector(x.get().evaluate(), 0);

		for (Wall wall : walls()) {
			if (wall.plane.inside(v(wall.local(p)))) return true;
		}

		return false;
	}

	@Override
	public double intersect(Vector p, Vector d) {
		double nearest = SphericalVolume.MISS;

		for (Wall wall : walls()) {
			nearest = Math.min(nearest, wall.plane.intersect(wall.local(p), d));
		}

		return nearest;
	}

	@Override
	public double[] getSpatialCoords(double[] uv) {
		List<Wall> all = walls();
		double scaled = uv[0] * all.size();
		int index = Math.max(0, Math.min(all.size() - 1, (int) scaled));
		Wall wall = all.get(index);

		Vector local = new Vector(wall.plane.getSpatialCoords(new double[] { scaled - index, uv[1] }));
		return local.add(wall.offset).toArray();
	}

	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> xyz) {
		Vector p = new Vector(xyz.get().evaluate(), 0);
		int index = nearestWall(p);
		Wall wall = walls().get(index);

		double[] uv = wall.plane.getSurfaceCoords(v(wall.local(p)));
		return new double[] { (index + uv[0]) / walls().size(), uv[1] };
	}
}
