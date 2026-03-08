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

package org.almostrealism.audio.midi;

/**
 * Defines velocity response curves for mapping MIDI velocity to amplitude.
 * <p>
 * Different curves allow customizing how responsive the synthesizer is
 * to touch dynamics:
 * <ul>
 *   <li>{@link #LINEAR} - Direct 1:1 mapping</li>
 *   <li>{@link #SOFT} - Emphasizes soft playing</li>
 *   <li>{@link #HARD} - Requires harder playing for loud notes</li>
 *   <li>{@link #FIXED} - Ignores velocity (full volume always)</li>
 * </ul>
 *
 * @see MidiSynthesizerBridge
 */
public enum VelocityCurve {

	/**
	 * Linear response: velocity maps directly to amplitude.
	 * velocity 64 = 50% amplitude
	 */
	LINEAR {
		@Override
		public double apply(int velocity) {
			return velocity / 127.0;
		}
	},

	/**
	 * Soft curve: emphasizes dynamics at lower velocities.
	 * Good for expressive playing where soft notes are important.
	 */
	SOFT {
		@Override
		public double apply(int velocity) {
			double normalized = velocity / 127.0;
			return Math.sqrt(normalized);
		}
	},

	/**
	 * Hard curve: requires harder playing to reach full volume.
	 * Good for aggressive playing styles.
	 */
	HARD {
		@Override
		public double apply(int velocity) {
			double normalized = velocity / 127.0;
			return normalized * normalized;
		}
	},

	/**
	 * S-curve: soft in the middle, more range at extremes.
	 * Provides a balanced feel for most playing styles.
	 */
	S_CURVE {
		@Override
		public double apply(int velocity) {
			double normalized = velocity / 127.0;
			// Attempt a smoother S-curve using sine
			return 0.5 * (1.0 + Math.sin(Math.PI * (normalized - 0.5)));
		}
	},

	/**
	 * Fixed response: ignores velocity, always full amplitude.
	 * Good for organ-like sounds or when using expression pedal.
	 */
	FIXED {
		@Override
		public double apply(int velocity) {
			return velocity > 0 ? 1.0 : 0.0;
		}
	};

	/**
	 * Applies the velocity curve to convert MIDI velocity to amplitude.
	 *
	 * @param velocity MIDI velocity (0-127)
	 * @return amplitude value (0.0-1.0)
	 */
	public abstract double apply(int velocity);

	/**
	 * Applies the curve with a minimum floor value.
	 *
	 * @param velocity MIDI velocity (0-127)
	 * @param floor minimum amplitude (0.0-1.0)
	 * @return amplitude value (floor to 1.0)
	 */
	public double apply(int velocity, double floor) {
		if (velocity == 0) {
			return 0.0;
		}
		double amplitude = apply(velocity);
		return floor + (1.0 - floor) * amplitude;
	}
}
