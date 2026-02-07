/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.almostrealism.spatial;

/**
 * A container that pairs an index with a {@link SpatialTimeseries} instance.
 *
 * <p>{@code SpatialData} is used as the event payload in the {@link SpatialDataHub}
 * publish/subscribe system. It associates a channel or slot index with the timeseries
 * data that has been published to that slot.</p>
 *
 * <h2>Usage with SpatialDataHub</h2>
 * <pre>{@code
 * SpatialTimeseries timeseries = ...;
 * SpatialData<SpatialTimeseries> data = new SpatialData<>(0, timeseries);
 *
 * // Publish to hub
 * SpatialDataHub.getCurrent().publish(data);
 * }</pre>
 *
 * @param <T> the type of timeseries contained, must extend {@link SpatialTimeseries}
 * @see SpatialTimeseries
 * @see SpatialDataHub
 * @see SpatialDataListener
 */
public class SpatialData<T extends SpatialTimeseries> {
	private int index;
	private T timeseries;

	/**
	 * Creates an empty spatial data container.
	 */
	public SpatialData() { }

	/**
	 * Creates a spatial data container with the specified index and timeseries.
	 *
	 * @param index      the channel or slot index
	 * @param timeseries the timeseries data
	 */
	public SpatialData(int index, T timeseries) {
		this.index = index;
		this.timeseries = timeseries;
	}

	/**
	 * Returns the channel or slot index for this data.
	 *
	 * @return the index
	 */
	public int getIndex() { return index; }

	/**
	 * Sets the channel or slot index for this data.
	 *
	 * @param index the index
	 */
	public void setIndex(int index) { this.index = index; }

	/**
	 * Returns the key from the contained timeseries.
	 *
	 * @return the timeseries key, or {@code null} if no timeseries is set
	 */
	public String getKey() { return timeseries == null ? null : timeseries.getKey(); }

	/**
	 * Returns the contained timeseries.
	 *
	 * @return the timeseries, or {@code null} if not set
	 */
	public T getTimeseries() { return timeseries; }

	/**
	 * Sets the contained timeseries.
	 *
	 * @param timeseries the timeseries
	 */
	public void setTimeseries(T timeseries) { this.timeseries = timeseries; }
}
