/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

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

	private WaveDetails sourceWave;
	private PackedCollection sourceFreqData;
	private int sourceFreqBinCount;
	private int sourceFreqFrameCount;
	private double sourceFreqSampleRate;

	// Stroke state
	private Vector strokeStartPosition;
	private int lastPaintedFrame;

	// Configurable parameters (required by interface but not used for sample painting)
	private double radius = 10.0;
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

	@Override
	public void reset() {
		strokeStartPosition = null;
		lastPaintedFrame = 0;
	}

	@Override
	public List<SpatialValue<?>> stroke(Vector center, double pressure, double duration) {
		if (!hasValidSource()) {
			return Collections.emptyList();
		}

		// First stroke establishes start position
		if (strokeStartPosition == null) {
			strokeStartPosition = center;
			lastPaintedFrame = 0;
		}

		// Calculate source frame range based on drag distance
		// X coordinate represents time in seconds (via TemporalSpatialContext)
		double dragDistance = center.getX() - strokeStartPosition.getX();
		if (dragDistance < 0) {
			return Collections.emptyList();
		}

		// Convert drag distance (seconds) to source frames
		int targetFrame = (int) (dragDistance * sourceFreqSampleRate);
		targetFrame = Math.min(targetFrame, sourceFreqFrameCount - 1);

		// Only paint new frames
		if (targetFrame <= lastPaintedFrame && lastPaintedFrame > 0) {
			return Collections.emptyList();
		}

		// Generate SpatialValues for frames [lastPaintedFrame, targetFrame]
		List<SpatialValue<?>> values = new ArrayList<>();
		int startFrame = lastPaintedFrame > 0 ? lastPaintedFrame + 1 : 0;

		for (int frame = startFrame; frame <= targetFrame; frame++) {
			double frameTime = frame / sourceFreqSampleRate;
			double targetX = strokeStartPosition.getX() + frameTime;
			addFrameValues(values, frame, targetX);
		}

		lastPaintedFrame = targetFrame;
		return values;
	}

	/**
	 * Adds SpatialValues for all bins in a single source frame.
	 *
	 * @param values     the list to add values to
	 * @param sourceFrame the frame index in the source
	 * @param targetX    the X coordinate (time) on the canvas
	 */
	private void addFrameValues(List<SpatialValue<?>> values, int sourceFrame, double targetX) {
		int offset = sourceFrame * sourceFreqBinCount;

		for (int bin = 0; bin < sourceFreqBinCount; bin++) {
			double magnitude = sourceFreqData.toDouble(offset + bin);

			// Skip values below threshold
			if (magnitude < FrequencyTimeseries.frequencyThreshold) {
				continue;
			}

			// Convert bin to normalized frequency (0-1)
			double normalizedFreq = (double) bin / sourceFreqBinCount;

			// Scale magnitude for SpatialValue (matching FrequencyTimeseries.loadElements)
			double scaledMag = (magnitude - FrequencyTimeseries.frequencyThreshold)
					* FrequencyTimeseries.frequencyScale;
			double logMag = Math.log(scaledMag + 1);

			values.add(new SpatialValue<>(
					new Vector(targetX, normalizedFreq, 0),
					logMag,
					0.5,  // neutral temperature
					true
			));
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
