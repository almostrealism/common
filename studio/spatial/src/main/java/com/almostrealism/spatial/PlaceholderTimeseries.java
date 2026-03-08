/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple placeholder implementation of {@link SpatialTimeseries} that generates
 * evenly-spaced points for visualization when actual frequency data is unavailable.
 *
 * <p>{@code PlaceholderTimeseries} is used as a fallback when the detailed frequency
 * analysis data (e.g., {@link SpatialWaveDetails}) cannot be loaded. It generates
 * a simple timeline representation with points every 0.25 seconds at a fixed
 * frequency position.</p>
 *
 * <h2>Usage</h2>
 * <p>Typically used internally by {@link GenomicTimeseries} when wave details are
 * unavailable:</p>
 * <pre>{@code
 * if (waveDetailsUnavailable) {
 *     double duration = audioProvider.getDuration();
 *     placeholder = new PlaceholderTimeseries(duration);
 * }
 * }</pre>
 *
 * @see SpatialTimeseries
 * @see GenomicTimeseries
 */
public class PlaceholderTimeseries implements SpatialTimeseries {
	private double seconds;

	/**
	 * Creates a placeholder timeseries with the specified duration.
	 *
	 * @param seconds the duration in seconds
	 */
	public PlaceholderTimeseries(double seconds) {
		this.seconds = seconds;
	}

	/**
	 * {@inheritDoc}
	 * <p>Returns {@code null} as placeholder has no associated resource.</p>
	 */
	@Override
	public String getKey() { return null; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Generates evenly-spaced placeholder points every 0.25 seconds at
	 * normalized frequency 0.5 (middle frequency). Each point has a fixed
	 * value of 1.5 and zero temperature.</p>
	 */
	@Override
	public List<SpatialValue> elements(TemporalSpatialContext context) {
		List<SpatialValue> elements = new ArrayList<>();

		for (double t = 0; t < seconds; t = t + 0.25) {
			double freq = 0.5;
			Vector position = context.position(
					context.getSecondsToTime().applyAsDouble(t),
					0, 0, freq);
			elements.add(new SpatialValue<>(
					position, 1.5, 0.0, false));
		}

		return elements;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the duration converted to the context's time units.</p>
	 */
	@Override
	public double getDuration(TemporalSpatialContext context) {
		return context.getSecondsToTime().applyAsDouble(seconds);
	}

	/**
	 * {@inheritDoc}
	 * <p>Always returns {@code false} as the placeholder always has content.</p>
	 */
	@Override
	public boolean isEmpty() { return false; }
}
