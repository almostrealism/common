/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * Provides coordinate transformation and configuration for mapping temporal
 * and frequency-domain data into 3D spatial positions.
 *
 * <p>{@code TemporalSpatialContext} is the central configuration object for
 * the spatial visualization system. It defines how time, frequency, channel,
 * and layer information are mapped to X, Y, and Z coordinates respectively.</p>
 *
 * <h2>Coordinate System</h2>
 * <p>The mapping depends on the {@code spatialFrequency} setting:</p>
 *
 * <h3>When spatialFrequency is true (default):</h3>
 * <ul>
 *   <li><b>X-axis</b>: Time (scaled by temporal scale and zoom)</li>
 *   <li><b>Y-axis</b>: Frequency (spacing: 200 units)</li>
 *   <li><b>Z-axis</b>: Layer (spacing: {@link #LAYER_SPACING} = 40 units)</li>
 * </ul>
 *
 * <h3>When spatialFrequency is false:</h3>
 * <ul>
 *   <li><b>X-axis</b>: Time (scaled by temporal scale)</li>
 *   <li><b>Y-axis</b>: Channel (spacing: 10 units)</li>
 *   <li><b>Z-axis</b>: Layer (spacing: {@link #LAYER_SPACING} = 40 units)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TemporalSpatialContext context = new TemporalSpatialContext();
 * context.setDuration(10.0); // 10 seconds of audio
 *
 * // Convert time/frequency to 3D position
 * Vector pos = context.position(time, channel, layer, frequency);
 *
 * // Convert position back to seconds
 * double seconds = context.seconds(pos);
 * }</pre>
 *
 * @see SpatialTimeseries#elements(TemporalSpatialContext)
 * @see FrequencyTimeseries
 */
public class TemporalSpatialContext implements ConsoleFeatures {

	/**
	 * The vertical spacing between layers in the Z dimension.
	 * Default value is 40 units.
	 */
	public static int LAYER_SPACING = 40;

	/**
	 * The vertical spacing for frequency in spatial frequency mode.
	 * Default value is 200 units.
	 */
	public static int FREQUENCY_SPACING = 200;

	/**
	 * The maximum zoom factor applied to the time axis.
	 * Default value is 1000.
	 */
	public static double MAX_ZOOM = 1000;

	private boolean spatialFrequency;
	private boolean zoom;
	private double duration;
	private DoubleSupplier temporalScale;

	/**
	 * Creates a new context with default settings: spatial frequency mode enabled,
	 * zoom enabled, and temporal scale of 1.0.
	 */
	public TemporalSpatialContext() {
		this(() -> 1.0, true);
	}

	/**
	 * Creates a new context with the specified temporal scale and frequency mode.
	 *
	 * @param temporalScale    supplier for the time scaling factor
	 * @param spatialFrequency {@code true} to use frequency-based Y-axis mapping,
	 *                         {@code false} for channel-based mapping
	 */
	public TemporalSpatialContext(DoubleSupplier temporalScale, boolean spatialFrequency) {
		this.temporalScale = temporalScale;
		this.spatialFrequency = spatialFrequency;
		this.zoom = true;
	}

	/**
	 * Returns whether zoom is enabled for time axis scaling.
	 *
	 * @return {@code true} if zoom is enabled
	 */
	public boolean isZoom() {
		return zoom;
	}

	/**
	 * Enables or disables zoom for time axis scaling.
	 *
	 * <p>When zoom is enabled and duration is set, the time axis is scaled
	 * to fit the duration within a fixed visual width (340 units), up to
	 * {@link #MAX_ZOOM}.</p>
	 *
	 * @param zoom {@code true} to enable zoom
	 */
	public void setZoom(boolean zoom) {
		this.zoom = zoom;
	}

	/**
	 * Returns the total duration of the content being visualized.
	 *
	 * @return the duration in seconds
	 */
	public double getDuration() {
		return duration;
	}

	/**
	 * Sets the total duration of the content being visualized.
	 *
	 * <p>This affects the zoom calculation when zoom is enabled.
	 * Setting the duration will cause spatial elements to be regenerated
	 * on the next call to {@link SpatialTimeseries#elements(TemporalSpatialContext)}.</p>
	 *
	 * @param duration the duration in seconds
	 */
	public void setDuration(double duration) {
		this.duration = duration;
	}

	/**
	 * Returns a function that converts seconds to the internal time representation.
	 *
	 * <p>The conversion applies the temporal scale factor.</p>
	 *
	 * @return a function that multiplies seconds by the temporal scale
	 */
	public DoubleUnaryOperator getSecondsToTime() {
		return seconds -> seconds * temporalScale.getAsDouble();
	}

	/**
	 * Returns a function that converts internal time to seconds.
	 *
	 * <p>The conversion applies the inverse of the temporal scale factor.</p>
	 *
	 * @return a function that divides time by the temporal scale
	 */
	public DoubleUnaryOperator getTimeToSeconds() {
		return time -> time / temporalScale.getAsDouble();
	}

	/**
	 * Extracts the time in seconds from a spatial position.
	 *
	 * @param position the 3D position to extract time from
	 * @return the time in seconds corresponding to the X coordinate
	 */
	public double seconds(Vector position) {
		double pos = position.getX();
		return getTimeToSeconds().applyAsDouble(pos / getScale());
	}

	/**
	 * Converts temporal and frequency data to a 3D position using vertical layout.
	 *
	 * @param time      the time value (after applying seconds-to-time conversion)
	 * @param channel   the channel index
	 * @param layer     the layer index
	 * @param frequency the normalized frequency (0.0 to 1.0)
	 * @return the computed 3D position
	 */
	public Vector position(double time, double channel, double layer, double frequency) {
		return position(time, channel, layer, frequency, true);
	}

	/**
	 * Converts temporal and frequency data to a 3D position.
	 *
	 * <p>The mapping differs based on the {@code spatialFrequency} setting:</p>
	 * <ul>
	 *   <li>When {@code spatialFrequency} is true: Y = frequency * 200, Z = layer * 40</li>
	 *   <li>When {@code spatialFrequency} is false: Y = channel * 10, Z = layer * 40</li>
	 * </ul>
	 *
	 * <p>The {@code vertical} parameter affects the layout orientation:</p>
	 * <ul>
	 *   <li>When vertical: standard mapping as described above</li>
	 *   <li>When not vertical: swaps Y and Z roles for horizontal layout</li>
	 * </ul>
	 *
	 * @param time      the time value (after applying seconds-to-time conversion)
	 * @param channel   the channel index
	 * @param layer     the layer index
	 * @param frequency the normalized frequency (0.0 to 1.0)
	 * @param vertical  {@code true} for vertical layout, {@code false} for horizontal
	 * @return the computed 3D position
	 */
	public Vector position(double time, double channel, double layer, double frequency, boolean vertical) {
		double spacing;
		double y;
		double z;

		if (spatialFrequency) {
			y = frequency;
			z = layer;
			spacing = FREQUENCY_SPACING;
		} else {
			y = channel;
			z = layer;
			spacing = 10;
		}

		if (vertical) {
			return new Vector(time * getScale(), y * spacing, z * LAYER_SPACING);
		} else {
			return new Vector(time * getScale(), (z + 1) * spacing, (1 - y) * LAYER_SPACING);
		}
	}

	/**
	 * Computes the scale factor for the time (X) axis.
	 *
	 * <p>When in spatial frequency mode with zoom enabled and duration set,
	 * the scale is computed to fit the content within 340 units, capped at
	 * {@link #MAX_ZOOM}. Otherwise, a default scale of 3 is used.</p>
	 *
	 * @return the time axis scale factor
	 */
	protected double getScale() {
		if (spatialFrequency) {
			if (zoom && duration > 0) {
				return Math.min(340 / duration, MAX_ZOOM);
			} else {
				return 3;
			}
		}

		return 3;
	}

	/**
	 * Converts a 3D spatial position back to temporal coordinates.
	 *
	 * <p>This is the inverse of {@link #position(double, double, double, double)}.
	 * It extracts time, frequency, and layer information from a spatial position.</p>
	 *
	 * <p>Note: Channel information cannot be reliably recovered in spatial frequency
	 * mode, so it is always returned as 0.</p>
	 *
	 * @param position the 3D position to convert
	 * @return the temporal coordinates extracted from the position
	 * @see #position(double, double, double, double)
	 */
	public TemporalCoordinates inverse(Vector position) {
		double x = position.getX();
		double y = position.getY();
		double z = position.getZ();

		double time = getTimeToSeconds().applyAsDouble(x / getScale());

		double frequency;
		int layer;

		if (spatialFrequency) {
			frequency = y / FREQUENCY_SPACING;
			layer = (int) Math.round(z / LAYER_SPACING);
		} else {
			// Channel mode - y represents channel, frequency not applicable
			frequency = 0.0;
			layer = (int) Math.round(z / LAYER_SPACING);
		}

		return new TemporalCoordinates(time, 0, layer, frequency, 0.0);
	}

	/**
	 * Represents temporal coordinates extracted from a spatial position.
	 *
	 * <p>This record holds the inverse mapping results from
	 * {@link TemporalSpatialContext#inverse(Vector)}, converting 3D spatial
	 * positions back to their temporal and frequency-domain representation.</p>
	 *
	 * @param time      the time in seconds
	 * @param channel   the audio channel index (always 0 in current implementation)
	 * @param layer     the layer index
	 * @param frequency the normalized frequency (0.0 to 1.0)
	 * @param intensity the value/magnitude (reserved for future use)
	 */
	public record TemporalCoordinates(
			double time,
			int channel,
			int layer,
			double frequency,
			double intensity
	) {}
}
