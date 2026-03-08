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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * Data interface for ADSR envelope generator state.
 * <p>
 * Stores envelope parameters (attack, decay, sustain level, release times)
 * and runtime state (current phase, position, level).
 * <p>
 * Memory layout (each slot is 1 element):
 * <ul>
 *   <li>Slot 0: attackTime - attack phase duration in seconds</li>
 *   <li>Slot 1: decayTime - decay phase duration in seconds</li>
 *   <li>Slot 2: sustainLevel - sustain amplitude level (0-1)</li>
 *   <li>Slot 3: releaseTime - release phase duration in seconds</li>
 *   <li>Slot 4: sampleRate - sample rate in Hz</li>
 *   <li>Slot 5: phase - current envelope phase (0=idle, 1=attack, 2=decay, 3=sustain, 4=release)</li>
 *   <li>Slot 6: position - position within current phase (0-1)</li>
 *   <li>Slot 7: currentLevel - current envelope output level (0-1)</li>
 *   <li>Slot 8: gateOpen - note gate state (0=closed, 1=open)</li>
 *   <li>Slot 9: releaseLevel - level when release was triggered</li>
 * </ul>
 *
 * @see ADSREnvelope
 */
public interface ADSREnvelopeData extends CodeFeatures {
	int SIZE = 10;

	// Phase constants
	int PHASE_IDLE = 0;
	int PHASE_ATTACK = 1;
	int PHASE_DECAY = 2;
	int PHASE_SUSTAIN = 3;
	int PHASE_RELEASE = 4;

	PackedCollection get(int index);

	default PackedCollection attackTime() { return get(0); }
	default PackedCollection decayTime() { return get(1); }
	default PackedCollection sustainLevel() { return get(2); }
	default PackedCollection releaseTime() { return get(3); }
	default PackedCollection sampleRate() { return get(4); }
	default PackedCollection phase() { return get(5); }
	default PackedCollection position() { return get(6); }
	default PackedCollection currentLevel() { return get(7); }
	default PackedCollection gateOpen() { return get(8); }
	default PackedCollection releaseLevel() { return get(9); }

	default Producer<PackedCollection> getAttackTime() { return p(attackTime().range(shape(1))); }
	default Producer<PackedCollection> getDecayTime() { return p(decayTime().range(shape(1))); }
	default Producer<PackedCollection> getSustainLevel() { return p(sustainLevel().range(shape(1))); }
	default Producer<PackedCollection> getReleaseTime() { return p(releaseTime().range(shape(1))); }
	default Producer<PackedCollection> getSampleRate() { return p(sampleRate().range(shape(1))); }
	default Producer<PackedCollection> getPhase() { return p(phase().range(shape(1))); }
	default Producer<PackedCollection> getPosition() { return p(position().range(shape(1))); }
	default Producer<PackedCollection> getCurrentLevel() { return p(currentLevel().range(shape(1))); }
	default Producer<PackedCollection> getGateOpen() { return p(gateOpen().range(shape(1))); }
	default Producer<PackedCollection> getReleaseLevel() { return p(releaseLevel().range(shape(1))); }

	default void setAttackTime(double seconds) { attackTime().setMem(0, seconds); }
	default void setDecayTime(double seconds) { decayTime().setMem(0, seconds); }
	default void setSustainLevel(double level) { sustainLevel().setMem(0, level); }
	default void setReleaseTime(double seconds) { releaseTime().setMem(0, seconds); }
	default void setSampleRate(double rate) { sampleRate().setMem(0, rate); }
	default void setPhase(int p) { phase().setMem(0, p); }
	default void setPosition(double pos) { position().setMem(0, pos); }
	default void setCurrentLevel(double level) { currentLevel().setMem(0, level); }
	default void setGateOpen(boolean open) { gateOpen().setMem(0, open ? 1.0 : 0.0); }
	default void setReleaseLevel(double level) { releaseLevel().setMem(0, level); }

	/**
	 * Sets all ADSR parameters at once.
	 *
	 * @param attack attack time in seconds
	 * @param decay decay time in seconds
	 * @param sustain sustain level (0-1)
	 * @param release release time in seconds
	 */
	default void setParameters(double attack, double decay, double sustain, double release) {
		setAttackTime(attack);
		setDecayTime(decay);
		setSustainLevel(sustain);
		setReleaseTime(release);
	}

	/**
	 * Resets the envelope to idle state.
	 */
	default void reset() {
		setPhase(PHASE_IDLE);
		setPosition(0);
		setCurrentLevel(0);
		setGateOpen(false);
		setReleaseLevel(0);
	}

	/**
	 * Triggers note on - starts the attack phase.
	 */
	default void noteOn() {
		setPhase(PHASE_ATTACK);
		setPosition(0);
		setGateOpen(true);
	}

	/**
	 * Triggers note off - starts the release phase.
	 */
	default void noteOff() {
		setReleaseLevel(currentLevel().toDouble(0));
		setPhase(PHASE_RELEASE);
		setPosition(0);
		setGateOpen(false);
	}

	/**
	 * Returns true if the envelope is currently active (not idle).
	 */
	default boolean isActive() {
		return phase().toDouble(0) != PHASE_IDLE;
	}
}
