/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.music.grains;

/**
 * Configuration for random grain generation within a {@link GrainSet}.
 *
 * <p>Defines the minimum and maximum bounds for the three grain parameters:
 * position within the source, duration, and playback rate. Grains generated
 * via {@link GrainSet#addGrain(GrainGenerationSettings)} will have uniformly
 * random values within these ranges.</p>
 *
 * @see GrainSet
 * @see Grain
 */
public class GrainGenerationSettings {
	/** Minimum grain start position within the source, as a fraction [0, 1]. */
	public double grainPositionMin = 0;

	/** Maximum grain start position within the source, as a fraction [0, 1]. */
	public double grainPositionMax = 1;

	/** Minimum grain duration in seconds. */
	public double grainDurationMin = 0.05;

	/** Maximum grain duration in seconds. */
	public double grainDurationMax = 0.2;

	/** Minimum playback rate (1.0 = normal speed). */
	public double playbackRateMin = 0.25;

	/** Maximum playback rate. */
	public double playbackRateMax = 4.0;
}
