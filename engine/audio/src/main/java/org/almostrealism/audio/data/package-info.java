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
 * Data types and storage for audio processing state.
 *
 * <p>This package provides the data structures used by audio cells to maintain
 * their internal state in {@link org.almostrealism.collect.PackedCollection} memory.
 * The primary type is {@link org.almostrealism.audio.data.PolymorphicAudioData},
 * which provides GPU-resident storage for wave cell state. Also includes
 * {@link org.almostrealism.audio.data.WaveData} for loading and managing audio
 * sample data from files.</p>
 */
package org.almostrealism.audio.data;
