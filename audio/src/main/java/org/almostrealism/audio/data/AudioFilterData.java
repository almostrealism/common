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

package org.almostrealism.audio.data;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.BaseAudioData;

public interface AudioFilterData extends BaseAudioData {

	default PackedCollection frequency() { return get(0); }
	default PackedCollection resonance() { return get(1); }
	default PackedCollection sampleRate() { return get(2); }
	default PackedCollection c() { return get(3); }
	default PackedCollection a1() { return get(4); }
	default PackedCollection a2() { return get(5); }
	default PackedCollection a3() { return get(6); }
	default PackedCollection b1() { return get(7); }
	default PackedCollection b2() { return get(8); }
	default PackedCollection output() { return get(9).range(shape(1)); }
	default PackedCollection inputHistory0() { return get(10); }
	default PackedCollection inputHistory1() { return get(11); }
	default PackedCollection outputHistory0() { return get(12); }
	default PackedCollection outputHistory1() { return get(13); }
	default PackedCollection outputHistory2() { return get(14); }

	default Producer<PackedCollection> getFrequency() { return p(frequency()); }
	default void setFrequency(double frequency) { frequency().setMem(0, frequency); }

	default Producer<PackedCollection> getResonance() { return p(resonance()); }
	default void setResonance(double resonance) { resonance().setMem(0, resonance); }

	default Producer<PackedCollection> getSampleRate() { return p(sampleRate()); }
	default void setSampleRate(double sampleRate) { sampleRate().setMem(0, sampleRate); }

	default Producer<PackedCollection> getC() { return p(c()); }
	default Producer<PackedCollection> getA1() { return p(a1()); }
	default Producer<PackedCollection> getA2() { return p(a2()); }
	default Producer<PackedCollection> getA3() { return p(a3()); }
	default Producer<PackedCollection> getB1() { return p(b1()); }
	default Producer<PackedCollection> getB2() { return p(b2()); }
	default Producer<PackedCollection> getOutput() { return p(output()); }
	default Producer<PackedCollection> getInputHistory0() { return p(inputHistory0()); }
	default Producer<PackedCollection> getInputHistory1() { return p(inputHistory1()); }
	default Producer<PackedCollection> getOutputHistory0() { return p(outputHistory0()); }
	default Producer<PackedCollection> getOutputHistory1() { return p(outputHistory1()); }
	default Producer<PackedCollection> getOutputHistory2() { return p(outputHistory2()); }

	default void reset() {
		c().setMem(0, 0.0);
		a1().setMem(0, 0.0);
		a2().setMem(0, 0.0);
		a3().setMem(0, 0.0);
		b1().setMem(0, 0.0);
		b2().setMem(0, 0.0);
		output().setMem(0, 0.0);
		inputHistory0().setMem(0, 0.0);
		inputHistory1().setMem(0, 0.0);
		outputHistory0().setMem(0, 0.0);
		outputHistory1().setMem(0, 0.0);
		outputHistory2().setMem(0, 0.0);
	}
}
