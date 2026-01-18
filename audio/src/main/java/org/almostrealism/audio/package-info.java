/*
 * Copyright 2021 Michael Murray
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
 * Core audio synthesis, processing, and file I/O framework.
 *
 * <p>The audio package provides a comprehensive framework for audio synthesis and processing,
 * built on the cell-based processing model from the graph module. It supports real-time
 * audio generation, file-based processing, and integration with external audio libraries.</p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link org.almostrealism.audio.CellFeatures} - Primary interface for building audio
 *       processing chains with fluent API</li>
 *   <li>{@link org.almostrealism.audio.CellList} - Hierarchical container for organizing
 *       audio processing cells</li>
 *   <li>{@link org.almostrealism.audio.WaveOutput} - Captures audio output and writes to
 *       WAV files</li>
 *   <li>{@link org.almostrealism.audio.WavFile} - Low-level WAV file reader/writer</li>
 *   <li>{@link org.almostrealism.audio.AudioLibrary} - Manages collections of audio files
 *       with metadata analysis</li>
 * </ul>
 *
 * <h2>Processing Model</h2>
 * <p>Audio processing uses a setup/tick pattern where:</p>
 * <ol>
 *   <li>{@code setup()} initializes processing state</li>
 *   <li>{@code tick()} advances time by one sample</li>
 *   <li>{@code push()} propagates samples through the processing chain</li>
 * </ol>
 *
 * <h2>Related Packages</h2>
 * <ul>
 *   <li>{@link org.almostrealism.audio.data} - Audio data containers and providers</li>
 *   <li>{@link org.almostrealism.audio.filter} - Audio filters and effects</li>
 *   <li>{@link org.almostrealism.audio.sources} - Audio signal sources</li>
 *   <li>{@link org.almostrealism.audio.tone} - Musical scales and tuning systems</li>
 *   <li>{@link org.almostrealism.audio.synth} - Synthesizer components</li>
 * </ul>
 *
 * @author Michael Murray
 */
package org.almostrealism.audio;