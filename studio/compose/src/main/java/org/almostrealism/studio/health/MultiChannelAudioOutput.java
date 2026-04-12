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

package org.almostrealism.studio.health;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Aggregates audio receptors for the master mix, individual stems, and per-measure
 * monitoring channels. Receptors are created on demand via factory functions when
 * a channel is first accessed.
 */
public class MultiChannelAudioOutput {
	/** Receptors keyed by stereo channel for the master mix output. */
	private final Map<ChannelInfo.StereoChannel, Receptor<PackedCollection>> master;

	/** Receptors keyed by channel info for individual stem outputs. */
	private final Map<ChannelInfo, Receptor<PackedCollection>> stems;

	/** Receptors keyed by channel info for per-measure monitoring. */
	private final Map<ChannelInfo, Receptor<PackedCollection>> measures;

	/** Factory for master-mix receptors, or {@code null} if master output is disabled. */
	private final Function<ChannelInfo.StereoChannel, Receptor<PackedCollection>> masterFactory;

	/** Factory for stem receptors, or {@code null} if stem output is disabled. */
	private final Function<ChannelInfo, Receptor<PackedCollection>> stemsFactory;

	/** Factory for measure receptors, or {@code null} if measure monitoring is disabled. */
	private final Function<ChannelInfo, Receptor<PackedCollection>> measuresFactory;

	/** Creates a multi-channel output with no active outputs. */
	public MultiChannelAudioOutput() {
		this((Function) null, null, null);
	}

	/**
	 * Creates a multi-channel output with the given master output and no stems or
	 * measure monitoring.
	 *
	 * @param masterOut the wave output to receive the master mix
	 */
	public MultiChannelAudioOutput(WaveOutput masterOut) {
		this(masterOut, null);
	}

	/**
	 * Creates a multi-channel output with the given master and stem outputs and no
	 * measure monitoring.
	 *
	 * @param masterOut the wave output to receive the master mix
	 * @param stemsOut  per-channel stem wave outputs, or {@code null} to disable stems
	 */
	public MultiChannelAudioOutput(
			WaveOutput masterOut, List<WaveOutput> stemsOut) {
		this(masterOut, stemsOut, null);
	}

	/**
	 * Creates a multi-channel output with the given master, stem, and measures outputs.
	 *
	 * @param masterOut       the wave output for the master mix
	 * @param stemsOut        per-channel stem outputs, or {@code null}
	 * @param measuresFactory factory producing measure receptors for each channel
	 */
	public MultiChannelAudioOutput(
			WaveOutput masterOut, List<WaveOutput> stemsOut,
			Function<ChannelInfo, Receptor<PackedCollection>> measuresFactory) {
		this(masterOut == null ? null :
						(audioChannel) -> masterOut.getWriter(audioChannel.getIndex()),
				stemsOut == null ? null :
						(channelInfo) -> stemsOut.get(channelInfo.getPatternChannel())
											.getWriter(channelInfo.getAudioChannel().getIndex()),
				measuresFactory);
	}

	/**
	 * Creates a multi-channel output backed by arbitrary factory functions.
	 *
	 * @param masterFactory   factory for master-mix receptors, or {@code null}
	 * @param stemsFactory    factory for stem receptors, or {@code null}
	 * @param measuresFactory factory for measure receptors, or {@code null}
	 */
	public MultiChannelAudioOutput(
			Function<ChannelInfo.StereoChannel, Receptor<PackedCollection>> masterFactory,
			Function<ChannelInfo, Receptor<PackedCollection>> stemsFactory,
			Function<ChannelInfo, Receptor<PackedCollection>> measuresFactory) {
		this.masterFactory = masterFactory;
		this.stemsFactory = stemsFactory;
		this.measuresFactory = measuresFactory;

		this.master = masterFactory == null ? null : new HashMap<>();
		this.stems = stemsFactory == null ? null : new HashMap<>();
		this.measures = measuresFactory == null ? null : new HashMap<>();
	}

	/**
	 * Returns the measure receptor for the given voicing and stereo channel, creating
	 * it via the factory if it does not yet exist.
	 *
	 * @param voicing      the channel voicing (MAIN, WET, etc.)
	 * @param audioChannel the stereo channel (LEFT or RIGHT)
	 * @return the receptor, or {@code null} if measure monitoring is disabled
	 */
	public Receptor<PackedCollection> getMeasure(ChannelInfo.Voicing voicing,
													ChannelInfo.StereoChannel audioChannel) {
		Receptor<PackedCollection> result =
				retrieve(measures, match(voicing).and(match(audioChannel)));
		if (result != null) return result;

		return getMeasure(new ChannelInfo(-1, voicing, audioChannel));
	}

