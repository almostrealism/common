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

import org.almostrealism.audio.synth.ModulationRouter;
import org.almostrealism.audio.synth.ModulationSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Manages mappings between MIDI CC messages and modulation destinations.
 * <p>
 * CCMappingManager provides:
 * <ul>
 *   <li>Creating and removing CC-to-parameter mappings</li>
 *   <li>MIDI learn functionality</li>
 *   <li>Integration with {@link ModulationRouter}</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * ModulationRouter router = new ModulationRouter();
 * CCMappingManager ccManager = new CCMappingManager(router);
 *
 * // Create a mapping: CC1 (mod wheel) to filter cutoff
 * ccManager.mapCC(0, 1, ModulationSlot.Destination.FILTER_CUTOFF, 1.0);
 *
 * // Enable MIDI learn for a destination
 * ccManager.enableLearn(ModulationSlot.Destination.FILTER_CUTOFF, mapping -> {
 *     System.out.println("Learned CC " + mapping.getCCNumber());
 * });
 *
 * // Add to MIDI input chain
 * connection.addListener(ccManager);
 * }</pre>
 *
 * @see MidiCCSource
 * @see ModulationRouter
 */
public class CCMappingManager implements MidiInputListener {

	/**
	 * Represents a single CC mapping.
	 */
	public static class CCMapping {
		private final int channel;
		private final int ccNumber;
		private final ModulationSlot.Destination destination;
		private final MidiCCSource source;
		private final ModulationSlot slot;

		CCMapping(int channel, int ccNumber, ModulationSlot.Destination destination,
				  MidiCCSource source, ModulationSlot slot) {
			this.channel = channel;
			this.ccNumber = ccNumber;
			this.destination = destination;
			this.source = source;
			this.slot = slot;
		}

		public int getChannel() { return channel; }
		public int getCCNumber() { return ccNumber; }
		public ModulationSlot.Destination getDestination() { return destination; }
		public MidiCCSource getSource() { return source; }
		public ModulationSlot getSlot() { return slot; }
	}

	private final ModulationRouter router;
	private final Map<Long, CCMapping> mappings;  // Key = channel << 8 | ccNumber
	private int learnChannel;
	private ModulationSlot.Destination learnDestination;
	private Consumer<CCMapping> learnCallback;

	/**
	 * Creates a CC mapping manager connected to the specified modulation router.
	 *
	 * @param router the modulation router to connect mappings to
	 */
	public CCMappingManager(ModulationRouter router) {
		this.router = router;
		this.mappings = new HashMap<>();
		this.learnChannel = -1;
		this.learnDestination = null;
		this.learnCallback = null;
	}

	/**
	 * Creates a mapping from a CC to a modulation destination.
	 *
	 * @param channel MIDI channel (0-15), or -1 for all channels
	 * @param ccNumber CC number (0-127)
	 * @param destination the modulation destination
	 * @param depth modulation depth
	 * @return the created mapping
	 */
	public CCMapping mapCC(int channel, int ccNumber, ModulationSlot.Destination destination, double depth) {
		// Remove existing mapping if present
		unmapCC(channel, ccNumber);

		// Create CC source
		MidiCCSource source = new MidiCCSource(ccNumber);

		// Connect to modulation router
		ModulationSlot slot = router.connect(source, destination, depth);

		// Store mapping
		CCMapping mapping = new CCMapping(channel, ccNumber, destination, source, slot);
		mappings.put(mappingKey(channel, ccNumber), mapping);

		return mapping;
	}

	/**
	 * Creates a mapping with custom source configuration.
	 *
	 * @param channel MIDI channel (0-15), or -1 for all channels
	 * @param ccNumber CC number (0-127)
	 * @param destination the modulation destination
	 * @param depth modulation depth
	 * @param curve the curve type
	 * @param minValue minimum output value
	 * @param maxValue maximum output value
	 * @return the created mapping
	 */
	public CCMapping mapCC(int channel, int ccNumber, ModulationSlot.Destination destination,
						   double depth, MidiCCSource.CurveType curve, double minValue, double maxValue) {
		CCMapping mapping = mapCC(channel, ccNumber, destination, depth);
		mapping.source.setCurve(curve);
		mapping.source.setRange(minValue, maxValue);
		return mapping;
	}

