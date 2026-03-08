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
 * A brush that creates horizontal bands at the specified frequency level.
 *
 * <p>Points are distributed along the X-axis (time) while staying close to
 * the Y-axis (frequency) position of the brush center. This creates horizontal
 * frequency bands useful for drawing sustained tones or drones.</p>
 *
 * <h2>Band Shape</h2>
 * <p>The brush generates points in an elliptical region with:</p>
 * <ul>
 *   <li>Wide horizontal spread (time axis) - controlled by radius</li>
 *   <li>Narrow vertical spread (frequency axis) - controlled by bandwidth</li>
 *   <li>Minimal Z spread (stays near layer 0)</li>
 * </ul>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Drawing sustained tones at specific frequencies</li>
 *   <li>Creating drone or pad-like textures</li>
 *   <li>Adding harmonic content at precise frequency levels</li>
 * </ul>
 *
 * @see SpatialBrush
 * @see SphericalBrush
 */
public class FrequencyBandBrush implements SpatialBrush {

	private double radius = 20.0;
	private double density = 100.0;
	private double bandwidth = 2.0;
	private double baseIntensity = 1.0;
	private final Random random = new Random();

	/**
	 * Creates a frequency band brush with default settings.
	 *
	 * <p>Default values:</p>
	 * <ul>
	 *   <li>Radius: 20.0 (horizontal spread)</li>
	 *   <li>Density: 100.0 points/second</li>
	 *   <li>Bandwidth: 2.0 (vertical spread)</li>
	 *   <li>Base intensity: 1.0</li>
	 * </ul>
	 */
	public FrequencyBandBrush() {
	}

	/**
	 * Creates a frequency band brush with specified parameters.
	 *
	 * @param radius    the horizontal spread (time axis)
	 * @param density   the point density in points per second
	 * @param bandwidth the vertical spread (frequency axis)
	 */
	public FrequencyBandBrush(double radius, double density, double bandwidth) {
		this.radius = radius;
		this.density = density;
		this.bandwidth = bandwidth;
	}

	@Override
	public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
		List<SpatialValue<?>> values = new ArrayList<>();

		int pointCount = (int) Math.ceil(density * duration * pressure);

		for (int i = 0; i < pointCount; i++) {
			// Wide horizontal spread along time axis
			double dx = (random.nextDouble() * 2 - 1) * radius;

			// Narrow vertical spread using Gaussian distribution for natural falloff
			double dy = random.nextGaussian() * bandwidth * 0.5;

			// Minimal Z variation (stays near layer 0)
			double dz = random.nextGaussian() * 2.0;

			double x = center.getX() + dx;
			double y = center.getY() + dy;
			double z = center.getZ() + dz;

			// Intensity based on distance from center line
			double horizontalDist = Math.abs(dx) / radius;
			double verticalDist = Math.abs(dy) / bandwidth;
			double falloff = (1.0 - horizontalDist) * Math.exp(-verticalDist * verticalDist);
			double intensity = baseIntensity * pressure * falloff;

			// Temperature based on vertical position for color variation
			double temperature = 0.5 + dy / (bandwidth * 2);
			temperature = Math.max(0, Math.min(1, temperature));

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
	 * Returns the bandwidth (vertical frequency spread).
	 *
	 * @return the bandwidth
	 */
	public double getBandwidth() { return bandwidth; }

	/**
	 * Sets the bandwidth (vertical frequency spread).
	 *
	 * @param bandwidth the bandwidth
	 */
	public void setBandwidth(double bandwidth) { this.bandwidth = bandwidth; }

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
