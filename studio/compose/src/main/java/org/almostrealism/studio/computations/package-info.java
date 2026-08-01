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
 * Hardware-accelerated audio analysis computations for the Almost Realism studio compose
 * module. Classes in this package provide efficient GPU-compatible operations for
 * measuring audio quality metrics such as clipping and silence.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.computations.ClipCounter} - Counts audio samples
 *       that exceed a configured amplitude range</li>
 *   <li>{@link org.almostrealism.studio.computations.SilenceDurationComputation} - Computes
 *       the duration of consecutive silence in an audio stream</li>
 * </ul>
 */
package org.almostrealism.studio.computations;
