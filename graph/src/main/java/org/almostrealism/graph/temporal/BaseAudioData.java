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
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;

public interface BaseAudioData extends CodeFeatures {
	Scalar get(int index);

	default Scalar wavePosition() { return get(0); }
	default Scalar waveLength() { return get(1); }
	default Scalar amplitude() { return get(2); }

	default Producer<PackedCollection<?>> getWavePosition() {
		return p(wavePosition().range(shape(1)));
	}

	default void setWavePosition(double wavePosition) {
		wavePosition().setValue(wavePosition);
	}

	default Producer<PackedCollection<?>> getWaveLength() {
		return p(waveLength().range(shape(1)));
	}

	default void setWaveLength(double waveLength) {
		waveLength().setValue(waveLength);
	}

	default Producer<PackedCollection<?>> getAmplitude() {
		return p(amplitude().range(shape(1)));
	}

	default void setAmplitude(double amplitude) {
		amplitude().setValue(amplitude);
	}
}
