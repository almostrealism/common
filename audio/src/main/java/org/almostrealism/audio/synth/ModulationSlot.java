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

package org.almostrealism.audio.synth;

/**
 * Represents a modulation connection between a source and a parameter.
 * <p>
 * ModulationSlot encapsulates:
 * <ul>
 *   <li>The modulation source (LFO, envelope, etc.)</li>
 *   <li>The destination parameter</li>
 *   <li>The modulation depth (amount)</li>
 *   <li>Optional offset to add to the modulated value</li>
 * </ul>
 * <p>
 * The output value is calculated as:
 * {@code output = baseValue + (source.getValue() * depth) + offset}
 *
 * @see ModulationSource
 * @see ModulationRouter
 */
public class ModulationSlot {

	/**
	 * Common modulation destinations.
	 */
	public enum Destination {
		/**
		 * Oscillator pitch (frequency).
		 */
		PITCH,

		/**
		 * Oscillator pulse width (for square waves).
		 */
		PULSE_WIDTH,

		/**
		 * Filter cutoff frequency.
		 */
		FILTER_CUTOFF,

		/**
		 * Filter resonance.
		 */
		FILTER_RESONANCE,

		/**
		 * Amplitude/volume.
		 */
		AMPLITUDE,

		/**
		 * Pan position.
		 */
		PAN,

		/**
		 * LFO rate (for modulating another LFO).
		 */
		LFO_RATE,

		/**
		 * Custom/user-defined destination.
		 */
		CUSTOM
	}

	private final ModulationSource source;
	private final Destination destination;
	private double depth;
	private double offset;
	private boolean enabled;

	/**
	 * Creates a modulation slot with the specified source and destination.
	 *
	 * @param source the modulation source
	 * @param destination the parameter to modulate
	 */
	public ModulationSlot(ModulationSource source, Destination destination) {
		this(source, destination, 1.0);
	}

	/**
	 * Creates a modulation slot with the specified source, destination, and depth.
	 *
	 * @param source the modulation source
	 * @param destination the parameter to modulate
	 * @param depth the modulation depth
	 */
	public ModulationSlot(ModulationSource source, Destination destination, double depth) {
		this.source = source;
		this.destination = destination;
		this.depth = depth;
		this.offset = 0;
		this.enabled = true;
	}

	/**
	 * Returns the modulation source.
	 */
	public ModulationSource getSource() {
		return source;
	}

	/**
	 * Returns the destination parameter.
	 */
	public Destination getDestination() {
		return destination;
	}

	/**
	 * Returns the modulation depth.
	 */
	public double getDepth() {
		return depth;
	}

	/**
	 * Sets the modulation depth.
	 *
	 * @param depth the depth (typically -1 to 1 or 0 to 1)
	 */
	public void setDepth(double depth) {
		this.depth = depth;
	}

	/**
	 * Returns the offset value.
	 */
	public double getOffset() {
		return offset;
	}

	/**
	 * Sets the offset to add to the modulated value.
	 *
	 * @param offset the offset value
	 */
	public void setOffset(double offset) {
		this.offset = offset;
	}

	/**
	 * Returns true if this modulation slot is enabled.
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Enables or disables this modulation slot.
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Calculates the current modulated value.
	 *
	 * @param baseValue the base parameter value before modulation
	 * @return the modulated value
	 */
	public double getModulatedValue(double baseValue) {
		if (!enabled) {
			return baseValue;
		}
		return baseValue + (source.getValue() * depth) + offset;
	}

	/**
	 * Returns just the modulation contribution (without base value).
	 */
	public double getModulationAmount() {
		if (!enabled) {
			return 0;
		}
		return source.getValue() * depth;
	}
}
