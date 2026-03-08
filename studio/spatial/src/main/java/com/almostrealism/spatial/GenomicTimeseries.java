/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */


package com.almostrealism.spatial;

import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.notes.SceneAudioNode;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.ProjectedGenome;

import java.beans.Transient;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Abstract base class for timeseries data that carries genetic/ML parameters
 * and is associated with a generated audio file.
 *
 * <p>{@code GenomicTimeseries} extends {@link FrequencyTimeseriesAdapter} and
 * implements {@link SpatialGenomic} to represent audio that has been generated
 * by machine learning models or evolutionary algorithms. It combines:</p>
 * <ul>
 *   <li>Frequency visualization via {@link SpatialWaveDetails} delegate</li>
 *   <li>Genetic parameters via {@link Genome}</li>
 *   <li>Fallback placeholder when detailed wave data is unavailable</li>
 *   <li>Reference to associated audio scene node</li>
 * </ul>
 *
 * <h2>Wave Details Loading</h2>
 * <p>The {@link #updateSeries()} method attempts to load pre-computed wave details
 * from a binary file adjacent to the WAV file. If unavailable, a
 * {@link PlaceholderTimeseries} is used instead.</p>
 *
 * <h2>Persistence</h2>
 * <p>Genome parameters can be serialized via {@link #getGenomeParameters()} and
 * deserialized via {@link #setGenomeParameters(List)}. This enables saving and
 * loading ML model configurations.</p>
 *
 * @see FrequencyTimeseriesAdapter
 * @see SpatialGenomic
 * @see GenomicNetwork
 * @see SpatialWaveDetails
 */
public abstract class GenomicTimeseries extends FrequencyTimeseriesAdapter implements SpatialGenomic {

	private transient Genome<PackedCollection> genome;
	private FrequencyTimeseriesAdapter delegate;
	private PlaceholderTimeseries placeholder;

	private SceneAudioNode node;

	/**
	 * {@inheritDoc}
	 * <p>Returns the genome (transient - not serialized directly).</p>
	 */
	@Override
	@Transient
	public Genome<PackedCollection> getGenome() { return genome; }

	/**
	 * Sets the genome for this timeseries.
	 *
	 * @param genome the genome containing generation parameters
	 */
	public void setGenome(Genome<PackedCollection> genome) { this.genome = genome; }

	/**
	 * Sets the genome from a list of double parameters.
	 *
	 * <p>This method is used for JSON deserialization. The list is wrapped
	 * in a {@link ProjectedGenome}.</p>
	 *
	 * @param params the list of genome parameters, or {@code null} to clear
	 */
	public void setGenomeParameters(List<Double> params) {
		if (params == null) {
			this.genome = null;
		} else {
			this.genome = new ProjectedGenome(PackedCollection.of(params));
		}
	}

	/**
	 * Returns the genome parameters as a list of doubles.
	 *
	 * <p>This method is used for JSON serialization. Only works if the genome
	 * is a {@link ProjectedGenome}.</p>
	 *
	 * @return the list of parameters, or {@code null} if not a ProjectedGenome
	 */
	public List<Double> getGenomeParameters() {
		if (genome instanceof ProjectedGenome) {
			return ((ProjectedGenome) genome).getParameters()
					.doubleStream().boxed().collect(Collectors.toList());
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns 1 if a delegate is available after calling {@link #updateSeries()},
	 * otherwise returns 0.</p>
	 */
	@Override
	public int getLayerCount() {
		if (delegate == null) updateSeries();
		return delegate == null ? 0 : 1;
	}

	/**
	 * Loads wave details from disk or creates a placeholder.
	 *
	 * <p>Attempts to load pre-computed wave details from a .bin file adjacent
	 * to the WAV file. If loading fails, creates a {@link PlaceholderTimeseries}
	 * based on the audio duration.</p>
	 */
	public void updateSeries() {
		File detailBin = getDetailsFile();
		if (detailBin == null) {
			return;
		}

		try {
			if (detailBin.exists()) {
				delegate = new SpatialWaveDetails(AudioLibraryPersistence
						.loadWaveDetails(detailBin.getPath()));
			}
		} catch (IOException e) {
			warn("Failed to load wave details for " + getWavFile() + " from " + detailBin.getPath(), e);
		}

		if (delegate == null) {
			double seconds = new FileWaveDataProvider(getWavFile()).getDuration();
			placeholder = new PlaceholderTimeseries(seconds);
		}

		resetElements();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Falls back to placeholder elements if delegate elements are unavailable.</p>
	 */
	@Override
	public List<SpatialValue> elements(TemporalSpatialContext context) {
		List<SpatialValue> elements = super.elements(context);

		if (elements == null && placeholder != null) {
			return placeholder.elements(context);
		}

		return elements;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Triggers {@link #updateSeries()} if delegate is not yet loaded.</p>
	 */
	@Override
	protected FrequencyTimeseries getDelegate(int layer) {
		if (delegate == null) updateSeries();
		return delegate;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Falls back to placeholder duration if delegate is unavailable.</p>
	 */
	@Override
	public double getDuration(TemporalSpatialContext context) {
		if (getLayerCount() > 0) {
			return super.getDuration(context);
		}

		return placeholder == null ? 0 : placeholder.getDuration(context);
	}

	/**
	 * Sets the associated scene audio node.
	 *
	 * @param node the scene audio node
	 */
	public void setSceneAudioNode(SceneAudioNode node) { this.node = node; }

	/**
	 * Returns the associated scene audio node.
	 *
	 * @return the scene audio node, or {@code null} if not set
	 */
	public SceneAudioNode getSceneAudioNode() { return node; }

	/**
	 * Returns the index for this timeseries (default 0).
	 *
	 * @return the index
	 */
	protected int getIndex() { return 0; }

	/**
	 * Returns the path to the WAV audio file associated with this timeseries.
	 *
	 * @return the WAV file path
	 */
	public abstract String getWavFile();

	/**
	 * Returns the file containing pre-computed wave details.
	 *
	 * <p>The details file is located in the same directory as the WAV file,
	 * with the same base name but a .bin extension.</p>
	 *
	 * @return the details file, or {@code null} if WAV file doesn't exist
	 */
	public File getDetailsFile() {
		File f = new File(getWavFile());
		if (!f.exists()) return null;

		File dir = f.getParentFile();
		return new File(dir,
				new FileWaveDataProvider(getWavFile()).getIdentifier() + ".bin");
	}

	/**
	 * Returns a list of files that this timeseries depends on.
	 *
	 * <p>Includes the WAV file and optionally the wave details binary file
	 * if it exists.</p>
	 *
	 * @return list of dependent files (may be empty)
	 */
	public List<File> getDependentFiles() {
		String f = getWavFile();
		if (f == null || f.isEmpty())
			return Collections.emptyList();

		File wav = new File(getWavFile());
		if (!wav.exists()) return Collections.emptyList();

		File details = getDetailsFile();
		if (details != null && details.exists()) {
			return List.of(wav, details);
		}

		return List.of(wav);
	}
}
