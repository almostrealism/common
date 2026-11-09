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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Vector;

/**
 * Represents an axis-aligned 3D bounding box (AABB).
 * Provides functionality to calculate a minimum bounding solid from a given
 * array of {@link Positioned} objects.
 *
 * <p>A bounding solid is defined by its minimum and maximum extents along
 * each axis. It is commonly used for:</p>
 * <ul>
 *   <li>Spatial acceleration structures for ray tracing</li>
 *   <li>Collision detection broad-phase testing</li>
 *   <li>View frustum culling</li>
 *   <li>Object selection</li>
 * </ul>
 *
 * <p>All bounds fields are immutable and computed at construction time.</p>
 *
 * @author Dan Chivers
 * @see Positioned
 */
public class BoundingSolid {
    /** The minimum x-coordinate of this bounding solid. */
    public final double minX;
    /** The maximum x-coordinate of this bounding solid. */
    public final double maxX;
    /** The minimum y-coordinate of this bounding solid. */
    public final double minY;
    /** The maximum y-coordinate of this bounding solid. */
    public final double maxY;
    /** The minimum z-coordinate of this bounding solid. */
    public final double minZ;
    /** The maximum z-coordinate of this bounding solid. */
    public final double maxZ;
    /** The width (x-extent) of this bounding solid. */
    public final double dx;
    /** The height (y-extent) of this bounding solid. */
    public final double dy;
    /** The depth (z-extent) of this bounding solid. */
    public final double dz;
    /** The volume of this bounding solid (dx * dy * dz). */
    public final double volume;
    /** The center point of this bounding solid, at the midpoint of each extent. */
    public final Vector center;

    /**
     * Constructs a new BoundingSolid with the specified extents.
     *
     * @param minX the minimum x-coordinate
     * @param maxX the maximum x-coordinate
     * @param minY the minimum y-coordinate
     * @param maxY the maximum y-coordinate
     * @param minZ the minimum z-coordinate
     * @param maxZ the maximum z-coordinate
     */
    public BoundingSolid(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;

        dx = maxX - minX;
        dy = maxY - minY;
        dz = maxZ - minZ;

        volume = dx * dy * dz;

        center = new Vector(minX + dx / 2, minY + dy / 2, minZ + dz / 2);
    }

    /**
     * Calculates the minimum axis-aligned bounding box that contains all
     * the specified positioned objects.
     *
     * @param points an array of {@link Positioned} objects to bound
     * @return a new {@link BoundingSolid} containing all points,
     *         or a zero-sized bounding solid if the array is null or empty
     */
    public static BoundingSolid getBounds(Positioned[] points) {
        if (points == null || points.length == 0) {
            return new BoundingSolid(0, 0, 0, 0, 0, 0);
        }

        double[][] coords = new double[points.length][];
        for (int i = 0; i < points.length; i++) {
            float[] p = points[i].getPosition();
            coords[i] = new double[] { p[0], p[1], p[2] };
        }

        return bound(coords);
    }

    /**
     * Calculates the minimum axis-aligned bounding box that contains all the
     * specified points.
     *
     * @param points the points to bound
     * @return a new {@link BoundingSolid} containing all points,
     *         or a zero-sized bounding solid if the array is null or empty
     */
    public static BoundingSolid of(Vector... points) {
        if (points == null || points.length == 0) {
            return new BoundingSolid(0, 0, 0, 0, 0, 0);
        }

        double[][] coords = new double[points.length][];
        for (int i = 0; i < points.length; i++) {
            coords[i] = new double[] { points[i].getX(), points[i].getY(), points[i].getZ() };
        }

        return bound(coords);
    }

    /**
     * Computes the minimum axis-aligned bounding box containing the given points, shared by
     * {@link #getBounds(Positioned[])} and {@link #of(Vector...)}.
     *
     * @param coords the points to bound, each a three-element {@code {x, y, z}} array
     * @return a new {@link BoundingSolid} containing all points
     */
    private static BoundingSolid bound(double[][] coords) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;

        for (double[] c : coords) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
            minZ = Math.min(minZ, c[2]);
            maxZ = Math.max(maxZ, c[2]);
        }

        return new BoundingSolid(minX, maxX, minY, maxY, minZ, maxZ);
    }

    /**
     * Returns a copy of this bounding solid expanded by the given amount on every side.
     *
     * @param amount the distance to grow the box outward on each face
     * @return a new {@link BoundingSolid} larger by {@code amount} on every side
     */
    public BoundingSolid expand(double amount) {
        return new BoundingSolid(minX - amount, maxX + amount,
                minY - amount, maxY + amount, minZ - amount, maxZ + amount);
    }

    /**
     * Returns the axis along which this bounding solid is longest.
     *
     * @return {@code 0} for x, {@code 1} for y, or {@code 2} for z
     */
    public int longestAxis() {
        if (dx >= dy && dx >= dz) return 0;
        if (dy >= dz) return 1;
        return 2;
    }

    /**
     * Returns this bounding solid's center coordinate along the given axis.
     *
     * @param axis the axis to read ({@code 0} for x, {@code 1} for y, {@code 2} for z)
     * @return the center coordinate on that axis
     */
    public double center(int axis) {
        if (axis == 0) return center.getX();
        if (axis == 1) return center.getY();
        return center.getZ();
    }

    /**
     * Tests whether the half-line that starts at the given origin and extends in the
     * given direction enters this box at a non-negative distance, using the slab method.
     *
     * <p>This is the broad-phase test used by acceleration structures: a triangle whose
     * box fails this test cannot be hit by the ray.</p>
     *
     * @param ox   origin x
     * @param oy   origin y
     * @param oz   origin z
     * @param dirX direction x
     * @param dirY direction y
     * @param dirZ direction z
     * @return {@code true} if the ray enters the box at a distance {@code >= 0}
     */
    public boolean intersectsRay(double ox, double oy, double oz, double dirX, double dirY, double dirZ) {
        double tmin = 0.0;
        double tmax = Double.POSITIVE_INFINITY;

        double[] origin = { ox, oy, oz };
        double[] direction = { dirX, dirY, dirZ };
        double[] lo = { minX, minY, minZ };
        double[] hi = { maxX, maxY, maxZ };

        for (int a = 0; a < 3; a++) {
            if (Math.abs(direction[a]) < 1e-12) {
                if (origin[a] < lo[a] || origin[a] > hi[a]) return false;
            } else {
                double inv = 1.0 / direction[a];
                double t1 = (lo[a] - origin[a]) * inv;
                double t2 = (hi[a] - origin[a]) * inv;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                if (t1 > tmin) tmin = t1;
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) return false;
            }
        }

        return true;
    }

    /**
     * Creates a new bounding solid that is the union of this bounding solid
     * and another, encompassing both volumes.
     *
     * @param other the other bounding solid to combine with
     * @return a new {@link BoundingSolid} that contains both this and the other bounding solid
     */
    public BoundingSolid combine(BoundingSolid other) {
        double _minX = Math.min(minX, other.minX);
        double _maxX = Math.max(maxX, other.maxX);
        double _minY = Math.min(minY, other.minY);
        double _maxY = Math.max(maxY, other.maxY);
        double _minZ = Math.min(minZ, other.minZ);
        double _maxZ = Math.max(maxZ, other.maxZ);
        return new BoundingSolid(_minX, _maxX, _minY, _maxY, _minZ, _maxZ);
    }
}
