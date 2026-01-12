/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.audio.health.AudioHealthScore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;

import java.io.File;

/**
 * A concrete implementation of {@link GenomicTimeseries} for audio generated
 * by neural network or ML models.
 *
 * <p>{@code GenomicNetwork} represents the output of a generative model,
 * combining:</p>
 * <ul>
 *   <li>A network/model index for identification</li>
 *   <li>The genome (model parameters) used for generation</li>
 *   <li>An {@link AudioHealthScore} containing the output file and quality metrics</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GenomicNetwork network = new GenomicNetwork(0, genome);
 * network.setHealthScore(healthScore);
 *
 * // Get spatial visualization
 * List<SpatialValue> elements = network.elements(context);
 * }</pre>
 *
 * @see GenomicTimeseries
 * @see AudioHealthScore
 * @see SpatialGenomic
 */
public class GenomicNetwork extends GenomicTimeseries {
	private int index;
	private AudioHealthScore healthScore;

	/**
	 * Creates an empty genomic network.
	 */
	public GenomicNetwork() { }

	/**
	 * Creates a genomic network with the specified index and genome.
	 *
	 * @param index  the network/model index
	 * @param genome the genome containing model parameters
	 */
	public GenomicNetwork(int index, Genome<PackedCollection> genome) {
		setIndex(index);
		setGenome(genome);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the absolute path of the output audio file from the health score.</p>
	 */
	@Override
	public String getKey() {
		return new File(healthScore.getOutput()).getAbsolutePath();
	}

	/**
	 * Returns the network/model index.
	 *
	 * @return the index
	 */
	@Override
	public int getIndex() { return index; }

	/**
	 * Sets the network/model index.
	 *
	 * @param index the index
	 */
	public void setIndex(int index) { this.index = index; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Caps the element interval at 32 for performance with network outputs.</p>
	 */
	@Override
	public double getElementInterval(int layer) {
		return Math.min(super.getElementInterval(layer), 32);
	}

	/**
	 * Returns the audio health score containing output file and quality metrics.
	 *
	 * @return the health score, or {@code null} if not set
	 */
	public AudioHealthScore getHealthScore() {
		return healthScore;
	}

	/**
	 * Sets the audio health score and triggers wave details loading.
	 *
	 * @param healthScore the health score containing the output audio
	 */
	public void setHealthScore(AudioHealthScore healthScore) {
		this.healthScore = healthScore;
		updateSeries();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns 0 if health score is not set.</p>
	 */
	@Override
	public double getDuration(TemporalSpatialContext context) {
		return healthScore == null ? 0.0 : super.getDuration(context);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the output file path from the health score.</p>
	 */
	@Override
	public String getWavFile() {
		return healthScore == null ? null : healthScore.getOutput();
	}
}
