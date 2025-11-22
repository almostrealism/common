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
    /** The center point of this bounding solid. */
    public final Positioned center;

    /**
     * Constructs a new BoundingSolid with the specified extents.
     *
     * @param minX the minimum x-coordinate
     * @param maxX the maximum x-coordinate
     * @param minY the minimum y-coordinate
     * @param maxY the maximum y-coordinate
     * @param minZ the minimum z-coordinate
     * @param maxZ the maximum z-coordinate
     * @throws UnsupportedOperationException currently unimplemented due to center calculation
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

        // center = new Vector(minX+dx/2, minY+dy/2, minZ+dz/2); TODO
        throw new UnsupportedOperationException();
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
        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = Double.MIN_VALUE;

        if (points == null || points.length == 0) {
            return new BoundingSolid(0, 0, 0, 0, 0, 0);
        }

        for (Positioned p : points) {
            double x = p.getPosition()[0];
            double y = p.getPosition()[1];
            double z = p.getPosition()[2];

            minX = x < minX ? x : minX;
            maxX = x > maxX ? x : maxX;
            minY = y < minY ? y : minY;
            maxY = y > maxY ? y : maxY;
            minZ = z < minZ ? z : minZ;
            maxZ = z > maxZ ? z : maxZ;
        }

        return new BoundingSolid(minX, maxX, minY, maxY, minZ, maxZ);
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
