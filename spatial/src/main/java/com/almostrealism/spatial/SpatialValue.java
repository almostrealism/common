/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

/**
 * A spatial element that carries a numeric value and associated metadata
 * for visualization.
 *
 * <p>{@code SpatialValue} extends {@link SpatialElement} to represent individual
 * data points in a frequency/time visualization. Each spatial value has:</p>
 * <ul>
 *   <li>A 3D position (inherited from {@link SpatialElement})</li>
 *   <li>A numeric value (typically log-scaled magnitude)</li>
 *   <li>A temperature (used for color mapping, often represents frequency)</li>
 *   <li>Rendering hints (dot vs. other shapes, alternative visualization)</li>
 *   <li>A back-reference to the source timeseries</li>
 * </ul>
 *
 * <p>Spatial values are typically created by {@link FrequencyTimeseries#elements(TemporalSpatialContext)}
 * when converting frequency domain data into spatial representations for visualization.</p>
 *
 * @param <T> the type of the referent object (typically a {@link SpatialTimeseries} subclass)
 * @see SpatialElement
 * @see FrequencyTimeseries
 * @see TemporalSpatialContext
 */
public class SpatialValue<T> extends SpatialElement {
	private double value;
	private double temperature;
	private int index;
	private boolean alt;
	private boolean dot;
	private T referent;

	/**
	 * Creates a new spatial value with the specified position and value.
	 *
	 * @param position the 3D position of this value
	 * @param value    the numeric value at this position
	 */
	public SpatialValue(Vector position, double value) {
		this(position, value, 0.0, false);
	}

	/**
	 * Creates a new spatial value with position, value, temperature, and dot flag.
	 *
	 * @param position    the 3D position of this value
	 * @param value       the numeric value at this position
	 * @param temperature the temperature value (used for color mapping, typically 1.0 - frequency)
	 * @param dot         whether this value should be rendered as a dot
	 */
	public SpatialValue(Vector position, double value, double temperature, boolean dot) {
		this(position, value, temperature, dot, false);
	}

	/**
	 * Creates a new spatial value with all properties.
	 *
	 * @param position    the 3D position of this value
	 * @param value       the numeric value at this position
	 * @param temperature the temperature value (used for color mapping)
	 * @param dot         whether this value should be rendered as a dot
	 * @param alt         whether to use alternative visualization mode
	 */
	public SpatialValue(Vector position, double value, double temperature, boolean dot, boolean alt) {
		super(position);
		this.value = value;
		this.temperature = temperature;
		this.index = -1;
		this.dot = dot;
		this.alt = alt;
	}

	/**
	 * Returns the numeric value at this spatial position.
	 *
	 * @return the value, typically a log-scaled magnitude
	 */
	public double getValue() { return value; }

	/**
	 * Sets the numeric value at this spatial position.
	 *
	 * @param value the new value
	 */
	public void setValue(double value) { this.value = value; }

	/**
	 * Returns the temperature value used for color mapping.
	 *
	 * <p>In frequency visualization, this is typically computed as
	 * {@code 1.0 - frequency} where frequency is normalized to [0, 1].</p>
	 *
	 * @return the temperature value
	 */
	public double getTemperature() { return temperature; }

	/**
	 * Sets the temperature value used for color mapping.
	 *
	 * @param temperature the new temperature value
	 */
	public void setTemperature(double temperature) { this.temperature = temperature; }

	/**
	 * Returns the index of this value within its source data.
	 *
	 * @return the index, or -1 if not set
	 */
	public int getIndex() { return index; }

	/**
	 * Sets the index of this value within its source data.
	 *
	 * @param index the index
	 */
	public void setIndex(int index) { this.index = index; }

	/**
	 * Returns whether this value uses alternative visualization mode.
	 *
	 * <p>Alternative mode is typically used for aggregated frequency quartile
	 * data points that are displayed with different positioning.</p>
	 *
	 * @return {@code true} if using alternative visualization
	 */
	public boolean isAlt() { return alt; }

	/**
	 * Sets whether this value uses alternative visualization mode.
	 *
	 * @param alt {@code true} to use alternative visualization
	 */
	public void setAlt(boolean alt) { this.alt = alt; }

	/**
	 * Returns whether this value should be rendered as a dot.
	 *
	 * @return {@code true} if this value should be rendered as a dot
	 */
	public boolean isDot() {
		return dot;
	}

	/**
	 * Sets whether this value should be rendered as a dot.
	 *
	 * @param dot {@code true} to render as a dot
	 */
	public void setDot(boolean dot) {
		this.dot = dot;
	}

	/**
	 * Returns the referent object that this spatial value originated from.
	 *
	 * <p>This is typically set to the {@link SpatialTimeseries} that generated
	 * this value, allowing navigation back to the source data.</p>
	 *
	 * @return the referent object, or {@code null} if not set
	 */
	public T getReferent() { return referent; }

	/**
	 * Sets the referent object that this spatial value originated from.
	 *
	 * @param referent the referent object
	 */
	public void setReferent(T referent) { this.referent = referent; }
}
