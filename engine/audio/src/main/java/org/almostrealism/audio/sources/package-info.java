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
 * Audio source implementations and aggregation for signal generation.
 *
 * <p>This package provides audio signal sources including wavetable synthesis
 * ({@link org.almostrealism.audio.sources.WavetableCell}), audio buffer management
 * ({@link org.almostrealism.audio.sources.AudioBuffer}), and source aggregation
 * with normalization and EQ ({@link org.almostrealism.audio.sources.ModularSourceAggregator}).
 * These are the building blocks for constructing audio signal chains in the engine.</p>
 */
package org.almostrealism.audio.sources;
