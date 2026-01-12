/*
 * Copyright 2023 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

/**
 * Callback interface for receiving selection events from a {@link SpatialDataHub}.
 *
 * <p>{@code SpatialSelectionListener} is notified when a user or system selects
 * a spatial timeseries. This is typically used to update detail views, property
 * panels, or other UI elements that display information about the selected item.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SpatialDataHub.getCurrent().addSelectionListener(new SpatialSelectionListener<FrequencyTimeseries>() {
 *     @Override
 *     public void selected(FrequencyTimeseries info) {
 *         // Update detail view with selected timeseries info
 *         detailPanel.setTimeseries(info);
 *     }
 * });
 * }</pre>
 *
 * @param <T> the type of timeseries this listener handles
 * @see SpatialDataHub
 * @see SpatialTimeseries
 */
public interface SpatialSelectionListener<T extends SpatialTimeseries> {

	/**
	 * Called when a spatial timeseries is selected.
	 *
	 * @param info the selected timeseries
	 */
	void selected(T info);
}
