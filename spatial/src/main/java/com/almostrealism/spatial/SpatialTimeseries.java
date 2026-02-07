/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import java.util.List;

/**
 * Interface for time-series data that can be converted to spatial representations
 * for visualization.
 *
 * <p>{@code SpatialTimeseries} is the core abstraction for any data that varies
 * over time and can be visualized in 3D space. Implementations convert their
 * internal data representation (e.g., frequency spectra, waveforms, or placeholder
 * data) into a list of {@link SpatialValue} objects positioned in 3D space.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link FrequencyTimeseries} - Frequency-domain data visualization</li>
 *   <li>{@link PlaceholderTimeseries} - Simple placeholder when actual data is unavailable</li>
 *   <li>{@link GenomicTimeseries} - ML model outputs with genetic parameters</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * SpatialTimeseries timeseries = ...;
 * TemporalSpatialContext context = new TemporalSpatialContext();
 * context.setDuration(timeseries.getDuration(context));
 *
 * // Get spatial representation for visualization
 * List<SpatialValue> elements = timeseries.elements(context);
 * for (SpatialValue value : elements) {
 *     Vector pos = value.getPosition();
 *     double magnitude = value.getValue();
 *     // Render the point...
 * }
 * }</pre>
 *
 * @see SpatialValue
 * @see TemporalSpatialContext
 * @see FrequencyTimeseries
 * @see SpatialData
 */
public interface SpatialTimeseries {

	/**
	 * Returns a unique identifier for this timeseries.
	 *
	 * <p>The key is typically a file path, resource identifier, or unique name
	 * that distinguishes this timeseries from others.</p>
	 *
	 * @return the unique key, or {@code null} if not applicable
	 */
	String getKey();

	/**
	 * Generates the spatial representation of this timeseries.
	 *
	 * <p>This method converts the internal time-series data into a list of
	 * {@link SpatialValue} objects, each positioned in 3D space according to
	 * the provided context. The context determines how time, frequency, and
	 * other dimensions are mapped to spatial coordinates.</p>
	 *
	 * <p>Results may be cached internally. Changes to the context's duration
	 * will typically trigger regeneration of the elements.</p>
	 *
	 * @param context the temporal-spatial context defining coordinate mapping
	 * @return a list of spatial values representing this timeseries, or {@code null}
	 *         if the data cannot be converted
	 */
	List<SpatialValue> elements(TemporalSpatialContext context);

	/**
	 * Returns the duration of this timeseries in the context's time units.
	 *
	 * <p>The duration is converted using the context's seconds-to-time
	 * transformation.</p>
	 *
	 * @param context the temporal-spatial context for time conversion
	 * @return the duration in the context's time units
	 */
	double getDuration(TemporalSpatialContext context);

	/**
	 * Returns whether this timeseries contains no data.
	 *
	 * @return {@code true} if this timeseries is empty or has no meaningful data
	 */
	boolean isEmpty();
}
