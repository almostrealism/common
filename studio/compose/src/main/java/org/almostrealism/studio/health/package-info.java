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
 * Audio health computation framework used to evaluate the quality of generated audio
 * during evolutionary optimization cycles. Classes in this package implement various
 * scoring strategies based on audio stability, silence detection, and multi-channel output.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.health.AudioHealthScore} - Immutable health score
 *       for a single evaluated individual</li>
 *   <li>{@link org.almostrealism.studio.health.AudioHealthComputation} - Base interface for
 *       audio-specific health computations</li>
 *   <li>{@link org.almostrealism.studio.health.HealthComputationAdapter} - Adapter providing
 *       common configuration for health computations</li>
 *   <li>{@link org.almostrealism.studio.health.StableDurationHealthComputation} - Health
 *       computation that evaluates audio stability over a fixed duration</li>
 *   <li>{@link org.almostrealism.studio.health.SilenceDurationHealthComputation} - Health
 *       computation that penalizes extended silence in generated audio</li>
 *   <li>{@link org.almostrealism.studio.health.MultiChannelAudioOutput} - Audio output
 *       abstraction for multi-channel health evaluation</li>
 * </ul>
 */
package org.almostrealism.studio.health;