	/**
	 * Removes a CC mapping.
	 *
	 * @param channel MIDI channel (0-15), or -1 for all channels
	 * @param ccNumber CC number (0-127)
	 */
	public void unmapCC(int channel, int ccNumber) {
		CCMapping existing = mappings.remove(mappingKey(channel, ccNumber));
		if (existing != null) {
			router.disconnect(existing.slot);
			router.removeSource(existing.source);
		}
	}

	/**
	 * Returns the mapping for the specified CC, or null if not mapped.
	 *
	 * @param channel MIDI channel (0-15), or -1 for all channels
	 * @param ccNumber CC number (0-127)
	 * @return the mapping or null
	 */
	public CCMapping getMapping(int channel, int ccNumber) {
		return mappings.get(mappingKey(channel, ccNumber));
	}

	/**
	 * Returns all current mappings.
	 */
	public List<CCMapping> getAllMappings() {
		return new ArrayList<>(mappings.values());
	}

	/**
	 * Enables MIDI learn mode for a destination.
	 * <p>
	 * The next CC message received will be mapped to the destination,
	 * and the callback will be invoked with the new mapping.
	 *
	 * @param destination the destination to map
	 * @param callback called when mapping is created
	 */
	public void enableLearn(ModulationSlot.Destination destination, Consumer<CCMapping> callback) {
		enableLearn(-1, destination, callback);
	}

	/**
	 * Enables MIDI learn mode for a specific channel and destination.
	 *
	 * @param channel MIDI channel to learn from (-1 for any)
	 * @param destination the destination to map
	 * @param callback called when mapping is created
	 */
	public void enableLearn(int channel, ModulationSlot.Destination destination, Consumer<CCMapping> callback) {
		this.learnChannel = channel;
		this.learnDestination = destination;
		this.learnCallback = callback;
	}

	/**
	 * Disables MIDI learn mode.
	 */
	public void disableLearn() {
		this.learnChannel = -1;
		this.learnDestination = null;
		this.learnCallback = null;
	}

	/**
	 * Returns true if MIDI learn mode is active.
	 */
	public boolean isLearning() {
		return learnDestination != null;
	}

	/**
	 * Clears all mappings.
	 */
	public void clearAllMappings() {
		for (CCMapping mapping : new ArrayList<>(mappings.values())) {
			router.disconnect(mapping.slot);
			router.removeSource(mapping.source);
		}
		mappings.clear();
	}

	// ========== MidiInputListener Implementation ==========

	@Override
	public void controlChange(int channel, int controller, int value) {
		// Check for MIDI learn
		if (learnDestination != null) {
			if (learnChannel == -1 || learnChannel == channel) {
				CCMapping mapping = mapCC(channel, controller, learnDestination, 1.0);
				Consumer<CCMapping> callback = learnCallback;
				disableLearn();
				if (callback != null) {
					callback.accept(mapping);
				}
				return;
			}
		}

		// Update existing mappings
		// Check for channel-specific mapping first
		CCMapping mapping = mappings.get(mappingKey(channel, controller));
		if (mapping != null) {
			mapping.source.setValue(value);
		}

		// Also check for omni mapping (channel -1)
		CCMapping omniMapping = mappings.get(mappingKey(-1, controller));
		if (omniMapping != null) {
			omniMapping.source.setValue(value);
		}
	}

	// ========== Internal Methods ==========

	/**
	 * Creates a unique key for channel/CC combination.
	 */
	private long mappingKey(int channel, int ccNumber) {
		return ((long)(channel + 1) << 8) | ccNumber;
	}
}
