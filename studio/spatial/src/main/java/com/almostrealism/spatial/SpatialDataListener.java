/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

/**
 * Callback interface for receiving spatial timeseries data events from a
 * {@link SpatialDataHub}.
 *
 * <p>{@code SpatialDataListener} is notified when timeseries data is published
 * or when a time-based scan operation occurs. Implementations typically update
 * visualizations or perform processing on the received data.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SpatialDataHub.getCurrent().addDataListener(new SpatialDataListener<FrequencyTimeseries>() {
 *     @Override
 *     public void published(SpatialData<FrequencyTimeseries> data) {
 *         int channel = data.getIndex();
 *         FrequencyTimeseries ts = data.getTimeseries();
 *         // Update visualization for channel...
 *     }
 *
 *     @Override
 *     public void scan(double time) {
 *         // Move playhead to time position...
 *     }
 * });
 * }</pre>
 *
 * @param <T> the type of timeseries this listener handles
 * @see SpatialDataHub
 * @see SpatialData
 * @see SpatialTimeseries
 */
public interface SpatialDataListener<T extends SpatialTimeseries> {

	/**
	 * Called when spatial data is published to the hub.
	 *
	 * @param data the published data containing an index and timeseries
	 */
	void published(SpatialData<T> data);

	/**
	 * Called when a time-based scan operation is requested.
	 *
	 * <p>This is typically used to synchronize visualizations with a playhead
	 * position. The default implementation does nothing.</p>
	 *
	 * @param time the time position to scan to
	 */
	default void scan(double time) { }
}
