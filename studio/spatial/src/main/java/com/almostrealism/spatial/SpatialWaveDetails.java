/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Adapter that converts {@link WaveDetails} frequency analysis data into
 * a spatial representation for visualization.
 *
 * <p>{@code SpatialWaveDetails} wraps pre-computed wave analysis data
 * (spectrogram/FFT results) and provides the interface needed by
 * {@link FrequencyTimeseries} to generate spatial visualizations.
 * It supports optional offset and length parameters for visualizing
 * a subset of the audio.</p>
 *
 * <h2>Data Source</h2>
 * <p>The underlying {@link WaveDetails} object contains:</p>
 * <ul>
 *   <li>Frequency magnitude data per channel</li>
 *   <li>Sample rate and frequency sample rate</li>
 *   <li>Frequency bin count for spectral resolution</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * WaveDetails details = AudioLibraryPersistence.loadWaveDetails("audio.bin");
 * SpatialWaveDetails spatial = new SpatialWaveDetails(details);
 *
 * // Get spatial visualization
 * List<SpatialValue> elements = spatial.elements(context);
 * }</pre>
 *
 * @see FrequencyTimeseriesAdapter
 * @see WaveDetails
 * @see GenomicTimeseries
 */
public class SpatialWaveDetails extends FrequencyTimeseriesAdapter {
	private WaveDetails wave;
	private int offset, length;

	/**
	 * Creates spatial wave details for the entire audio file.
	 *
	 * @param wave the wave details containing frequency analysis data
	 */
	public SpatialWaveDetails(WaveDetails wave) {
		this(wave, 0, wave.getFrameCount());
	}

	/**
	 * Creates spatial wave details for a subset of the audio file.
	 *
	 * @param wave   the wave details containing frequency analysis data
	 * @param offset the starting frame offset (in audio sample frames)
	 * @param length the number of frames to include
	 */
	public SpatialWaveDetails(WaveDetails wave, int offset, int length) {
		this.wave = wave;
		this.offset = offset;
		this.length = length;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the wave identifier from the underlying WaveDetails.</p>
	 */
	@Override
	public String getKey() {
		return wave.getIdentifier();
	}

	public WaveDetails getWave() { return wave; }

	public int getOffset() { return offset; }

	public int getLength() { return length; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Always returns 1 as wave details represent a single layer.</p>
	 */
	@Override
	public int getLayerCount() { return 1; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Always returns 0 (single channel index).</p>
	 */
	@Override
	public int getIndex(int layer) {
		return 0;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Calculates interval to produce approximately 40 time points,
	 * capped at 1.0 for minimum resolution.</p>
	 */
	@Override
	public double getElementInterval(int layer) {
		if (wave.getFreqFrameCount() == 0) {
			return 1.0;
		}

		return Math.min(1.0, wave.getFreqFrameCount() / 40.0);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the inverse of the frequency sample rate.</p>
	 */
	@Override
	public double getFrequencyTimeScale(int layer) {
		return 1.0 / wave.getFreqSampleRate();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns frequency data for all channels in the wave.</p>
	 */
	@Override
	public List<PackedCollection> getSeries(int layer) {
		return IntStream.range(0, wave.getFreqChannelCount())
				.mapToObj(c -> getSeries(layer, c))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the frequency data for a specific channel with offset/length applied.
	 *
	 * @param layer   the layer index (unused, always 0)
	 * @param channel the audio channel index
	 * @return the frequency data collection, or {@code null} if no frequency data
	 */
	public PackedCollection getSeries(int layer, int channel) {
		if (wave.getFreqData() == null) return null;

		double ratio = wave.getSampleRate() / wave.getFreqSampleRate();
		int o = (int) (offset / ratio);
		int l = (int) (length / ratio);

		if (l == 0) {
			l = 1;

			if ((o + l) * wave.getFreqBinCount() > wave.getFreqData(channel).getShape().getTotalSize()) {
				o--;
			}
		}

		return wave.getFreqData(channel).range(new TraversalPolicy(l, wave.getFreqBinCount(), 1),
				o * wave.getFreqBinCount());
	}
}
