/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

/**
 * Spatial audio visualization components for the Almost Realism studio layer.
 *
 * <p>This package provides the core abstractions and implementations for
 * transforming audio frequency data into 3D spatial representations. It
 * supports multiple visualization strategies, brush-based editing, and
 * publish/subscribe coordination between audio producers and visualizers.</p>
 *
 * <h2>Key Abstractions</h2>
 * <ul>
 *   <li>{@link org.almostrealism.spatial.SpatialTimeseries} — base interface for
 *       objects that produce lists of {@link org.almostrealism.spatial.SpatialValue}
 *       given a {@link org.almostrealism.spatial.TemporalSpatialContext}</li>
 *   <li>{@link org.almostrealism.spatial.FrequencyTimeseries} — converts spectrogram
 *       data into spatial elements with log-scaled magnitudes</li>
 *   <li>{@link org.almostrealism.spatial.TemporalSpatialContext} — defines the
 *       mapping from time/frequency coordinates to 3D positions</li>
 *   <li>{@link org.almostrealism.spatial.SpatialBrush} — interface for interactive
 *       drawing tools that emit {@link org.almostrealism.spatial.SpatialValue} lists</li>
 * </ul>
 *
 * <h2>Hub Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.spatial.SpatialDataHub} — pub/sub hub for spatial
 *       timeseries publication and selection events</li>
 *   <li>{@link org.almostrealism.spatial.SoundDataHub} — pub/sub hub for sound data
 *       selection, publication, and playback control</li>
 * </ul>
 *
 * <h2>Brush Implementations</h2>
 * <ul>
 *   <li>{@link org.almostrealism.spatial.SphericalBrush} — uniform spherical distribution</li>
 *   <li>{@link org.almostrealism.spatial.GaussianBrush} — Gaussian falloff distribution</li>
 *   <li>{@link org.almostrealism.spatial.HarmonicBrush} — harmonic frequency series</li>
 *   <li>{@link org.almostrealism.spatial.FrequencyBandBrush} — sustained horizontal bands</li>
 *   <li>{@link org.almostrealism.spatial.SampleBrush} — paints from a source audio sample</li>
 * </ul>
 */
package org.almostrealism.spatial;
