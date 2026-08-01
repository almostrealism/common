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

package org.almostrealism.audio.sources;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Interface for audio generators that produce output based solely on input parameters.
 * Unlike stateful cells, stateless sources do not maintain internal state between
 * invocations. The generate method produces audio frames given buffer specifications,
 * parameter data, and a frequency factor for pitch control.
 *
 * @see BufferDetails
 */
public interface StatelessSource {
	/**
	 * Generates audio data for the given buffer configuration, synthesis parameters, and frequency.
	 *
	 * @param buffer    the buffer configuration (sample rate and frame count)
	 * @param params    synthesis parameter values
	 * @param frequency a Factor that scales the output based on the desired frequency
	 * @return a producer yielding the generated audio data
	 */
	Producer<PackedCollection> generate(BufferDetails buffer,
										   Producer<PackedCollection> params,
										   Factor<PackedCollection> frequency);
}
