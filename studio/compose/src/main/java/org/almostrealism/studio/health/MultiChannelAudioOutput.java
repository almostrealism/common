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
import org.almostrealism.hardware.OperationList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Aggregates audio receptors for the master mix, individual stems, and per-measure
 * monitoring channels. Receptors are created on demand via factory functions when
 * a channel is first accessed.
 */
public class MultiChannelAudioOutput {
	/**
	 * Number of bus stems in the standard stem layout, appended after the
	 * per-channel stems: the efx bus and the reverb bus.
	 */
	public static final int BUS_STEM_COUNT = 2;

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

	/** The stem wave outputs, retained when constructed from a list; {@code null} otherwise. */
	private List<WaveOutput> stemOutputs;

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
						(channelInfo) -> channelInfo.getPatternChannel() < stemsOut.size()
								? stemsOut.get(channelInfo.getPatternChannel())
										.getWriter(channelInfo.getAudioChannel().getIndex())
								: null,
				measuresFactory);
		this.stemOutputs = stemsOut;
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
	 * Returns the {@link WaveOutput} recording the given stem, for consumers that
	 * write whole buffers per pass ({@link WaveOutput#append}) rather than pushing
	 * frames through the stem receptors. Available only when this output was
	 * constructed from a stem output list; {@code null} otherwise, or when the
	 * index is outside the constructed list.
	 *
	 * @param patternChannel the stem's pattern-channel index
	 * @return the stem's wave output, or {@code null}
	 */
	public WaveOutput getStemOutput(int patternChannel) {
		if (stemOutputs == null || patternChannel < 0
				|| patternChannel >= stemOutputs.size()) {
			return null;
		}
		return stemOutputs.get(patternChannel);
	}

	/**
	 * Creates an operation appending a whole buffer of frames to the given stem on
	 * both of its stereo writers (dual-mono, matching the master's output shape).
	 * When this output was not constructed with a stem at that index, the returned
	 * operation is empty — the stems an output records are decided at its
	 * construction, never by the party writing to them.
	 *
	 * @param patternChannel the stem's pattern-channel index
	 * @param frames         producer of the frames to append
	 * @param count          number of frames per append
	 * @return supplier of the append operation (empty when the stem is absent)
	 */
	public Supplier<Runnable> appendStem(int patternChannel,
										 PackedCollection frames, int count) {
		OperationList append = new OperationList("Stem Append");
		WaveOutput stem = getStemOutput(patternChannel);
		if (stem != null) {
			append.add(stem.append(0, frames, count));
			append.add(stem.append(1, frames, count));
		}
		return append;
	}

	/**
	 * Returns the total stem count of the standard stem layout for the given number
	 * of pattern channels: one stem per pattern channel, followed by the efx bus at
	 * {@link #efxStemIndex(int)} and the reverb bus at {@link #reverbStemIndex(int)}.
	 * Callers constructing an output that should record the bus stems pass this
	 * value as their stem count; nothing infers or inflates it on their behalf.
	 *
	 * @param channels the number of pattern channels
	 * @return the stem count including the bus stems
	 */
	public static int stemCount(int channels) {
		return channels + BUS_STEM_COUNT;
	}

	/**
	 * Returns the stem index of the efx bus in the standard stem layout.
	 *
	 * @param channels the number of pattern channels
	 * @return the efx bus stem index
	 */
	public static int efxStemIndex(int channels) {
		return channels;
	}

	/**
	 * Returns the stem index of the reverb bus in the standard stem layout.
	 *
	 * @param channels the number of pattern channels
	 * @return the reverb bus stem index
	 */
	public static int reverbStemIndex(int channels) {
		return channels + 1;
	}

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
