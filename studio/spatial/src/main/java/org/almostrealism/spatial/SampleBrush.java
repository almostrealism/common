/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package org.almostrealism.spatial;

import org.almostrealism.algebra.Vector;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A brush that paints frequency data from a source audio sample onto the canvas.
 *
 * <p>This brush implements {@link SpatialBrush} and generates {@link SpatialValue}
 * objects from source frequency data. The first stroke position establishes the
 * canvas start point; subsequent strokes extend the painted region by reading
 * more frames from the source.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SampleBrush brush = new SampleBrush();
 * brush.setSource(waveDetails);  // Must have freqData
 *
 * // On stroke start
 * brush.reset();
 *
 * // During drag
 * List<SpatialValue<?>> values = brush.stroke(position, pressure, duration);
 * canvas.applyValues(values, context);
 * }</pre>
 *
 * <h2>State Tracking</h2>
 * <p>The brush maintains internal state to track the stroke start position and
 * the last painted offset. Call {@link #reset()} when a new stroke begins.</p>
 *
 * <h2>Frequency Bin Handling</h2>
 * <p>Source frequency data is mapped directly to canvas coordinates using
 * normalized frequency (0-1). The {@link EditableSpatialWaveDetails#applyValues}
 * method handles the actual bin mapping via {@link TemporalSpatialContext}.</p>
 *
 * @see SpatialBrush
 * @see WaveDetails#getFreqData()
 */
public class SampleBrush implements SpatialBrush {

	/** The source wave details providing frequency data. */
	private WaveDetails sourceWave;

	/** The packed frequency data extracted from the source wave. */
	private PackedCollection sourceFreqData;

	/** The number of frequency bins per frame in the source data. */
	private int sourceFreqBinCount;

	/** The total number of frequency frames in the source data. */
	private int sourceFreqFrameCount;

	/** The frequency sample rate of the source data in Hz. */
	private double sourceFreqSampleRate;

	/**
	 * The spatial context used to convert between spatial X coordinates and
	 * seconds. Required for correct stroke handling.
	 */
	private TemporalSpatialContext context;

	// Stroke state
	/** The position at which the current stroke began (in spatial coordinates). */
	private Vector strokeStartPosition;

	/** The canvas time in seconds at which the current stroke began. */
	private double strokeStartSeconds;

	/** The index of the last frequency frame painted during the current stroke. */
	private int lastPaintedFrame;

	// Configurable parameters (required by interface but not used for sample painting)
	/** The brush radius (not used for sample painting, required by interface). */
	private double radius = 10.0;

	/** The brush density (not used for sample painting, required by interface). */
	private double density = 100.0;

	/**
	 * Creates an empty sample brush with no source configured.
	 *
	 * <p>Call {@link #setSource(WaveDetails)} before using.</p>
	 */
	public SampleBrush() {
	}

	/**
	 * Sets the source WaveDetails to paint from.
	 *
	 * <p>The source must have frequency data available via
	 * {@link WaveDetails#getFreqData()}. If the source is null or
	 * lacks frequency data, the brush will return empty results.</p>
	 *
	 * @param wave the source wave details (must have freqData)
	 * @return true if source is valid and ready, false otherwise
	 */
	public boolean setSource(WaveDetails wave) {
		if (wave == null || wave.getFreqData() == null) {
			this.sourceWave = null;
			this.sourceFreqData = null;
			return false;
		}

		this.sourceWave = wave;
		this.sourceFreqData = wave.getFreqData();
		this.sourceFreqBinCount = wave.getFreqBinCount();
		this.sourceFreqFrameCount = wave.getFreqFrameCount();
		this.sourceFreqSampleRate = wave.getFreqSampleRate();
		return true;
	}

	/**
	 * Returns whether this brush has a valid source configured.
	 *
	 * @return true if a source with frequency data is set
	 */
	public boolean hasValidSource() {
		return sourceWave != null && sourceFreqData != null;
	}

	/**
	 * Returns the current source WaveDetails.
	 *
	 * @return the source, or null if not set
	 */
	public WaveDetails getSource() {
		return sourceWave;
	}

	/**
	 * Sets the spatial context used by this brush to convert between spatial
	 * X coordinates and seconds. This is required for correct stroke handling.
	 *
	 * @param context the temporal spatial context
	 */
	public void setContext(TemporalSpatialContext context) {
		this.context = context;
	}

	@Override
	public void reset() {
		strokeStartPosition = null;
		strokeStartSeconds = 0;
		lastPaintedFrame = -1;
	}

	@Override
	public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
		if (!hasValidSource() || context == null) {
			return Collections.emptyList();
		}

		double currentSeconds = context.seconds(center);

		// First stroke establishes start position
		if (strokeStartPosition == null) {
			strokeStartPosition = center;
			strokeStartSeconds = currentSeconds;
			lastPaintedFrame = -1;
		}

		// Calculate elapsed canvas time since stroke start
		double elapsedSeconds = currentSeconds - strokeStartSeconds;
		if (elapsedSeconds < 0) {
			return Collections.emptyList();
		}

		// Map elapsed canvas time directly to source frames (1:1 time mapping)
		int targetFrame = (int) (elapsedSeconds * sourceFreqSampleRate);
		targetFrame = Math.min(targetFrame, sourceFreqFrameCount - 1);

		if (targetFrame <= lastPaintedFrame) {
			return Collections.emptyList();
		}

		// Generate SpatialValues for frames (lastPaintedFrame, targetFrame]
		List<SpatialValue<?>> values = new ArrayList<>();
		int startFrame = lastPaintedFrame + 1;

		for (int frame = startFrame; frame <= targetFrame; frame++) {
			double frameSeconds = frame / sourceFreqSampleRate;
			double canvasSeconds = strokeStartSeconds + frameSeconds;
			addFrameValues(values, frame, canvasSeconds);
		}

		lastPaintedFrame = targetFrame;
		return values;
	}

	/**
	 * Adds SpatialValues for all bins in a single source frame at the
	 * given canvas time.
	 *
	 * @param values        the list to add values to
	 * @param sourceFrame   the frame index in the source
	 * @param canvasSeconds the canvas time in seconds where this frame should land
	 */
	private void addFrameValues(List<SpatialValue<?>> values, int sourceFrame, double canvasSeconds) {
		int offset = sourceFrame * sourceFreqBinCount;
		double timeUnits = context.getSecondsToTime().applyAsDouble(canvasSeconds);

		for (int bin = 0; bin < sourceFreqBinCount; bin++) {
			double magnitude = sourceFreqData.toDouble(offset + bin);

			if (magnitude < FrequencyTimeseries.frequencyThreshold) {
				continue;
			}

			double normalizedFreq = (double) bin / sourceFreqBinCount;
			Vector pos = context.position(timeUnits, 0, 0, normalizedFreq);

			// Scale magnitude for SpatialValue (matching FrequencyTimeseries.loadElements)
			double scaledMag = (magnitude - FrequencyTimeseries.frequencyThreshold)
					* FrequencyTimeseries.frequencyScale;
			double logMag = Math.log(scaledMag + 1);

			values.add(new SpatialValue<>(pos, logMag, 0.5, true));
		}
	}

	@Override
	public double getRadius() {
		return radius;
	}

	@Override
	public void setRadius(double radius) {
		this.radius = radius;
	}

	@Override
	public double getDensity() {
		return density;
	}

	@Override
	public void setDensity(double density) {
		this.density = density;
	}
}
