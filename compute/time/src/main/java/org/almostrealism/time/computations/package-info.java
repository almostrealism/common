/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Hardware-accelerated temporal signal computations for the AR time-domain module.
 *
 * <p>This package provides {@code CollectionProducer}-based implementations of common
 * signal processing operations, all of which compile to native GPU/CPU kernels via the
 * standard AR code generation pipeline:</p>
 *
 * <ul>
 *   <li>{@link org.almostrealism.time.computations.FourierTransform} — forward and inverse
 *       FFT using a mixed radix-2/radix-4 Cooley-Tukey decomposition.</li>
 *   <li>{@link org.almostrealism.time.computations.Interpolate} — linear interpolation
 *       over a time-series at arbitrary fractional positions, with optional rate scaling
 *       and configurable time/index mapping functions.</li>
 *   <li>{@link org.almostrealism.time.computations.WindowComputation} — generation of
 *       standard analysis windows (Hann, Hamming, Blackman, flat-top) for spectral analysis.</li>
 *   <li>{@link org.almostrealism.time.computations.MultiOrderFilter} — cascaded IIR filter
 *       computation supporting arbitrary filter orders.</li>
 *   <li>{@link org.almostrealism.time.computations.AcceleratedTimeSeriesPurge} — hardware-
 *       accelerated purge of stale entries from an {@code AcceleratedTimeSeries} buffer.</li>
 *   <li>{@link org.almostrealism.time.computations.AcceleratedTimeSeriesAdd} — hardware-
 *       accelerated addition of a new sample into an {@code AcceleratedTimeSeries}.</li>
 *   <li>{@link org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt} — hardware-
 *       accelerated lookup of the interpolated value at a given time in an
 *       {@code AcceleratedTimeSeries}.</li>
 * </ul>
 */
package org.almostrealism.time.computations;
