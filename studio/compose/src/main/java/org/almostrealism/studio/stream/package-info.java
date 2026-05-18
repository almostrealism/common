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
 * Audio streaming and server infrastructure for the Almost Realism studio compose module.
 * This package provides components for real-time audio streaming over HTTP, shared memory,
 * and delegated audio line management for DAW integration.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.stream.AudioServer} - HTTP server that streams
 *       audio data to connected clients</li>
 *   <li>{@link org.almostrealism.studio.stream.AudioStreamManager} - Manages active audio
 *       streams and client connections</li>
 *   <li>{@link org.almostrealism.studio.stream.AudioStreamHandler} - Handles individual
 *       audio stream connections</li>
 *   <li>{@link org.almostrealism.studio.stream.AudioLineDelegationHandler} - Routes audio
 *       line connections to the appropriate delegated output</li>
 *   <li>{@link org.almostrealism.studio.stream.AudioSharedMemory} - Shared memory transport
 *       for low-latency audio delivery</li>
 *   <li>{@link org.almostrealism.studio.stream.BufferedOutputControl} - Control interface
 *       for the buffered audio output pipeline</li>
 *   <li>{@link org.almostrealism.studio.stream.HttpAudioHandler} - HTTP request handler for
 *       audio stream endpoints</li>
 *   <li>{@link org.almostrealism.studio.stream.SharedPlayerConfig} - Configuration record
 *       for shared player instances</li>
 * </ul>
 */
package org.almostrealism.studio.stream;
