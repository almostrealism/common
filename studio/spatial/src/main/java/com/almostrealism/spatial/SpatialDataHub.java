/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import java.util.ArrayList;
import java.util.List;

/**
 * A publish/subscribe hub for spatial timeseries data and selection events.
 *
 * <p>{@code SpatialDataHub} implements the Observer pattern to coordinate
 * communication between spatial data producers and consumers. It manages
 * two types of listeners:</p>
 * <ul>
 *   <li>{@link SpatialDataListener} - Notified when timeseries data is published or scanned</li>
 *   <li>{@link SpatialSelectionListener} - Notified when a timeseries is selected</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // Register listener
 * SpatialDataHub hub = SpatialDataHub.getCurrent();
 * hub.addDataListener(new SpatialDataListener<FrequencyTimeseries>() {
 *     @Override
 *     public void published(SpatialData<FrequencyTimeseries> data) {
 *         // Handle new data
 *     }
 * });
 *
 * // Publish data
 * hub.publish(new SpatialData<>(0, myTimeseries));
 *
 * // Notify selection
 * hub.selected(myTimeseries);
 * }</pre>
 *
 * <p>A singleton instance is available via {@link #getCurrent()}, but custom
 * instances can also be created for isolated pub/sub contexts.</p>
 *
 * @see SpatialData
 * @see SpatialDataListener
 * @see SpatialSelectionListener
 * @see SpatialTimeseries
 */
public class SpatialDataHub {
	private static SpatialDataHub current;

	private List<SpatialDataListener> dataListeners;
	private List<SpatialSelectionListener> selectionListeners;

	/**
	 * Creates a new spatial data hub with empty listener lists.
	 */
	public SpatialDataHub() {
		this.dataListeners = new ArrayList<>();
		this.selectionListeners = new ArrayList<>();
	}

	/**
	 * Registers a listener to receive data publication and scan events.
	 *
	 * @param listener the listener to add
	 */
	public void addDataListener(SpatialDataListener<?> listener) {
		this.dataListeners.add(listener);
	}

	/**
	 * Removes a previously registered data listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeDataListener(SpatialDataListener<?> listener) {
		this.dataListeners.remove(listener);
	}

	/**
	 * Registers a listener to receive selection events.
	 *
	 * @param listener the listener to add
	 */
	public void addSelectionListener(SpatialSelectionListener<?> listener) {
		this.selectionListeners.add(listener);
	}

	/**
	 * Removes a previously registered selection listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeSelectionListener(SpatialSelectionListener<?> listener) {
		this.selectionListeners.remove(listener);
	}

	/**
	 * Publishes spatial data to all registered data listeners.
	 *
	 * <p>Each registered {@link SpatialDataListener} will have its
	 * {@link SpatialDataListener#published(SpatialData)} method called
	 * with the provided data.</p>
	 *
	 * @param <T>  the type of timeseries in the data
	 * @param data the spatial data to publish
	 */
	public <T extends SpatialTimeseries> void publish(SpatialData<T> data) {
		dataListeners.forEach(l -> l.published(data));
	}

	/**
	 * Removes (unpublishes) data from the specified channel by publishing
	 * a {@link SpatialData} with null timeseries.
	 *
	 * <p>Listeners receiving this event should interpret a null timeseries
	 * as a signal to clear any data associated with that channel index.</p>
	 *
	 * @param index the channel index to unpublish
	 */
	public void unpublish(int index) {
		publish(new SpatialData<>(index, null));
	}

	/**
	 * Notifies all data listeners to scan at the specified time.
	 *
	 * <p>This is typically used for time-based navigation or playhead positioning.</p>
	 *
	 * @param time the time position to scan to
	 */
	public void scan(double time) {
		dataListeners.forEach(l -> l.scan(time));
	}

	/**
	 * Notifies all selection listeners that a timeseries has been selected.
	 *
	 * <p>Exceptions from listeners are caught and printed to avoid
	 * interrupting notification of other listeners.</p>
	 *
	 * @param <T>  the type of timeseries selected
	 * @param info the selected timeseries
	 */
	public <T extends SpatialTimeseries> void selected(T info) {
		selectionListeners.forEach(l -> {
			try {
				l.selected(info);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Returns the singleton instance of the spatial data hub.
	 *
	 * <p>Creates the instance on first access.</p>
	 *
	 * @return the singleton hub instance
	 */
	public static SpatialDataHub getCurrent() {
		if (current == null) current = new SpatialDataHub();
		return current;
	}
}
