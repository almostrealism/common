package org.almostrealism.util;

import org.almostrealism.algebra.Ray;
import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.RayField;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Used to construct a RayField from the given bounding solid.
 */
public class RayFieldFactory {

    private final BoundingSolid bounds;
    private final int rayCount;
    private double scale = 1d;
    private RayDistribution distribution = RayDistribution.UNIFORM;

    public RayFieldFactory(BoundingSolid bounds, int rayCount) {
        this.bounds = bounds;
        this.rayCount = rayCount;
    }

    public RayFieldFactory setScale(double scale) {
        this.scale = scale;
        return this;
    }

    public RayFieldFactory setDistribution(RayDistribution distribution) {
        this.distribution = distribution;
        return this;
    }

    public RayField build() {
        int totalRays = (int) (rayCount * scale);
        Set<Ray> rays;

        switch (distribution) {
            case UNIFORM:
                rays = generateUniform(totalRays);
                break;
            case GUASSIAN:
                rays = generateGuassian(totalRays);
                break;
            case RANDOM:
                rays = generateRandom(totalRays);
                break;
            default:
                return null;
        }

        RayField rf = new RayField();

        for (Ray ray : rays) {
            Callable<Ray> r = () -> ray;
            rf.add(r);
        }

        return rf;
    }

    private Set<Ray> generateUniform(int totalRays) {
        Set<Ray> rays = new HashSet<>();
        // TODO;
        return rays;
    }

    private Set<Ray> generateGuassian(int totalRays) {
        Set<Ray> rays = new HashSet<>();
        // TODO
        return rays;
    }

    private Set<Ray> generateRandom(int totalRays) {
        Random rand = new Random();
        Set<Ray> rays = new HashSet<>();

        double dx = bounds.getMaxX() - bounds.getMinX();
        double dy = bounds.getMaxY() - bounds.getMinY();
        double dz = bounds.getMaxZ() - bounds.getMinZ();

        for(int i = 0; i<totalRays; ++i) {
            Ray ray = new Ray();
            double x = rand.nextDouble() * dx + bounds.getMinX();
            double y = rand.nextDouble() * dy + bounds.getMinY();
            double z = rand.nextDouble() * dz + bounds.getMinZ();

            ray.setOrigin(new Vector(x, y, z));
            rays.add(ray);
        }

        return rays;
    }

    public enum RayDistribution {
        UNIFORM, GUASSIAN, RANDOM;
    }
}
