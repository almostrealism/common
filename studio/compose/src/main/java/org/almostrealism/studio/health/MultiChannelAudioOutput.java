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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;

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
	 * <p>A mono {@link WaveOutput} carries only the first channel, so the second
	 * stereo channel of a rendered signal has nowhere to go: its receptor is absent
	 * ({@code null}) rather than an error, and consumers skip an absent receptor
	 * exactly as they skip a disabled one.</p>
	 *
	 * @param masterOut       the wave output for the master mix
	 * @param stemsOut        per-channel stem outputs, or {@code null}
	 * @param measuresFactory factory producing measure receptors for each channel
	 */
	public MultiChannelAudioOutput(
			WaveOutput masterOut, List<WaveOutput> stemsOut,
			Function<ChannelInfo, Receptor<PackedCollection>> measuresFactory) {
		this(masterOut == null ? null :
						(audioChannel) -> audioChannel.getIndex() < masterOut.getChannelCount() ?
								masterOut.getWriter(audioChannel.getIndex()) : null,
				stemsOut == null ? null :
						(channelInfo) -> channelInfo.getAudioChannel().getIndex() <
								stemsOut.get(channelInfo.getPatternChannel()).getChannelCount() ?
								stemsOut.get(channelInfo.getPatternChannel())
										.getWriter(channelInfo.getAudioChannel().getIndex()) : null,
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
				retrieve(measures, c -> c.matches(voicing) && c.matches(audioChannel));
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
				retrieve(stems, c -> c.matches(patternChannel) && c.matches(audioChannel));
		if (result != null) return result;

		return getStem(new ChannelInfo(patternChannel, audioChannel));
	}

	/**
	 * Returns all measure receptors for the given stereo channel.
	 *
	 * @param audioChannel the stereo channel to filter by
	 * @return a list of matching receptors, empty if measure monitoring is disabled
	 */
	public List<Receptor<PackedCollection>> getMeasures(ChannelInfo.StereoChannel audioChannel) {
		if (measures == null) return List.of();

		return measures.keySet().stream()
				.filter(c -> c.matches(audioChannel))
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

	/**
	 * Builds operations pushing the given frame to both master-mix stereo receptors,
	 * once per stereo channel. A stereo channel whose receptor is absent (a mono or
	 * disabled master) is skipped.
	 *
	 * @param frame producer of the frame to push
	 * @return the push operations, empty when master output is disabled
	 */
	public Supplier<Runnable> pushMaster(Producer<PackedCollection> frame) {
		return pushToStereo("Master Push", frame, this::getMaster);
	}

	/**
	 * Builds operations pushing the given frame to the measure receptors of the given
	 * voicing, once per stereo channel. A stereo channel the output does not monitor
	 * is skipped.
	 *
	 * @param voicing the signal path voicing the frame belongs to
	 * @param frame   producer of the frame to push
	 * @return the push operations, empty when measure monitoring is disabled
	 */
	public Supplier<Runnable> pushMeasures(ChannelInfo.Voicing voicing,
										   Producer<PackedCollection> frame) {
		return pushToStereo("Measure Push", frame,
				stereo -> getMeasure(voicing, stereo));
	}

	/**
	 * Builds operations pushing the given frames to the stem receptors of the given
	 * audio channel, once per stereo channel. A stereo channel the output does not
	 * capture is skipped.
	 *
	 * @param audioChannel the audio channel whose stem receives the frames
	 * @param frames       producer of the frames to push
	 * @return the push operations, empty when stem output is disabled
	 */
	public Supplier<Runnable> pushStem(int audioChannel,
									   Producer<PackedCollection> frames) {
		return pushToStereo("Stem Push", frames,
				stereo -> getStem(audioChannel, stereo));
	}

	/**
	 * Builds operations pushing the given frame to the receptor each stereo channel
	 * resolves to, skipping a stereo channel with no receptor. Shared by
	 * {@link #pushMaster}, {@link #pushMeasures}, and {@link #pushStem}, which differ
	 * only in which receptor family the lookup draws from.
	 *
	 * @param name   the operation list name
	 * @param frame  producer of the frame to push
	 * @param lookup the per-stereo-channel receptor lookup
	 * @return the push operations, empty when none of the receptors are present
	 */
	private Supplier<Runnable> pushToStereo(String name, Producer<PackedCollection> frame,
			Function<ChannelInfo.StereoChannel, Receptor<PackedCollection>> lookup) {
		OperationList ops = new OperationList(name);
		for (ChannelInfo.StereoChannel stereo : ChannelInfo.StereoChannel.values()) {
			Receptor<PackedCollection> receptor = lookup.apply(stereo);
			if (receptor != null) ops.add(receptor.push(frame));
		}
		return ops;
	}

	/** Returns {@code true} if measure monitoring is active. */
	public boolean isMeasuresActive() { return measures != null; }

	/** Returns {@code true} if stem output is active. */
	public boolean isStemsActive() { return stems != null; }

	/**
	 * Retrieves the single receptor whose channel matches the predicate, from one of
	 * this output's receptor maps.
	 *
	 * @param receptors the receptor map to search
	 * @param predicate the channel predicate
	 * @return the matching receptor, or {@code null} if no channel matches
	 * @throws IllegalArgumentException if more than one channel matches
	 */
	protected Receptor<PackedCollection> retrieve(Map<ChannelInfo, Receptor<PackedCollection>> receptors,
												  Predicate<ChannelInfo> predicate) {
		if (receptors == null) return null;

		List<Receptor<PackedCollection>> values = receptors.entrySet().stream()
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
}
