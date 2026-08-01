/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.persistence;

import io.almostrealism.code.Precision;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.assets.CollectionEncoder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Codec utilities for the multi-layer audio format
 * ({@link Audio.AudioLayerGroup} / {@link Audio.AudioLayer} / {@link Audio.LayerRef}).
 *
 * <p>An {@link Audio.AudioLayerGroup} is a peer of {@link Audio.WaveRecording} added
 * at {@link Audio.AudioLibraryData} field 5; existing serialised data remains
 * readable. This class provides helpers for:</p>
 *
 * <ul>
 *   <li>Looking up a layer by id ({@link #findLayer(Audio.AudioLayerGroup, String)})</li>
 *   <li>Walking the {@code derived_from} graph
 *       ({@link #walkDerivationChain(Audio.AudioLayerGroup, Audio.AudioLayer)})</li>
 *   <li>Resolving a {@link Audio.LayerRef} to its target layer plus channel subset
 *       ({@link #resolve(Audio.AudioLayerGroup, Audio.LayerRef)})</li>
 *   <li>Extracting a channel subset from a {@link Audio.WaveDetailData}
 *       ({@link #extractChannels(Audio.WaveDetailData, List)})</li>
 *   <li>Null-safe content access ({@link #audioOf(Audio.AudioLayer)},
 *       {@link #midiOf(Audio.AudioLayer)})</li>
 * </ul>
 *
 * <p>Audio bytes resolution for layers whose {@code WaveDetailData.data} is
 * omitted is handled by the existing
 * {@link AudioLibraryPersistence#createDetailsLoader(String) library
 * identifier-resolution path} — this class does not duplicate that mechanism.</p>
 */
public final class AudioLayerGroupPersistence {

	/** Utility class — not instantiable. */
	private AudioLayerGroupPersistence() {}

	/**
	 * Looks up a layer by id within a group.
	 *
	 * @param group   the group to search
	 * @param layerId the layer id to find
	 * @return the matching layer, or empty if not present
	 */
	public static Optional<Audio.AudioLayer> findLayer(Audio.AudioLayerGroup group, String layerId) {
		if (group == null || layerId == null) return Optional.empty();
		for (Audio.AudioLayer layer : group.getLayersList()) {
			if (layerId.equals(layer.getLayerId())) return Optional.of(layer);
		}
		return Optional.empty();
	}

	/**
	 * Returns the layers immediately referenced by {@code derived_from} on
	 * {@code layer}, in declaration order. Layers whose ids do not resolve to
	 * a layer in the group are skipped.
	 *
	 * @param group the group containing {@code layer} and its parents
	 * @param layer the layer whose parents to resolve
	 * @return the immediate parent layers
	 */
	public static List<Audio.AudioLayer> directParents(Audio.AudioLayerGroup group, Audio.AudioLayer layer) {
		List<Audio.AudioLayer> parents = new ArrayList<>();
		for (Audio.LayerRef ref : layer.getDerivedFromList()) {
			findLayer(group, ref.getLayerId()).ifPresent(parents::add);
		}
		return parents;
	}

	/**
	 * Walks the derivation graph upwards from {@code layer}, returning every
	 * ancestor reachable via {@code derived_from} together with {@code layer}
	 * itself. Each layer appears once. Walk order is breadth-first; this
	 * matches the typical "show me everything that contributed to this
	 * rendering" inspection idiom.
	 *
	 * <p>Cycles are not enforced by the wire format; the walk treats already-
	 * visited layer ids as terminators rather than recursing into them, so
	 * a malformed group surfaces as a finite traversal rather than an
	 * infinite loop.</p>
	 *
	 * @param group the group to walk within
	 * @param layer the starting layer
	 * @return ordered list starting with {@code layer} followed by its ancestors
	 */
	public static List<Audio.AudioLayer> walkDerivationChain(Audio.AudioLayerGroup group, Audio.AudioLayer layer) {
		List<Audio.AudioLayer> visited = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Deque<Audio.AudioLayer> queue = new ArrayDeque<>();
		queue.add(layer);
		while (!queue.isEmpty()) {
			Audio.AudioLayer current = queue.poll();
			if (!seen.add(current.getLayerId())) continue;
			visited.add(current);
			for (Audio.AudioLayer parent : directParents(group, current)) {
				if (!seen.contains(parent.getLayerId())) queue.add(parent);
			}
		}
		return visited;
	}

	/**
	 * Resolves a {@link Audio.LayerRef} against a group, returning the target
	 * layer and the requested channel selection.
	 *
	 * @param group the group containing the referenced layer
	 * @param ref   the reference to resolve
	 * @return the resolved reference, or empty if {@code ref.layer_id} is not present
	 */
	public static Optional<ResolvedLayerRef> resolve(Audio.AudioLayerGroup group, Audio.LayerRef ref) {
		Optional<Audio.AudioLayer> layer = findLayer(group, ref.getLayerId());
		return layer.map(audioLayer -> new ResolvedLayerRef(audioLayer, ref.getChannelsList()));
	}

	/**
	 * Returns the audio payload of a layer, or empty if the layer carries a
	 * MIDI payload or no payload at all.
	 *
	 * @param layer the layer to inspect
	 * @return the audio payload if present
	 */
	public static Optional<Audio.WaveDetailData> audioOf(Audio.AudioLayer layer) {
		return layer != null && layer.hasAudio() ? Optional.of(layer.getAudio()) : Optional.empty();
	}

	/**
	 * Returns the MIDI payload of a layer, or empty if the layer carries an
	 * audio payload or no payload at all.
	 *
	 * @param layer the layer to inspect
	 * @return the MIDI payload if present
	 */
	public static Optional<Audio.MidiPattern> midiOf(Audio.AudioLayer layer) {
		return layer != null && layer.hasMidi() ? Optional.of(layer.getMidi()) : Optional.empty();
	}

	/**
	 * Returns the {@link Audio.TransformInfo} of a layer if set.
	 *
	 * @param layer the layer to inspect
	 * @return the transform info if present
	 */
	public static Optional<Audio.TransformInfo> transformOf(Audio.AudioLayer layer) {
		return layer != null && layer.hasTransform() ? Optional.of(layer.getTransform()) : Optional.empty();
	}

	/**
	 * Returns the {@link Audio.AudioUnitParameterState} of a layer if set.
	 *
	 * @param layer the layer to inspect
	 * @return the AU state if present
	 */
	public static Optional<Audio.AudioUnitParameterState> auStateOf(Audio.AudioLayer layer) {
		return layer != null && layer.hasAuState() ? Optional.of(layer.getAuState()) : Optional.empty();
	}

	/**
	 * Returns the device type of a layer if set, defaulting to
	 * {@link Audio.DeviceType#UNSPECIFIED}.
	 *
	 * @param layer the layer to inspect
	 * @return the device type, or {@code UNSPECIFIED} if unset
	 */
	public static Audio.DeviceType deviceTypeOf(Audio.AudioLayer layer) {
		return layer != null && layer.hasDeviceType() ? layer.getDeviceType() : Audio.DeviceType.UNSPECIFIED;
	}

	/**
	 * Returns the capture timestamp of a layer if set.
	 *
	 * @param layer the layer to inspect
	 * @return the {@code created_at_millis} value if set
	 */
	public static Optional<Long> createdAtOf(Audio.AudioLayer layer) {
		return layer != null && layer.hasCreatedAtMillis() ? Optional.of(layer.getCreatedAtMillis()) : Optional.empty();
	}

	/**
	 * Lifts a legacy {@link Audio.WaveRecording} into a single
	 * {@link Audio.AudioLayerGroup}, where each {@link Audio.WaveDetailData}
	 * in the recording becomes one audio-only {@link Audio.AudioLayer}. This
	 * provides uniform access to legacy and multi-layer data in callers that
	 * want a single API.
	 *
	 * @param recording the legacy recording to lift
	 * @return the lifted group
	 */
	public static Audio.AudioLayerGroup fromLegacy(Audio.WaveRecording recording) {
		Audio.AudioLayerGroup.Builder b = Audio.AudioLayerGroup.newBuilder()
				.setKey(recording.getKey());
		if (recording.hasGroupKey()) b.setGroupKey(recording.getGroupKey());
		if (recording.hasGroupOrderIndex()) b.setGroupOrderIndex(recording.getGroupOrderIndex());
		for (int i = 0; i < recording.getDataCount(); i++) {
			Audio.WaveDetailData detail = recording.getData(i);
			Audio.AudioLayer.Builder layer = Audio.AudioLayer.newBuilder()
					.setLayerId(legacyLayerId(recording, i))
					.setAudio(detail);
			b.addLayers(layer.build());
		}
		return b.build();
	}

	/**
	 * Generates a deterministic layer id for a legacy {@link Audio.WaveRecording}
	 * detail being lifted into an {@link Audio.AudioLayerGroup}.
	 *
	 * @param recording the source recording
	 * @param index     the detail index within the recording
	 * @return the synthesized layer id
	 */
	private static String legacyLayerId(Audio.WaveRecording recording, int index) {
		String key = recording.getKey();
		if (key == null || key.isEmpty()) key = "recording";
		return key + ":" + index;
	}

	/**
	 * Returns a copy of {@code source} with only the requested channels,
	 * preserving frame count and sample rate. When {@code channels} is empty
	 * or null, {@code source} is returned unchanged. The output's
	 * {@code channel_count} reflects the size of {@code channels}; when
	 * {@code source} carries inline audio data, that data is sliced to the
	 * requested channels (channel-major layout, matching
	 * {@link org.almostrealism.audio.data.WaveData#getChannelData(int)}).
	 * When {@code source.data} is omitted, only the metadata is adjusted —
	 * the audio bytes still resolve via the existing
	 * {@code WaveDetailData.identifier} library lookup.
	 *
	 * @param source   the buffer to subset
	 * @param channels zero-indexed channel selection
	 * @return the channel-subset buffer
	 * @throws IndexOutOfBoundsException if any requested channel is outside
	 *         {@code [0, source.channel_count)}
	 */
	public static Audio.WaveDetailData extractChannels(Audio.WaveDetailData source, List<Integer> channels) {
		if (source == null) return null;
		if (channels == null || channels.isEmpty()) return source;
		int sourceChannels = source.getChannelCount();
		for (int c : channels) {
			if (c < 0 || c >= sourceChannels) {
				throw new IndexOutOfBoundsException("channel " + c + " out of [0, " + sourceChannels + ")");
			}
		}

		Audio.WaveDetailData.Builder out = source.toBuilder().setChannelCount(channels.size());
		if (source.hasData()) {
			out.setData(CollectionEncoder.encode(sliceChannels(source, channels), Precision.FP32));
		}
		return out.build();
	}

	/**
	 * Returns a new {@link PackedCollection} populated with the requested
	 * channel rows of {@code source} in {@code channels} order.
	 *
	 * @param source   the source buffer (contains channel-major audio data)
	 * @param channels zero-indexed channel selection
	 * @return the channel-subset audio data
	 */
	private static PackedCollection sliceChannels(Audio.WaveDetailData source, List<Integer> channels) {
		PackedCollection full = CollectionEncoder.decode(source.getData());
		int frameCount = source.getFrameCount();
		PackedCollection sliced = new PackedCollection(channels.size(), frameCount);
		for (int outIdx = 0; outIdx < channels.size(); outIdx++) {
			int srcIdx = channels.get(outIdx);
			sliced.setFrom(outIdx * frameCount, full, srcIdx * frameCount, frameCount);
		}
		return sliced;
	}

	/**
	 * Builds an order-preserving map of {@code layer_id -> AudioLayer} for the
	 * group. Useful for callers that need repeated lookups without scanning.
	 *
	 * @param group the group to index
	 * @return ordered mapping from layer id to layer
	 */
	public static Map<String, Audio.AudioLayer> indexByLayerId(Audio.AudioLayerGroup group) {
		Map<String, Audio.AudioLayer> index = new LinkedHashMap<>();
		for (Audio.AudioLayer layer : group.getLayersList()) {
			index.put(layer.getLayerId(), layer);
		}
		return Collections.unmodifiableMap(index);
	}

	/**
	 * Result of resolving a {@link Audio.LayerRef} against an
	 * {@link Audio.AudioLayerGroup}: the target layer plus the requested
	 * channel selection (empty selection means "all channels").
	 *
	 * <p>The resolved layer is returned in its original form. Use
	 * {@link #extractedAudio()} to materialise an audio buffer reduced to the
	 * requested channels.</p>
	 */
	public static final class ResolvedLayerRef {
		/** The resolved target layer. */
		private final Audio.AudioLayer layer;
		/** The requested channel selection (empty = all channels). */
		private final List<Integer> channels;

		/**
		 * Creates a resolved reference.
		 *
		 * @param layer    the target layer
		 * @param channels the requested channel selection
		 */
		ResolvedLayerRef(Audio.AudioLayer layer, List<Integer> channels) {
			this.layer = layer;
			this.channels = channels == null
					? Collections.emptyList()
					: List.copyOf(channels);
		}

		/** Returns the resolved layer. */
		public Audio.AudioLayer getLayer() { return layer; }

		/**
		 * Returns the requested channel indices. An empty list means "all
		 * channels of the referenced layer".
		 */
		public List<Integer> getChannels() { return channels; }

		/**
		 * Returns the layer's audio buffer reduced to the requested channels,
		 * or empty if the layer is not an audio layer.
		 *
		 * @return the channel-extracted audio buffer
		 */
		public Optional<Audio.WaveDetailData> extractedAudio() {
			if (!layer.hasAudio()) return Optional.empty();
			return Optional.of(extractChannels(layer.getAudio(), channels));
		}
	}
}
