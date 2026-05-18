/*
 * Copyright 2025 Michael Murray
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
 * Audio line abstractions for real-time input and output.
 *
 * <p>This package defines the interfaces and implementations for audio line I/O,
 * including {@link org.almostrealism.audio.line.InputLine} for capturing audio,
 * {@link org.almostrealism.audio.line.OutputLine} for playback, and
 * {@link org.almostrealism.audio.line.BufferedAudio} for real-time buffered streaming.
 * The {@link org.almostrealism.audio.line.OutputLine#sampleRate} constant defines
 * the default sample rate used throughout the audio module.</p>
 */
package org.almostrealism.audio.line;