	/**
	 * Returns the stem receptor for the given pattern channel and stereo channel.
	 *
	 * @param patternChannel the zero-based pattern (instrument) channel index
	 * @param audioChannel   the stereo channel (LEFT or RIGHT)
	 * @return the receptor, or {@code null} if stem output is disabled
	 */
	public Receptor<PackedCollection> getStem(int patternChannel,
												 ChannelInfo.StereoChannel audioChannel) {
		Receptor<PackedCollection> result =
				retrieve(stems, match(patternChannel).and(match(audioChannel)));
		if (result != null) return result;

		return getStem(new ChannelInfo(patternChannel, audioChannel));
	}

	/**
	 * Returns all measure receptors for the given stereo channel.
	 *
	 * @param audioChannel the stereo channel to filter by
	 * @return a list of matching receptors
	 */
	public List<Receptor<PackedCollection>> getMeasures(ChannelInfo.StereoChannel audioChannel) {
		return measures.keySet().stream()
				.filter(match(audioChannel))
				.map(measures::get)
				.toList();
	}

	/**
	 * Returns the measure receptor for the given channel, creating it via the factory
	 * if it does not yet exist.
	 *
	 * @param channel the channel info key
	 * @return the receptor, or {@code null} if measure monitoring is disabled
	 */
	public Receptor<PackedCollection> getMeasure(ChannelInfo channel) {
		if (measures == null) {
			return null;
		} else if (measuresFactory != null) {
			return measures.computeIfAbsent(channel, measuresFactory);
		} else {
			return measures.get(channel);
		}
	}

	/**
	 * Returns the stem receptor for the given channel, creating it via the factory
	 * if it does not yet exist.
	 *
	 * @param channel the channel info key
	 * @return the receptor, or {@code null} if stem output is disabled
	 */
	public Receptor<PackedCollection> getStem(ChannelInfo channel) {
		if (stems == null) {
			return null;
		} else if (stemsFactory != null) {
			return stems.computeIfAbsent(channel, stemsFactory);
		} else {
			return stems.get(channel);
		}
	}

	/**
	 * Returns the master-mix receptor for the given stereo channel, creating it via
	 * the factory if it does not yet exist.
	 *
	 * @param channel the stereo channel (LEFT or RIGHT)
	 * @return the receptor, or {@code null} if master output is disabled
	 */
	public Receptor<PackedCollection> getMaster(ChannelInfo.StereoChannel channel) {
		if (master == null) {
			return null;
		} else if (masterFactory != null) {
			return master.computeIfAbsent(channel, masterFactory);
		} else {
			return master.get(channel);
		}
	}

	/** Returns {@code true} if measure monitoring is active. */
	public boolean isMeasuresActive() { return measures != null; }

	/** Returns {@code true} if stem output is active. */
	public boolean isStemsActive() { return stems != null; }

	/**
	 * Retrieves a single value from the map whose key matches the predicate.
	 *
	 * @param map       the map to search
	 * @param predicate the key predicate
	 * @param <K>       key type
	 * @param <V>       value type
	 * @return the matching value, or {@code null} if no entry matches
	 * @throws IllegalArgumentException if more than one entry matches
	 */
	protected <K, V> V retrieve(Map<K, V> map, Predicate<K> predicate) {
		if (map == null) return null;

		List<V> values = map.entrySet().stream()
				.filter(e -> predicate.test(e.getKey()))
				.map(Map.Entry::getValue)
				.toList();
		if (values.isEmpty()) {
			return null;
		} else if (values.size() > 1) {
			throw new IllegalArgumentException();
		} else {
			return values.get(0);
		}
	}

	/**
	 * Returns a predicate that matches channel infos with the given stereo channel.
	 *
	 * @param audioChannel the stereo channel to match
	 */
	protected Predicate<ChannelInfo> match(ChannelInfo.StereoChannel audioChannel) {
		return c -> c.getAudioChannel() == audioChannel;
	}

	/**
	 * Returns a predicate that matches channel infos with the given pattern channel index.
	 *
	 * @param patternChannel the pattern channel index to match
	 */
	protected Predicate<ChannelInfo> match(int patternChannel) {
		return c -> c.getPatternChannel() == patternChannel;
	}

	/**
	 * Returns a predicate that matches channel infos with the given voicing.
	 *
	 * @param voicing the voicing to match
	 */
	protected Predicate<ChannelInfo> match(ChannelInfo.Voicing voicing) {
		return c -> c.getVoicing() == voicing;
	}
}
