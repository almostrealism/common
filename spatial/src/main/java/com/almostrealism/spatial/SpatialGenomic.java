/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;

/**
 * Marker interface for spatial objects that have associated genetic/ML parameters.
 *
 * <p>{@code SpatialGenomic} indicates that a spatial timeseries has been generated
 * using machine learning or evolutionary algorithms, and carries the parameters
 * (genome) used to generate it. This allows:</p>
 * <ul>
 *   <li>Visualization of ML model outputs alongside their parameters</li>
 *   <li>Re-generation with modified parameters</li>
 *   <li>Persistence of generation parameters for reproducibility</li>
 * </ul>
 *
 * <p>The {@link Genome} object typically wraps a {@link PackedCollection} containing
 * the numeric parameters, which may represent neural network weights, evolutionary
 * algorithm chromosomes, or other learned representations.</p>
 *
 * @see GenomicTimeseries
 * @see GenomicNetwork
 * @see Genome
 */
public interface SpatialGenomic {

	/**
	 * Returns the genome (parameter set) associated with this spatial object.
	 *
	 * @return the genome containing generation parameters, or {@code null} if none
	 */
	Genome<PackedCollection> getGenome();
}
