package org.almostrealism.util;

import org.almostrealism.algebra.Ray;
import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.RayField;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Used to construct a RayField from the given bounding solid.
 */
public class RayFieldFactory
{
    public enum RayDistribution { UNIFORM, RANDOM }

    private static RayFieldFactory INSTANCE;

    private RayFieldFactory() {}

    public static RayFieldFactory getFactory()
    {
        if (INSTANCE == null) {
            INSTANCE = new RayFieldFactory();
        }
        return INSTANCE;
    }

    public RayField buildRayField(BoundingSolid bounds, int rayCount, RayDistribution distribution) {
        rayCount = rayCount < 0 ? 0 : rayCount;

        Set<Ray> rays;
        switch (distribution) {
            case UNIFORM:
                rays = generateUniform(bounds, rayCount);
                break;
            case RANDOM:
                rays = generateRandom(bounds, rayCount);
                break;
            default:
                return null;
        }

        RayField rayField = new RayField();
        for (Ray ray : rays) {
            Callable<Ray> r = () -> ray;
            rayField.add(r);
        }

        return rayField;
    }

    /**
     * A fast method to produce a uniform distribution of vertices within the bounding solid by repeated subdivision.
     *
     * Limitations to this approach are:
     * - Each subdivision adds multiple vertices. Subdivision stops once we equal or exceed the requested value,
     *   but this method can produce more vertices than requested.
     * - The distance between vertices on any given axis is constant, but can vary between axes. Subdivision
     *   is performed on the axis with the largest face, so the solution will converge on producing cubes if
     *   enough vertices are requested.
     */
    private Set<Ray> generateUniform(BoundingSolid bounds, int rayCount) {
        int xPlanes = 2, yPlanes = 2, zPlanes = 2;
        double xFaceWidth = bounds.dx / (xPlanes - 1);
        double yFaceWidth = bounds.dy / (yPlanes - 1);
        double zFaceWidth = bounds.dz / (zPlanes - 1);

        while (xPlanes * yPlanes * zPlanes < rayCount) {
            if (xFaceWidth >= yFaceWidth && xFaceWidth >= zFaceWidth) {
                xPlanes++;
                xFaceWidth = bounds.dx / (xPlanes - 1);
            }
            else if (yFaceWidth >= xFaceWidth && yFaceWidth >= zFaceWidth) {
                yPlanes++;
                yFaceWidth = bounds.dy / (yPlanes - 1);
            }
            else {
                zPlanes++;
                zFaceWidth = bounds.dz / (zPlanes - 1);
            }
        }

        Set<Ray> rays = new HashSet<>();
        for (int x=0; x < xPlanes; ++x) {
            for (int y = 0; y < yPlanes; ++y) {
                for (int z = 0; z < zPlanes; ++z) {
                    double ox = x * xFaceWidth + bounds.minX;
                    double oy = y * yFaceWidth + bounds.minY;
                    double oz = z * zFaceWidth + bounds.minZ;
                    Vector origin = new Vector(ox, oy, oz);
                    Ray ray = new Ray();
                    ray.setOrigin(origin);
                    rays.add(ray);
                }
            }
        }

        return rays;
    }

    /**
     * Generates a random distribution of Rays.
     */
    private Set<Ray> generateRandom(BoundingSolid bounds, int rayCount) {
        Random r = new Random();
        Set<Ray> rays = new HashSet<>();

        for(int i = 0; i < rayCount; ++i) {
            Ray ray = new Ray();
            double x = r.nextDouble() * bounds.dx + bounds.minX;
            double y = r.nextDouble() * bounds.dy + bounds.minY;
            double z = r.nextDouble() * bounds.dz + bounds.minZ;

            ray.setOrigin(new Vector(x, y, z));
            rays.add(ray);
        }

        return rays;
    }
}
