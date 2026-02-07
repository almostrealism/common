/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A brush that generates points with Gaussian (normal) distribution falloff.
 *
 * <p>Unlike {@link SphericalBrush} which uses linear falloff, this brush uses
 * Gaussian distribution for both point placement and intensity, creating a
 * softer, more natural brush stroke with smooth edges.</p>
 *
 * <h2>Gaussian Distribution</h2>
 * <p>Points are distributed using a 3D Gaussian distribution centered at the
 * brush position. The radius parameter controls the standard deviation (sigma).
 * Most points (68%) will fall within one sigma of the center, and 95% within
 * two sigmas.</p>
 *
 * <h2>Intensity Profile</h2>
 * <p>Intensity follows the Gaussian bell curve:</p>
 * <pre>
 * intensity = baseIntensity * pressure * exp(-distance² / (2 * radius²))
 * </pre>
 * <p>This creates a smooth falloff from full intensity at center to near-zero
 * at the edges, with no hard boundary.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Soft, airbrushed drawing effects</li>
 *   <li>Blending and smoothing frequency content</li>
 *   <li>Natural-looking brush strokes</li>
 * </ul>
 *
 * @see SpatialBrush
 * @see SphericalBrush
 */
public class GaussianBrush implements SpatialBrush {

	private double radius = 10.0;
	private double density = 100.0;
	private double baseIntensity = 1.0;
	private final Random random = new Random();

	/**
	 * Creates a Gaussian brush with default settings.
	 *
	 * <p>Default values:</p>
	 * <ul>
	 *   <li>Radius (sigma): 10.0</li>
	 *   <li>Density: 100.0 points/second</li>
	 *   <li>Base intensity: 1.0</li>
	 * </ul>
	 */
	public GaussianBrush() {
	}

	/**
	 * Creates a Gaussian brush with specified parameters.
	 *
	 * @param radius  the standard deviation (sigma) of the Gaussian distribution
	 * @param density the point density in points per second
	 */
	public GaussianBrush(double radius, double density) {
		this.radius = radius;
		this.density = density;
	}

	@Override
	public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
		List<SpatialValue<?>> values = new ArrayList<>();

		int pointCount = (int) Math.ceil(density * duration * pressure);

		for (int i = 0; i < pointCount; i++) {
			// Generate point using 3D Gaussian distribution
			// radius is used as sigma (standard deviation)
			double dx = random.nextGaussian() * radius;
			double dy = random.nextGaussian() * radius;
			double dz = random.nextGaussian() * radius * 0.5;

			double x = center.getX() + dx;
			double y = center.getY() + dy;
			double z = center.getZ() + dz;

			// Compute distance from center
			double distanceSquared = dx * dx + dy * dy + dz * dz;

			// Gaussian intensity falloff: exp(-d² / (2σ²))
			double gaussianFalloff = Math.exp(-distanceSquared / (2 * radius * radius));
			double intensity = baseIntensity * pressure * gaussianFalloff;

			// Temperature varies with distance for visual interest
			double distance = Math.sqrt(distanceSquared);
			double temperature = Math.min(1.0, distance / (radius * 2));

			values.add(new SpatialValue<>(
					new Vector(x, y, z),
					Math.log(intensity + 1),
					temperature,
					true
			));
		}

		return values;
	}

	@Override
	public double getRadius() { return radius; }

	@Override
	public void setRadius(double radius) { this.radius = radius; }

	@Override
	public double getDensity() { return density; }

	@Override
	public void setDensity(double density) { this.density = density; }

	/**
	 * Returns the base intensity multiplier.
	 *
	 * @return the base intensity
	 */
	public double getBaseIntensity() { return baseIntensity; }

	/**
	 * Sets the base intensity multiplier.
	 *
	 * @param baseIntensity the base intensity
	 */
	public void setBaseIntensity(double baseIntensity) {
		this.baseIntensity = baseIntensity;
	}
}
