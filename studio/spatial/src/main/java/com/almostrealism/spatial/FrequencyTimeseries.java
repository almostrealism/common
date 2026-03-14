/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.Vector;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Abstract base class for converting frequency-domain data into spatial
 * representations for 3D visualization.
 *
 * <p>{@code FrequencyTimeseries} implements the core algorithm for transforming
 * spectrogram or frequency magnitude data into a list of {@link SpatialValue}
 * objects. Each frequency bin above the threshold becomes a positioned point
 * in 3D space, with the value representing the log-scaled magnitude.</p>
 *
 * <h2>Data Model</h2>
 * <p>Subclasses must provide frequency data through the abstract methods:</p>
 * <ul>
 *   <li>{@link #getLayerCount()} - Number of independent layers to visualize</li>
 *   <li>{@link #getIndex(int)} - Channel index for each layer</li>
 *   <li>{@link #getElementInterval(int)} - Sampling interval for time axis</li>
 *   <li>{@link #getFrequencyTimeScale(int)} - Time scale factor for frequency frames</li>
 *   <li>{@link #getSeries(int)} - The actual frequency magnitude data</li>
 * </ul>
 *
 * <h2>Visualization Algorithm</h2>
 * <p>The {@link #loadElements(TemporalSpatialContext)} method:</p>
 * <ol>
 *   <li>Iterates through time intervals defined by element interval</li>
 *   <li>For each time position, scans all frequency bins</li>
 *   <li>Creates {@link SpatialValue} objects for magnitudes above threshold</li>
 *   <li>Applies logarithmic scaling: {@code Math.log(value + 1)}</li>
 *   <li>Aggregates frequencies into quartiles for summary visualization</li>
 *   <li>Retries with lower threshold if insufficient points generated</li>
 * </ol>
 *
 * <h2>Threshold and Scaling</h2>
 * <p>Two static parameters control visualization sensitivity:</p>
 * <ul>
 *   <li>{@link #frequencyThreshold} - Minimum magnitude to display (default: 35)</li>
 *   <li>{@link #frequencyScale} - Amplitude multiplier (default: 1.0)</li>
 * </ul>
 *
 * @see SpatialTimeseries
 * @see SpatialValue
 * @see FrequencyTimeseriesAdapter
 * @see SpatialWaveDetails
 */
public abstract class FrequencyTimeseries implements SpatialTimeseries, ConsoleFeatures {

	/**
	 * The minimum magnitude threshold for a frequency bin to be visualized.
	 * Values below this threshold are not displayed. Default is 35.
	 */
	public static double frequencyThreshold = 35;

	/**
	 * Amplitude scaling factor applied to frequency magnitudes.
	 * Default is 1.0.
	 */
	public static double frequencyScale = 1;

	private List<SpatialValue> elements;
	private double contextDuration;

	/**
	 * Returns the number of independent layers in this timeseries.
	 *
	 * <p>Each layer represents a separate data source that can be
	 * visualized at a different Z position.</p>
	 *
	 * @return the number of layers
	 */
	public abstract int getLayerCount();

	/**
	 * Returns the channel index for the specified layer.
	 *
	 * <p>This index affects the Y position in channel-based visualization mode.</p>
	 *
	 * @param layer the layer index
	 * @return the channel index
	 */
	public abstract int getIndex(int layer);

	/**
	 * Returns the sampling interval for the time axis of the specified layer.
	 *
	 * <p>Smaller values create more spatial points (higher resolution)
	 * but increase memory usage and rendering time.</p>
	 *
	 * @param layer the layer index
	 * @return the element interval in frame units
	 */
	public abstract double getElementInterval(int layer);

	/**
	 * Returns the time scale factor for converting frequency frames to seconds.
	 *
	 * <p>This is typically the inverse of the frequency sample rate.</p>
	 *
	 * @param layer the layer index
	 * @return the frequency time scale (seconds per frame)
	 */
	public abstract double getFrequencyTimeScale(int layer);

	/**
	 * Returns the frequency magnitude data for the specified layer.
	 *
	 * <p>The returned list contains {@link PackedCollection} objects, typically
	 * one per audio channel, with shape [frames, frequency_bins, 1].</p>
	 *
	 * @param layer the layer index
	 * @return list of frequency data collections, or {@code null} if unavailable
	 */
	public abstract List<PackedCollection> getSeries(int layer);

	/**
	 * Loads spatial elements from frequency data using the provided context.
	 *
	 * <p>This method implements the core frequency-to-spatial conversion algorithm.
	 * It handles caching, threshold adjustment, and aggregation. Results are
	 * cached in the {@code elements} field.</p>
	 *
	 * @param context the temporal-spatial context for coordinate mapping
	 */
	protected void loadElements(TemporalSpatialContext context) {
		if (elements != null) return;

		elements = new ArrayList<>();

		l: for (int layer = 0; layer < getLayerCount(); layer++) {
			int index = getIndex(layer);
			List<PackedCollection> features = getSeries(layer);

			if (features == null) {
				elements.add(new SpatialValue<>(context.position(0, index, layer, 0.0), 0.0));
				continue l;
			}

			double skip = getElementInterval(layer);

			TraversalPolicy featureShape = features.get(0).getShape();
			int len = featureShape.length(0);
			int depth = featureShape.length(1);
			double featureData[] = extractMax(features.stream().map(PackedCollection::toArray).toList());
			double frequencyTimeScale = getFrequencyTimeScale(layer);

			for (int attempt = 1; attempt < 5; attempt++) {
				elements.clear();

				for (double id = 0; id < len; id += skip) {
					int i = (int) id;
					double center = id + skip / 2.0;
					double time = center * frequencyTimeScale;

					double aggregates[] = new double[4];

					for (int j = 0; j < depth; j++) {
						int pos = depth - j - 1;
						double freq = j / (double) depth;

						int window = (int) Math.max(i + 1, Math.ceil(center));

						double value = IntStream.range(i, Math.min(window, len))
								.mapToDouble(k -> {
									double location = Math.abs(k - center);
									double dampen = 1.0 - Math.random() * location;
									return dampen * featureData[featureShape.index(k, pos, 0)];
								})
								.max()
								.orElse(0.0);
						value = value - (frequencyThreshold / attempt);
						value = value * frequencyScale * (1.0 + 0.2 * attempt);

						if (value > 0.0) {
							Vector position = context.position(
									context.getSecondsToTime().applyAsDouble(time),
									index, layer, freq);
							elements.add(new SpatialValue<>(
									position, Math.log(value + 1), 1.0 - freq, true));

							aggregates[(int) (4 * freq)] += value;
						}
					}

					for (int j = 0; j < aggregates.length; j++) {
						double freq = (j + 1) / (double) aggregates.length;

						if (aggregates[j] > 0.0) {
							Vector position = context.position(
									context.getSecondsToTime().applyAsDouble(time),
									index, layer, freq, false);
							elements.add(new SpatialValue<>(
									position, Math.log(aggregates[j] + 1), 1.0 - freq, true, true));
						}
					}
				}

				if (elements.size() > 4 * len) {
					return;
				}

				// Otherwise, try again
			}
		}
	}

	/**
	 * Extracts the maximum value at each position across multiple data arrays.
	 *
	 * <p>This is used to combine multi-channel frequency data by taking the
	 * maximum magnitude across all channels at each time/frequency position.</p>
	 *
	 * @param data list of arrays to extract maximum from
	 * @return array containing the maximum value at each position
	 */
	protected double[] extractMax(List<double[]> data) {
		int len = data.get(0).length;
		double max[] = new double[len];

		for (double[] d : data) {
			for (int i = 0; i < max.length; i++) {
				if (d[i] > max[i]) max[i] = d[i];
			}
		}

		return max;
	}

	/**
	 * Clears the cached spatial elements, forcing regeneration on next access.
	 */
	protected void resetElements() { this.elements = null; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation caches the generated elements and regenerates
	 * them when the context duration changes. Each element's referent is
	 * set to this timeseries for back-navigation.</p>
	 */
	@Override
	public List<SpatialValue> elements(TemporalSpatialContext context) {
		if (contextDuration != context.getDuration()) {
			contextDuration = context.getDuration();
			resetElements();
		}

		loadElements(context);
		if (elements != null) {
			elements.forEach(e -> e.setReferent(this));
		}

		return elements;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Calculates duration from the frequency data dimensions and time scale.
	 * Returns the maximum duration across all layers.</p>
	 */
	@Override
	public double getDuration(TemporalSpatialContext context) {
		return IntStream.range(0, getLayerCount())
				.filter(l -> getSeries(l) != null)
				.mapToDouble(l ->
				context.getSecondsToTime().applyAsDouble(
					getSeries(l).get(0).getShape().length(0) * getFrequencyTimeScale(l)))
				.max().orElse(0.0);
	}

	@Override
	public Console console() {
		return AudioScene.console;
	}

	@JsonIgnore
	@Override
	public Class getLogClass() {
		return ConsoleFeatures.super.getLogClass();
	}
}
