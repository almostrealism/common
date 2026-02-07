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

package org.almostrealism.audio.health;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public class MultiChannelAudioOutput {
	private final Map<ChannelInfo.StereoChannel, Receptor<PackedCollection>> master;
	private final Map<ChannelInfo, Receptor<PackedCollection>> stems;
	private final Map<ChannelInfo, Receptor<PackedCollection>> measures;

	private final Function<ChannelInfo.StereoChannel, Receptor<PackedCollection>> masterFactory;
	private final Function<ChannelInfo, Receptor<PackedCollection>> stemsFactory;
	private final Function<ChannelInfo, Receptor<PackedCollection>> measuresFactory;

	public MultiChannelAudioOutput() {
		this((Function) null, null, null);
	}

	public MultiChannelAudioOutput(WaveOutput masterOut) {
		this(masterOut, null);
	}

	public MultiChannelAudioOutput(
			WaveOutput masterOut, List<WaveOutput> stemsOut) {
		this(masterOut, stemsOut, null);
	}

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

	public Receptor<PackedCollection> getMeasure(ChannelInfo.Voicing voicing,
													ChannelInfo.StereoChannel audioChannel) {
		Receptor<PackedCollection> result =
				retrieve(measures, match(voicing).and(match(audioChannel)));
		if (result != null) return result;

		return getMeasure(new ChannelInfo(-1, voicing, audioChannel));
	}

	public Receptor<PackedCollection> getStem(int patternChannel,
												 ChannelInfo.StereoChannel audioChannel) {
		Receptor<PackedCollection> result =
				retrieve(stems, match(patternChannel).and(match(audioChannel)));
		if (result != null) return result;

		return getStem(new ChannelInfo(patternChannel, audioChannel));
	}

	public List<Receptor<PackedCollection>> getMeasures(ChannelInfo.StereoChannel audioChannel) {
		return measures.keySet().stream()
				.filter(match(audioChannel))
				.map(measures::get)
				.toList();
	}

	public Receptor<PackedCollection> getMeasure(ChannelInfo channel) {
		if (measures == null) {
			return null;
		} else if (measuresFactory != null) {
			return measures.computeIfAbsent(channel, measuresFactory);
		} else {
			return measures.get(channel);
		}
	}

	public Receptor<PackedCollection> getStem(ChannelInfo channel) {
		if (stems == null) {
			return null;
		} else if (stemsFactory != null) {
			return stems.computeIfAbsent(channel, stemsFactory);
		} else {
			return stems.get(channel);
		}
	}

	public Receptor<PackedCollection> getMaster(ChannelInfo.StereoChannel channel) {
		if (master == null) {
			return null;
		} else if (masterFactory != null) {
			return master.computeIfAbsent(channel, masterFactory);
		} else {
			return master.get(channel);
		}
	}

	public boolean isMeasuresActive() { return measures != null; }

	public boolean isStemsActive() { return stems != null; }

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

	protected Predicate<ChannelInfo> match(ChannelInfo.StereoChannel audioChannel) {
		return c -> c.getAudioChannel() == audioChannel;
	}

	protected Predicate<ChannelInfo> match(int patternChannel) {
		return c -> c.getPatternChannel() == patternChannel;
	}

	protected Predicate<ChannelInfo> match(ChannelInfo.Voicing voicing) {
		return c -> c.getVoicing() == voicing;
	}
}
