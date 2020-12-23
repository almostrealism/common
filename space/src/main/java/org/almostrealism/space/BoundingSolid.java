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

package org.almostrealism.space;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Positioned;

/**
 * Represents a 3D bounding solid.
 * Provides functionality to calculate a minimum bounding solid from a given
 * array of Positioned objects.
 *
 * @author Dan Chivers
 */
public class BoundingSolid {

    public final double minX, maxX, minY, maxY, minZ, maxZ;
    public final double dx, dy, dz;
    public final double volume;
    public final Positioned center;

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
