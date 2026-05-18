/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * Core audio scene composition and playback infrastructure for the Almost Realism
 * studio layer. This package provides the central {@link org.almostrealism.studio.AudioScene}
 * orchestrator along with audio player abstractions for real-time and buffered playback.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.AudioScene} - Central orchestrator for audio scene
 *       composition, pattern management, effects processing, and generation</li>
 *   <li>{@link org.almostrealism.studio.AudioPlayer} - Interface for audio playback control</li>
 *   <li>{@link org.almostrealism.studio.AudioPlayerBase} - Base implementation for file-based
 *       audio players with stems export support</li>
 *   <li>{@link org.almostrealism.studio.BufferedAudioPlayer} - Multi-channel buffered audio
 *       player for real-time playback</li>
 *   <li>{@link org.almostrealism.studio.StreamingAudioPlayer} - Unified player supporting
 *       both direct hardware and DAW streaming output modes</li>
 * </ul>
 */
package org.almostrealism.studio;
