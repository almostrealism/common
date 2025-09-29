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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;

public interface WaveCellData extends BaseAudioData {

	default Scalar waveIndex() { return get(3); }
	default Scalar waveCount() { return get(4); }
	default PackedCollection<?> value() { return get(9).range(shape(1)); }

	default Producer<PackedCollection<?>> getWaveIndex() { return p(waveIndex().range(shape(1))); }
	default void setWaveIndex(int count) { waveIndex().setValue(count); }

	default Producer<PackedCollection<?>> getWaveCount() { return p(waveCount().range(shape(1))); }
	default void setWaveCount(int count) { waveCount().setValue(count); }
}

