/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial.series;

import com.almostrealism.spatial.FrequencyTimeseries;
import com.almostrealism.spatial.FrequencyTimeseriesAdapter;
import com.almostrealism.spatial.SoundData;
import com.almostrealism.spatial.SpatialWaveDetails;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;

/**
 * A timeseries that bridges {@link SoundData} with {@link SpatialWaveDetails}
 * for frequency visualization.
 *
 * <p>{@code SoundDataTimeseries} combines sound data (the audio file reference)
 * with its frequency analysis (wave details) to provide a complete representation
 * that can be both played back and visualized.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SoundData sound = new SoundData("/path/to/audio.wav");
 * WaveDetails details = AudioLibraryPersistence.loadWaveDetails("/path/to/audio.bin");
 * SoundDataTimeseries ts = new SoundDataTimeseries(sound, details);
 *
 * // Get audio for playback
 * WaveDataProvider provider = ts.getProvider();
 *
 * // Get visualization
 * List<SpatialValue> elements = ts.elements(context);
 * }</pre>
 *
 * @see SoundData
 * @see SpatialWaveDetails
 * @see SimpleTimeseries
 */
public class SoundDataTimeseries extends FrequencyTimeseriesAdapter {
	private SoundData sound;
	private SpatialWaveDetails details;

	/**
	 * Creates a sound data timeseries from sound data and wave details.
	 *
	 * @param sound   the sound data containing the audio file reference
	 * @param details the wave details containing frequency analysis
	 */
	public SoundDataTimeseries(SoundData sound, WaveDetails details) {
		this(sound, new SpatialWaveDetails(details));
	}

	/**
	 * Creates a sound data timeseries from sound data and spatial wave details.
	 *
	 * @param sound   the sound data containing the audio file reference
	 * @param details the spatial wave details adapter
	 */
	public SoundDataTimeseries(SoundData sound, SpatialWaveDetails details) {
		this.sound = sound;
		this.details = details;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Always returns 1.</p>
	 */
	@Override
	public int getLayerCount() { return 1; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the key from the wave details.</p>
	 */
	@Override
	public String getKey() { return details.getKey(); }

	/**
	 * Returns the sound data for this timeseries.
	 *
	 * @return the sound data containing the audio file reference
	 */
	public SoundData getSoundData() { return sound; }

	/**
	 * Returns a wave data provider for accessing the audio data.
	 *
	 * @return the wave data provider from the sound data
	 */
	public WaveDataProvider getProvider() {
		return getSoundData().toProvider();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the spatial wave details.</p>
	 */
	@Override
	protected FrequencyTimeseries getDelegate(int layer) {
		return details;
	}
}
