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

import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Routes modulation signals from sources to destinations.
 * <p>
 * ModulationRouter manages multiple modulation connections and provides
 * efficient access to modulated parameter values. It supports:
 * <ul>
 *   <li>Multiple sources modulating the same destination (summed)</li>
 *   <li>One source modulating multiple destinations</li>
 *   <li>Dynamic connection/disconnection</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * ModulationRouter router = new ModulationRouter();
 *
 * // Create modulation sources
 * LFO vibratoLFO = LFO.vibrato(5.0);
 * ADSREnvelope filterEnv = new ADSREnvelope(0.01, 0.3, 0.5, 0.2);
 *
 * // Add modulation connections
 * router.addSource(vibratoLFO);
 * router.addSource(filterEnv);
 * router.connect(vibratoLFO, ModulationSlot.Destination.PITCH, 0.1);
 * router.connect(filterEnv, ModulationSlot.Destination.FILTER_CUTOFF, 0.5);
 *
 * // Get modulated values
 * double pitch = router.getModulatedValue(ModulationSlot.Destination.PITCH, basePitch);
 * double cutoff = router.getModulatedValue(ModulationSlot.Destination.FILTER_CUTOFF, baseCutoff);
 * }</pre>
 *
 * @see ModulationSlot
 * @see ModulationSource
 */
public class ModulationRouter implements Temporal {

	private final List<ModulationSource> sources;
	private final List<ModulationSlot> slots;
	private final Map<ModulationSlot.Destination, List<ModulationSlot>> destinationMap;

	/**
	 * Creates a new modulation router.
	 */
	public ModulationRouter() {
		this.sources = new ArrayList<>();
		this.slots = new ArrayList<>();
		this.destinationMap = new EnumMap<>(ModulationSlot.Destination.class);

		// Initialize destination lists
		for (ModulationSlot.Destination dest : ModulationSlot.Destination.values()) {
			destinationMap.put(dest, new ArrayList<>());
		}
	}

	/**
	 * Adds a modulation source to be updated on each tick.
	 *
	 * @param source the modulation source
	 */
	public void addSource(ModulationSource source) {
		if (!sources.contains(source)) {
			sources.add(source);
		}
	}

	/**
	 * Removes a modulation source.
	 *
	 * @param source the modulation source to remove
	 */
	public void removeSource(ModulationSource source) {
		sources.remove(source);
		// Also remove any slots using this source
		slots.removeIf(slot -> slot.getSource() == source);
		for (List<ModulationSlot> destSlots : destinationMap.values()) {
			destSlots.removeIf(slot -> slot.getSource() == source);
		}
	}

	/**
	 * Creates a modulation connection.
	 *
	 * @param source the modulation source
	 * @param destination the parameter to modulate
	 * @param depth the modulation depth
	 * @return the created modulation slot
	 */
	public ModulationSlot connect(ModulationSource source, ModulationSlot.Destination destination, double depth) {
		// Add source if not already present
		addSource(source);

		ModulationSlot slot = new ModulationSlot(source, destination, depth);
		slots.add(slot);
		destinationMap.get(destination).add(slot);
		return slot;
	}

	/**
	 * Removes a modulation connection.
	 *
	 * @param slot the modulation slot to remove
	 */
	public void disconnect(ModulationSlot slot) {
		slots.remove(slot);
		destinationMap.get(slot.getDestination()).remove(slot);
	}

	/**
	 * Returns all modulation slots.
	 */
	public List<ModulationSlot> getSlots() {
		return new ArrayList<>(slots);
	}

	/**
	 * Returns modulation slots for a specific destination.
	 */
	public List<ModulationSlot> getSlotsForDestination(ModulationSlot.Destination destination) {
		return new ArrayList<>(destinationMap.get(destination));
	}

	/**
	 * Calculates the total modulation for a destination and applies it to a base value.
	 *
	 * @param destination the parameter being modulated
	 * @param baseValue the base parameter value
	 * @return the modulated value
	 */
	public double getModulatedValue(ModulationSlot.Destination destination, double baseValue) {
		List<ModulationSlot> destSlots = destinationMap.get(destination);
		if (destSlots.isEmpty()) {
			return baseValue;
		}

		double totalModulation = 0;
		for (ModulationSlot slot : destSlots) {
			totalModulation += slot.getModulationAmount();
		}

		return baseValue + totalModulation;
	}

	/**
	 * Calculates pitch modulation as a frequency multiplier.
	 * <p>
	 * Pitch modulation is typically expressed in semitones, so this
	 * converts the modulation to a frequency ratio.
	 *
	 * @param basePitch the base pitch in semitones (or Hz)
	 * @param semitoneRange the maximum semitone deviation
	 * @return the frequency multiplier
	 */
	public double getPitchMultiplier(double basePitch, double semitoneRange) {
		List<ModulationSlot> pitchSlots = destinationMap.get(ModulationSlot.Destination.PITCH);
		if (pitchSlots.isEmpty()) {
			return 1.0;
		}

		double totalSemitones = 0;
		for (ModulationSlot slot : pitchSlots) {
			totalSemitones += slot.getModulationAmount() * semitoneRange;
		}

		// Convert semitones to frequency ratio
		return Math.pow(2.0, totalSemitones / 12.0);
	}

	/**
	 * Advances all modulation sources by one sample.
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("ModulationRouter Tick");

		for (ModulationSource source : sources) {
			tick.add(source.tick());
		}

		return tick;
	}

	/**
	 * Resets all modulation sources.
	 */
	public void reset() {
		for (ModulationSource source : sources) {
			if (source instanceof io.almostrealism.lifecycle.Lifecycle) {
				((io.almostrealism.lifecycle.Lifecycle) source).reset();
			}
		}
	}

	/**
	 * Clears all modulation connections but keeps sources.
	 */
	public void clearConnections() {
		slots.clear();
		for (List<ModulationSlot> destSlots : destinationMap.values()) {
			destSlots.clear();
		}
	}

	/**
	 * Clears all sources and connections.
	 */
	public void clearAll() {
		sources.clear();
		clearConnections();
	}
}
