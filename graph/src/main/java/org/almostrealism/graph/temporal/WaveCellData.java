/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;

public interface WaveCellData extends BaseAudioData {

	default Scalar waveIndex() { return get(3); }
	default Scalar waveCount() { return get(4); }
	default Scalar duration() { return get(5); }
	default Scalar value() { return get(9); }

	default Producer<Scalar> getWaveIndex() { return p(waveIndex()); }
	default void setWaveIndex(int count) { waveIndex().setValue(count); }

	default Producer<Scalar> getWaveCount() { return p(waveCount()); }
	default void setWaveCount(int count) { waveCount().setValue(count); }

	default Producer<Scalar> getDuration() { return p(duration()); }
	default void setDuration(double duration) { duration().setValue(duration); }
}

