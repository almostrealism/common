/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial.series;

/**
 * A timeseries representing recorded audio that can be organized hierarchically.
 *
 * <p>{@code RecordedTimeseries} extends {@link SimpleTimeseries} to represent
 * captured or recorded audio samples. It adds a {@code group} flag to distinguish
 * between individual recordings and container nodes in the tree hierarchy.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a group container
 * RecordedTimeseries drumKit = new RecordedTimeseries("drums", true);
 *
 * // Add individual recordings
 * RecordedTimeseries kick = new RecordedTimeseries("kick");
 * RecordedTimeseries snare = new RecordedTimeseries("snare");
 * drumKit.getChildren().add(kick);
 * drumKit.getChildren().add(snare);
 * }</pre>
 *
 * @see SimpleTimeseries
 * @see SoundDataTimeseries
 */
public class RecordedTimeseries extends SimpleTimeseries<RecordedTimeseries> {
	private boolean group;

	/**
	 * Creates an empty recorded timeseries.
	 */
	public RecordedTimeseries() { }

	/**
	 * Creates a recorded timeseries with the specified key.
	 *
	 * @param key the unique identifier
	 */
	public RecordedTimeseries(String key) {
		this(key, false);
	}

	/**
	 * Creates a recorded timeseries with the specified key and group flag.
	 *
	 * @param key   the unique identifier
	 * @param group {@code true} if this is a container group, {@code false} for individual recording
	 */
	public RecordedTimeseries(String key, boolean group) {
		super(key);
		this.group = group;
	}

	/**
	 * Returns whether this timeseries is a group container.
	 *
	 * @return {@code true} if this is a group, {@code false} if individual recording
	 */
	public boolean isGroup() {
		return group;
	}

	/**
	 * Sets whether this timeseries is a group container.
	 *
	 * @param group {@code true} for a group, {@code false} for individual recording
	 */
	public void setGroup(boolean group) {
		this.group = group;
	}
}
