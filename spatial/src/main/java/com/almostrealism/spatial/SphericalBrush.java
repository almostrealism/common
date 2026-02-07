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
 * A spherical brush that generates random points within a sphere.
 *
 * <p>Points are distributed randomly within a sphere centered at the
 * brush position using uniform spherical distribution. Intensity falls
 * off linearly from center to edge, creating a soft brush effect.</p>
 *
 * <h2>Point Distribution</h2>
 * <p>The number of points generated per stroke is determined by:</p>
 * <pre>
 * pointCount = ceil(density * duration * pressure)
 * </pre>
 *
 * <h2>Intensity Falloff</h2>
 * <p>Each point's intensity is computed as:</p>
 * <pre>
 * intensity = baseIntensity * pressure * (1.0 - distance/radius)
 * </pre>
 * <p>This creates a smooth falloff from full intensity at the center
 * to zero at the brush edge.</p>
 *
 * @see SpatialBrush
 * @see SpatialValue
 */
public class SphericalBrush implements SpatialBrush {

	private double radius = 10.0;
	private double density = 100.0;  // Points per second
	private double baseIntensity = 1.0;
	private final Random random = new Random();

	/**
	 * Creates a spherical brush with default settings.
	 *
	 * <p>Default values:</p>
	 * <ul>
	 *   <li>Radius: 10.0</li>
	 *   <li>Density: 100.0 points/second</li>
	 *   <li>Base intensity: 1.0</li>
	 * </ul>
	 */
	public SphericalBrush() {
	}

	/**
	 * Creates a spherical brush with the specified radius and density.
	 *
	 * @param radius  the brush radius in spatial units
	 * @param density the point density in points per second
	 */
	public SphericalBrush(double radius, double density) {
		this.radius = radius;
		this.density = density;
	}

	@Override
	public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
		List<SpatialValue<?>> values = new ArrayList<>();

		// Calculate number of points based on density, duration, and pressure
		int pointCount = (int) Math.ceil(density * duration * pressure);

		for (int i = 0; i < pointCount; i++) {
			// Generate random point within sphere using uniform spherical distribution
			// Using cube root for uniform volume distribution
			double r = radius * Math.cbrt(random.nextDouble());
			double theta = random.nextDouble() * 2 * Math.PI;
			double phi = Math.acos(2 * random.nextDouble() - 1);

			double x = center.getX() + r * Math.sin(phi) * Math.cos(theta);
			double y = center.getY() + r * Math.sin(phi) * Math.sin(theta);
			double z = center.getZ() + r * Math.cos(phi);

			// Intensity falls off linearly from center to edge
			double falloff = 1.0 - (r / radius);
			double intensity = baseIntensity * pressure * falloff;

			// Random temperature for color variation
			double temperature = random.nextDouble();

			values.add(new SpatialValue<>(
					new Vector(x, y, z),
					Math.log(intensity + 1),  // Log scaling consistent with FrequencyTimeseries
					temperature,
					true  // Render as dot
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
	 * @param baseIntensity the base intensity (typically 0.0 to 1.0)
	 */
	public void setBaseIntensity(double baseIntensity) {
		this.baseIntensity = baseIntensity;
	}
}
