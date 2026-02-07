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
 * A brush that creates points at harmonic frequencies of the center position.
 *
 * <p>When drawing at a given frequency (Y position), this brush generates
 * additional points at harmonic multiples (2x, 3x, 4x, etc.) of that frequency.
 * This creates harmonically rich content useful for synthesizing musical tones.</p>
 *
 * <h2>Harmonic Series</h2>
 * <p>The brush generates points at:</p>
 * <ul>
 *   <li>Fundamental frequency (Y position of center)</li>
 *   <li>2nd harmonic (2 * Y)</li>
 *   <li>3rd harmonic (3 * Y)</li>
 *   <li>... up to harmonicCount harmonics</li>
 * </ul>
 *
 * <h2>Intensity Rolloff</h2>
 * <p>Higher harmonics have reduced intensity according to the rolloff parameter.
 * For each harmonic n, the intensity is multiplied by {@code rolloff^(n-1)}.
 * A rolloff of 0.5 means each harmonic is half the intensity of the previous.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Drawing pitched tones with natural harmonic content</li>
 *   <li>Creating organ or bell-like timbres</li>
 *   <li>Adding harmonic richness to simple frequency bands</li>
 * </ul>
 *
 * @see SpatialBrush
 * @see FrequencyBandBrush
 */
public class HarmonicBrush implements SpatialBrush {

	private double radius = 10.0;
	private double density = 100.0;
	private int harmonicCount = 6;
	private double rolloff = 0.7;
	private double baseIntensity = 1.0;
	private final Random random = new Random();

	/**
	 * Creates a harmonic brush with default settings.
	 *
	 * <p>Default values:</p>
	 * <ul>
	 *   <li>Radius: 10.0</li>
	 *   <li>Density: 100.0 points/second</li>
	 *   <li>Harmonic count: 6</li>
	 *   <li>Rolloff: 0.7 (each harmonic is 70% of previous)</li>
	 *   <li>Base intensity: 1.0</li>
	 * </ul>
	 */
	public HarmonicBrush() {
	}

	/**
	 * Creates a harmonic brush with specified parameters.
	 *
	 * @param radius        the radius for each harmonic cluster
	 * @param density       the point density in points per second
	 * @param harmonicCount the number of harmonics to generate
	 * @param rolloff       intensity multiplier per harmonic (0.0-1.0)
	 */
	public HarmonicBrush(double radius, double density, int harmonicCount, double rolloff) {
		this.radius = radius;
		this.density = density;
		this.harmonicCount = harmonicCount;
		this.rolloff = rolloff;
	}

	@Override
	public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
		List<SpatialValue<?>> values = new ArrayList<>();

		int totalPoints = (int) Math.ceil(density * duration * pressure);
		int pointsPerHarmonic = Math.max(1, totalPoints / harmonicCount);

		double fundamentalY = center.getY();

		for (int h = 1; h <= harmonicCount; h++) {
			double harmonicY = fundamentalY * h;

			// Intensity rolls off for higher harmonics
			double harmonicIntensity = baseIntensity * Math.pow(rolloff, h - 1);

			for (int i = 0; i < pointsPerHarmonic; i++) {
				// Small random displacement around harmonic position
				double r = radius * Math.cbrt(random.nextDouble());
				double theta = random.nextDouble() * 2 * Math.PI;
				double phi = Math.acos(2 * random.nextDouble() - 1);

				double x = center.getX() + r * Math.sin(phi) * Math.cos(theta);
				double y = harmonicY + r * Math.sin(phi) * Math.sin(theta) * 0.3;
				double z = center.getZ() + r * Math.cos(phi) * 0.5;

				double falloff = 1.0 - (r / radius);
				double intensity = harmonicIntensity * pressure * falloff;

				// Temperature varies by harmonic number for visual distinction
				double temperature = (double) h / harmonicCount;

				values.add(new SpatialValue<>(
						new Vector(x, y, z),
						Math.log(intensity + 1),
						temperature,
						true
				));
			}
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
	 * Returns the number of harmonics generated.
	 *
	 * @return the harmonic count
	 */
	public int getHarmonicCount() { return harmonicCount; }

	/**
	 * Sets the number of harmonics to generate.
	 *
	 * @param harmonicCount the harmonic count (1 or greater)
	 */
	public void setHarmonicCount(int harmonicCount) {
		this.harmonicCount = Math.max(1, harmonicCount);
	}

	/**
	 * Returns the intensity rolloff factor per harmonic.
	 *
	 * @return the rolloff factor (0.0-1.0)
	 */
	public double getRolloff() { return rolloff; }

	/**
	 * Sets the intensity rolloff factor per harmonic.
	 *
	 * <p>A value of 1.0 means all harmonics have equal intensity.
	 * A value of 0.5 means each harmonic is half the intensity of the previous.</p>
	 *
	 * @param rolloff the rolloff factor (0.0-1.0)
	 */
	public void setRolloff(double rolloff) {
		this.rolloff = Math.max(0, Math.min(1, rolloff));
	}

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
