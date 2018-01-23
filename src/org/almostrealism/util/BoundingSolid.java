package org.almostrealism.util;

/**
 * Represents a 3D bounding solid.
 */
public class BoundingSolid {

    public final double minX, maxX, minY, maxY, minZ, maxZ;
    public final double dx, dy, dz;
    public final double volume;

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
    }
}
