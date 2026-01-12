/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A mutable frequency timeseries that can be modified through spatial drawing.
 *
 * <p>This class extends {@link SpatialWaveDetails} and wraps a mutable
 * {@link WaveDetails} instance. When spatial values are applied via
 * {@link #applyValues}, the underlying frequency data is modified.</p>
 *
 * <p>The frequency data can be accessed via {@code getSeries(layer)} for
 * further processing such as autoencoder input.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create an editable canvas
 * EditableSpatialWaveDetails canvas = new EditableSpatialWaveDetails(
 *     100,    // 100 time frames
 *     256,    // 256 frequency bins
 *     44100,  // 44.1kHz sample rate
 *     100     // 100 Hz frequency sample rate
 * );
 *
 * // Apply brush strokes
 * List<SpatialValue<?>> values = brush.stroke(position, pressure, duration);
 * canvas.applyValues(values, context);
 *
 * // Get the frequency data for processing
 * List<PackedCollection> freqData = canvas.getSeries(0);
 * }</pre>
 *
 * @see SpatialWaveDetails
 * @see EditableSpatialTimeseries
 * @see SpatialBrush
 */
public class EditableSpatialWaveDetails extends SpatialWaveDetails
		implements EditableSpatialTimeseries {

	private boolean modified;

	/**
	 * Creates an editable frequency timeseries with the specified dimensions.
	 *
	 * <p>The resulting WaveDetails will have no identifier. Use
	 * {@link #EditableSpatialWaveDetails(String, int, int, double, double)}
	 * to create a canvas with an identifier for use as an audio condition.</p>
	 *
	 * @param timeFrames     number of time frames in the frequency data
	 * @param frequencyBins  number of frequency bins per frame
	 * @param sampleRate     audio sample rate in Hz
	 * @param freqSampleRate frequency analysis sample rate in Hz
	 */
	public EditableSpatialWaveDetails(int timeFrames, int frequencyBins,
									  double sampleRate, double freqSampleRate) {
		this(null, timeFrames, frequencyBins, sampleRate, freqSampleRate);
	}

	/**
	 * Creates an editable frequency timeseries with the specified identifier and dimensions.
	 *
	 * <p>When an identifier is provided, the resulting WaveDetails can be used
	 * as an audio condition by adding it to an AudioLibrary and referencing
	 * its identifier in AudioModel.audioConditions.</p>
	 *
	 * @param identifier     unique identifier for the WaveDetails (e.g., from KeyUtils.generateKey())
	 * @param timeFrames     number of time frames in the frequency data
	 * @param frequencyBins  number of frequency bins per frame
	 * @param sampleRate     audio sample rate in Hz
	 * @param freqSampleRate frequency analysis sample rate in Hz
	 */
	public EditableSpatialWaveDetails(String identifier, int timeFrames, int frequencyBins,
									  double sampleRate, double freqSampleRate) {
		super(createMutableWaveDetails(identifier, timeFrames, frequencyBins, sampleRate, freqSampleRate));
		this.modified = false;
	}

	/**
	 * Creates a mutable WaveDetails with the specified identifier and dimensions.
	 */
	private static WaveDetails createMutableWaveDetails(String identifier, int timeFrames, int frequencyBins,
														double sampleRate, double freqSampleRate) {
		WaveDetails wave = new WaveDetails(identifier);
		wave.setSampleRate((int) sampleRate);
		wave.setFreqSampleRate((int) freqSampleRate);
		wave.setFreqBinCount(frequencyBins);
		wave.setFreqChannelCount(1);
		wave.setFreqFrameCount(timeFrames);
		wave.setFrameCount((int) (timeFrames * sampleRate / freqSampleRate));
		wave.setFreqData(new PackedCollection(timeFrames * frequencyBins));
		return wave;
	}

	@Override
	public void applyValues(List<SpatialValue<?>> values, TemporalSpatialContext context) {
		WaveDetails wave = getWave();
		PackedCollection freqData = wave.getFreqData();
		int timeFrames = wave.getFreqFrameCount();
		int frequencyBins = wave.getFreqBinCount();

		for (SpatialValue<?> value : values) {
			TemporalSpatialContext.TemporalCoordinates coords = context.inverse(value.getPosition());

			// Convert time to frame index
			int frameIndex = (int) (coords.time() * wave.getFreqSampleRate());
			if (frameIndex < 0 || frameIndex >= timeFrames) continue;

			// Convert normalized frequency to bin index
			int binIndex = (int) (coords.frequency() * frequencyBins);
			if (binIndex < 0 || binIndex >= frequencyBins) continue;

			// Apply the value (inverse of log scaling from FrequencyTimeseries.loadElements)
			double magnitude = Math.exp(value.getValue()) - 1;
			magnitude = magnitude / FrequencyTimeseries.frequencyScale + FrequencyTimeseries.frequencyThreshold;

			int dataIndex = frameIndex * frequencyBins + binIndex;
			double existing = freqData.toDouble(dataIndex);
			freqData.setMem(dataIndex, Math.max(existing, magnitude));
		}

		modified = true;
		resetElements(); // Force regeneration of SpatialValues
	}

	@Override
	public void clear() {
		PackedCollection freqData = getWave().getFreqData();
		for (int i = 0; i < freqData.getMemLength(); i++) {
			freqData.setMem(i, 0.0);
		}
		modified = false;
		resetElements();
	}

	@Override
	public boolean isModified() { return modified; }

	/**
	 * Clears the modified flag without clearing the data.
	 *
	 * <p>Use this after the drawing has been captured as a condition
	 * to prevent re-capturing on subsequent generation attempts.</p>
	 */
	public void clearModified() {
		modified = false;
	}
}
