/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

import java.util.List;

/**
 * A brush that generates spatial values around a center point.
 *
 * <p>Brushes are used to create spatial points during drawing operations.
 * The {@link #stroke} method generates points within the brush's area,
 * scaled by the duration parameter to ensure consistent density regardless
 * of how frequently the method is called.</p>
 *
 * <h2>Duration Parameter</h2>
 * <p>The duration parameter in {@link #stroke} represents the time elapsed
 * since the last stroke call. Implementations should scale the number of
 * generated points proportionally to this duration. For example, if the
 * brush has a density of 100 points/second and duration is 0.016 seconds
 * (approximately 60fps), the brush should generate about 1-2 points.</p>
 *
 * <h2>Implementation Example</h2>
 * <pre>{@code
 * public class MyBrush implements SpatialBrush {
 *     private double radius = 10.0;
 *     private double density = 100.0; // points per second
 *
 *     public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
 *         int pointCount = (int) Math.ceil(density * duration * pressure);
 *         List<SpatialValue<?>> values = new ArrayList<>();
 *         for (int i = 0; i < pointCount; i++) {
 *             // Generate point within brush area
 *             values.add(generatePoint(center, pressure));
 *         }
 *         return values;
 *     }
 * }
 * }</pre>
 *
 * @see SpatialValue
 * @see EditableSpatialTimeseries#applyValues(List, TemporalSpatialContext)
 * @see SphericalBrush
 */
public interface SpatialBrush {

	/**
	 * Generates spatial values for a brush stroke at the given position.
	 *
	 * <p>The number of generated points should be proportional to {@code duration}
	 * to ensure consistent density regardless of call frequency.</p>
	 *
	 * @param center   the center position of the brush stroke
	 * @param pressure the input pressure (0.0-1.0), affects intensity and point count
	 * @param duration the time duration of this stroke segment in seconds
	 * @return list of generated spatial values, never null
	 */
	List<SpatialValue<?>> stroke(Vector center, double pressure, double duration);

	/**
	 * Returns the brush radius in spatial units.
	 *
	 * @return the brush radius
	 */
	double getRadius();

	/**
	 * Sets the brush radius.
	 *
	 * @param radius the brush radius in spatial units
	 */
	void setRadius(double radius);

	/**
	 * Returns the point density (points per second at pressure 1.0).
	 *
	 * @return the density in points per second
	 */
	double getDensity();

	/**
	 * Sets the point density.
	 *
	 * @param density the density in points per second
	 */
	void setDensity(double density);

	/**
	 * Resets brush state for a new stroke.
	 *
	 * <p>Called when a new stroke begins (mouse down) to allow stateful
	 * brushes to reset their internal tracking. Default implementation
	 * does nothing, preserving backward compatibility for stateless brushes.</p>
	 *
	 * <p>Stateful brushes like {@link SampleBrush} use this to clear their
	 * stroke start position and painted frame tracking.</p>
	 */
	default void reset() {}
}
