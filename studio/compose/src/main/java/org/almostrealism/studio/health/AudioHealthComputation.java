/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.health;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.optimize.HealthComputation;

import java.util.function.Consumer;

/**
 * Health computation interface for audio generation evaluation. Combines
 * temporal cellular computation with destroyable lifecycle management, and
 * provides access to multi-channel audio output and wave-details processing.
 *
 * @param <T> the type of {@link TemporalCellular} target being evaluated
 */
public interface AudioHealthComputation<T extends TemporalCellular>
		extends HealthComputation<T, AudioHealthScore>, Destroyable {

	/**
	 * Returns the multi-channel audio output used to capture rendered audio
	 * during health evaluation.
	 */
	MultiChannelAudioOutput getOutput();

	/**
	 * Sets a consumer that receives {@link WaveDetails} metadata for each
	 * rendered audio segment.
	 *
	 * @param processor the wave-details consumer, or {@code null} to disable
	 */
	void setWaveDetailsProcessor(Consumer<WaveDetails> processor);
}
