/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import java.util.List;

/**
 * A spatial timeseries that supports modification through spatial input.
 *
 * <p>This interface extends {@link SpatialTimeseries} to add write capabilities
 * for brush-based drawing. Implementations should modify their underlying
 * frequency data when {@link #applyValues} is called.</p>
 *
 * <p>The frequency data can be accessed via the inherited {@code getSeries(layer)}
 * method from {@link FrequencyTimeseries}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EditableSpatialTimeseries canvas = new EditableSpatialWaveDetails(
 *     timeFrames, frequencyBins, sampleRate, freqSampleRate);
 *
 * // Apply brush strokes
 * List<SpatialValue<?>> brushValues = brush.stroke(position, pressure, duration);
 * canvas.applyValues(brushValues, context);
 *
 * // Check if modified
 * if (canvas.isModified()) {
 *     // Process the frequency data via getSeries()
 * }
 *
 * // Clear to start over
 * canvas.clear();
 * }</pre>
 *
 * @see SpatialTimeseries
 * @see EditableSpatialWaveDetails
 * @see SpatialBrush
 */
public interface EditableSpatialTimeseries extends SpatialTimeseries {

	/**
	 * Applies spatial values to modify the underlying data.
	 *
	 * <p>Each spatial value's position is converted to temporal coordinates
	 * using the context's {@link TemporalSpatialContext#inverse(org.almostrealism.algebra.Vector)}
	 * method, and the value is written to the corresponding location in the
	 * frequency data.</p>
	 *
	 * @param values  the spatial values to apply (typically from brush strokes)
	 * @param context the coordinate mapping context for position conversion
	 */
	void applyValues(List<SpatialValue<?>> values, TemporalSpatialContext context);

	/**
	 * Clears all drawn data, resetting to an empty state.
	 *
	 * <p>After calling this method, {@link #isModified()} will return {@code false}
	 * and the frequency data will be zeroed.</p>
	 */
	void clear();

	/**
	 * Returns whether this timeseries has been modified since creation or last clear.
	 *
	 * @return {@code true} if {@link #applyValues} has been called since creation
	 *         or the last call to {@link #clear()}
	 */
	boolean isModified();
}
