/*
 * Copyright 2016 Michael Murray
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
 * Provides temporal/time-series processing framework with hardware-accelerated signal processing.
 *
 * <p>This package contains abstractions and computations for:</p>
 * <ul>
 *   <li>Temporal synchronization - coordinating sequential operations</li>
 *   <li>Time-series data management - storing, interpolating, querying time-indexed values</li>
 *   <li>Signal processing - FFT, filtering, frequency operations</li>
 *   <li>Time-based iteration - running operations with timing control</li>
 * </ul>
 *
 * <h2>Core Abstractions</h2>
 *
 * <h3>Temporal Operations</h3>
 * <ul>
 *   <li>{@link org.almostrealism.time.Temporal} - Sequential operations performed as timed steps</li>
 *   <li>{@link org.almostrealism.time.TemporalFeatures} - Convenience methods for temporal operations</li>
 *   <li>{@link org.almostrealism.time.TemporalRunner} - Orchestrates setup and execution</li>
 *   <li>{@link org.almostrealism.time.TemporalList} - Collection of synchronized temporals</li>
 * </ul>
 *
 * <h3>Time-Series Data</h3>
 * <ul>
 *   <li>{@link org.almostrealism.time.TemporalScalar} - Time-value pair</li>
 *   <li>{@link org.almostrealism.time.TimeSeries} - Basic in-memory time-series</li>
 *   <li>{@link org.almostrealism.time.AcceleratedTimeSeries} - GPU-accelerated time-series</li>
 * </ul>
 *
 * <h3>Signal Processing</h3>
 * <ul>
 *   <li>{@link org.almostrealism.time.computations.FourierTransform} - FFT/IFFT</li>
 *   <li>{@link org.almostrealism.time.computations.MultiOrderFilter} - Low/high-pass filtering</li>
 *   <li>{@link org.almostrealism.time.computations.Interpolate} - Time-series interpolation</li>
 * </ul>
 *
 * <h3>Utilities</h3>
 * <ul>
 *   <li>{@link org.almostrealism.time.Frequency} - Frequency conversions (Hz/BPM)</li>
 *   <li>{@link org.almostrealism.time.CursorPair} - Position tracking (deprecated)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Temporal Synchronization</h3>
 * <pre>{@code
 * Temporal myOperation = ...;
 * Supplier<Runnable> looped = myOperation.iter(10);  // Run 10 times
 * }</pre>
 *
 * <h3>Time-Series Processing</h3>
 * <pre>{@code
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
 * series.add(new TemporalScalar(0.0, 1.5));
 * double value = series.valueAt(0.5);  // Interpolated
 * }</pre>
 *
 * <h3>Signal Processing</h3>
 * <pre>{@code
 * // FFT
 * FourierTransform fft = features.fft(512, input);
 *
 * // Filtering
 * MultiOrderFilter filtered = features.lowPass(series, cutoff, sampleRate, 40);
 * }</pre>
 *
 * @author Michael Murray
 */
package org.almostrealism.time;