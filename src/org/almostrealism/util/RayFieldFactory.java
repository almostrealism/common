package org.almostrealism.util;

import org.almostrealism.algebra.Ray;
import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.RayField;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Used to construct a RayField from the given bounding solid.
 */
public class RayFieldFactory {

    private final BoundingSolid bounds;
    private final int rayCount;

    private double scale = 1d;
    private RayDistribution distribution = RayDistribution.PSEUDOUNIFORM;

    public RayFieldFactory(BoundingSolid bounds, int rayCount) {
        this.bounds = bounds;
        this.rayCount = rayCount < 0 ? 0 :rayCount;
    }

    public RayFieldFactory setScale(double scale) {
        this.scale = scale < 0 ? 0 : scale;
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
            case PSEUDOUNIFORM:
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

    /**
     * Uses Lloyd's algorithm for adjusting ray positions to achieve a more
     * uniform distribution.
     * Will achieve visibly superior results to a random distribution with
     * a performance trade-off.
     * TODO: The algorithm performance can potentially be improved either by
     * optimisation of the code itself or a careful approach to selecting the
     * number of density points / iterations.
     */
    private Set<Ray> generateUniform(int totalRays) {
        final int DENSITY_POINTS = totalRays * 10;
        final int ITERATIONS = 30;

        Set<Vector> densityPoints = new HashSet<>();
        Set<Ray> sites = new HashSet<>();

        // Generate random density points.
        Random r = new Random();
        for (int i=0 ; i<DENSITY_POINTS ; ++i) {
            double x = r.nextDouble() * bounds.dx + bounds.minX;
            double y = r.nextDouble() * bounds.dy + bounds.minY;
            double z = r.nextDouble() * bounds.dz + bounds.minZ;
            densityPoints.add(new Vector(x, y, z));
        }

        // Generate N random sites.
        for (int i=0 ; i<totalRays ; ++i) {
            double x = r.nextDouble() * bounds.dx + bounds.minX;
            double y = r.nextDouble() * bounds.dy + bounds.minY;
            double z = r.nextDouble() * bounds.dz + bounds.minZ;
            Ray newRay = new Ray();
            newRay.setOrigin(new Vector(x, y, z));
            sites.add(newRay);
        }

        for (int i=0 ; i<ITERATIONS ; ++i) {
            // Assign density points to nearest sites.
            // Map (site > density point).
            Map<Ray, Set<Vector>> nearestDensityPointsMap = new HashMap<>();

            for (Vector densityPoint : densityPoints) {
                Ray closestSite = null;
                double dist = Double.MAX_VALUE;
                for (Ray site : sites) {
                    double d = dist(densityPoint, site.getOrigin());
                    if (closestSite == null || d < dist) {
                        closestSite = site;
                        dist = d;
                    }
                }
                Set<Vector> nearestDensityPoints = nearestDensityPointsMap.computeIfAbsent(closestSite, k -> new HashSet<>());
                nearestDensityPoints.add(densityPoint);
            }

            // Move each site to the barycenter of it's density points.
            for (Map.Entry<Ray, Set<Vector>> entry : nearestDensityPointsMap.entrySet()) {
                Ray site = entry.getKey();
                Set<Vector> closestDensityPoints = entry.getValue();
                double bx = 0, by = 0, bz = 0;

                for (Vector densityPoint : closestDensityPoints) {
                    bx += densityPoint.getX();
                    by += densityPoint.getY();
                    bz += densityPoint.getZ();
                }

                bx /= closestDensityPoints.size();
                by /= closestDensityPoints.size();
                bz /= closestDensityPoints.size();

                Vector newOrigin = new Vector(bx, by, bz);
                site.setOrigin(newOrigin);
            }
        }

        return sites;
    }

    private double dist(Vector a, Vector b) {
        return Math.sqrt(Math.pow(a.getX() - b.getX(), 2)
                       + Math.pow(a.getY() - b.getY(), 2)
                       + Math.pow(a.getZ() - b.getZ(), 2));
    }

    private Set<Ray> generateGuassian(int totalRays) {
        Set<Ray> rays = new HashSet<>();
        // TODO
        return rays;
    }

    private Set<Ray> generateRandom(int totalRays) {
        Random rand = new Random();
        Set<Ray> rays = new HashSet<>();

        for(int i = 0; i<totalRays; ++i) {
            Ray ray = new Ray();
            double x = rand.nextDouble() * bounds.dx + bounds.minX;
            double y = rand.nextDouble() * bounds.dy + bounds.minY;
            double z = rand.nextDouble() * bounds.dz + bounds.minZ;

            ray.setOrigin(new Vector(x, y, z));
            rays.add(ray);
        }

        return rays;
    }

    public enum RayDistribution {
        PSEUDOUNIFORM, GUASSIAN, RANDOM
    }
}
