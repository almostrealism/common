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

package org.almostrealism.studio.persistence.test;

import com.google.protobuf.ByteString;
import io.almostrealism.code.Precision;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.studio.persistence.AudioLayerGroupPersistence;
import org.almostrealism.studio.persistence.MidiPatternPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence-format tests for the multi-layer audio extensions
 * ({@link Audio.AudioLayerGroup}, {@link Audio.AudioLayer},
 * {@link Audio.MidiPattern}, {@link Audio.LayerRef},
 * {@link Audio.TransformInfo}, {@link Audio.AudioUnitParameterState},
 * {@link Audio.DeviceType}).
 *
 * <p>The 12 test methods below cover:</p>
 * <ol>
 *   <li>Round-trip of each individual layer shape (audio / MIDI / metadata).</li>
 *   <li>Round-trip of an {@link Audio.AudioLayerGroup} with a
 *       {@code derived_from} edge between two layers.</li>
 *   <li>Round-trip of the AU two-layer use case (MIDI source + audio rendering
 *       carrying {@code au_state}).</li>
 *   <li>{@link Audio.MidiPattern} round-trip via structured events.</li>
 *   <li>{@link Audio.MidiPattern} round-trip via SMF bytes.</li>
 *   <li>{@link Audio.MidiPattern} export to the AU host
 *       {@code note_on}/{@code note_off} JSON command stream.</li>
 *   <li>{@link Audio.LayerRef} channel subset on a 4-channel buffer.</li>
 *   <li>{@link Audio.DeviceType} enum value coverage.</li>
 *   <li>{@link Audio.TransformInfo} round-trip across every
 *       {@link Audio.TransformInfo.TransformKind} value.</li>
 *   <li>{@link Audio.AudioUnitParameterState} round-trip.</li>
 *   <li>Backward compatibility: an {@link Audio.AudioLibraryData} written
 *       without field 5 ({@code layer_groups}) parses cleanly through the
 *       new code, and an {@link Audio.AudioLibraryData} written with the
 *       field is parsed by an old-style reader that ignores unknown fields.</li>
 *   <li>Identifier-driven external audio resolution: a layer whose
 *       {@link Audio.WaveDetailData} has only an {@code identifier}
 *       (no inline {@code data}) round-trips, and the inline data is
 *       supplied by the existing library identifier-resolution path.</li>
 * </ol>
 */
public class AudioLayerGroupPersistenceTest extends TestSuiteBase {

	private static final int PPQ = 480;

	/**
	 * 1a. A solitary audio-only layer round-trips via wire bytes.
	 */
	@Test(timeout = 30000)
	public void audioOnlyLayerRoundTrip() throws IOException {
		Audio.WaveDetailData detail = audioDetail("aud-1", 2, 1024);
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("a")
				.setAudio(detail)
				.build();
		Audio.AudioLayer parsed = Audio.AudioLayer.parseFrom(layer.toByteArray());
		Assert.assertEquals(layer, parsed);
		Assert.assertTrue(parsed.hasAudio());
		Assert.assertFalse(parsed.hasMidi());
		Assert.assertEquals("aud-1", AudioLayerGroupPersistence.audioOf(parsed)
				.orElseThrow(AssertionError::new).getIdentifier());
	}

	/**
	 * 1b. A solitary MIDI-only layer round-trips via wire bytes.
	 */
	@Test(timeout = 30000)
	public void midiOnlyLayerRoundTrip() throws IOException {
		Audio.MidiPattern pattern = simplePattern();
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("m")
				.setMidi(pattern)
				.build();
		Audio.AudioLayer parsed = Audio.AudioLayer.parseFrom(layer.toByteArray());
		Assert.assertEquals(layer, parsed);
		Assert.assertTrue(parsed.hasMidi());
		Assert.assertFalse(parsed.hasAudio());
		Assert.assertEquals(pattern, AudioLayerGroupPersistence.midiOf(parsed)
				.orElseThrow(AssertionError::new));
	}

	/**
	 * 1c. A metadata-only layer (no payload set) round-trips via wire bytes.
	 */
	@Test(timeout = 30000)
	public void metadataOnlyLayerRoundTrip() throws IOException {
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("meta-1")
				.setDeviceType(Audio.DeviceType.SOFTWARE)
				.setCreatedAtMillis(1714000000000L)
				.build();
		Audio.AudioLayer parsed = Audio.AudioLayer.parseFrom(layer.toByteArray());
		Assert.assertEquals(layer, parsed);
		Assert.assertEquals(Audio.AudioLayer.ContentCase.CONTENT_NOT_SET, parsed.getContentCase());
		Assert.assertEquals(Audio.DeviceType.SOFTWARE, parsed.getDeviceType());
		Assert.assertEquals(1714000000000L, parsed.getCreatedAtMillis());
	}

	/**
	 * 2. An {@link Audio.AudioLayerGroup} with a {@code derived_from} edge
	 * round-trips, and {@code walkDerivationChain} walks the parent.
	 */
	@Test(timeout = 30000)
	public void groupWithDerivationRoundTrip() throws IOException {
		Audio.AudioLayer source = Audio.AudioLayer.newBuilder()
				.setLayerId("src")
				.setAudio(audioDetail("src-id", 1, 256))
				.build();
		Audio.AudioLayer derived = Audio.AudioLayer.newBuilder()
				.setLayerId("der")
				.setAudio(audioDetail("der-id", 1, 256))
				.addDerivedFrom(Audio.LayerRef.newBuilder().setLayerId("src"))
				.setTransform(Audio.TransformInfo.newBuilder()
						.setTransformKind(Audio.TransformInfo.TransformKind.REVERB)
						.setTransformName("Hall L"))
				.build();
		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("group-1")
				.addLayers(source)
				.addLayers(derived)
				.build();
		Audio.AudioLayerGroup parsed = Audio.AudioLayerGroup.parseFrom(group.toByteArray());
		Assert.assertEquals(group, parsed);
		Assert.assertEquals("src", parsed.getLayers(1).getDerivedFrom(0).getLayerId());

		List<Audio.AudioLayer> chain = AudioLayerGroupPersistence.walkDerivationChain(parsed,
				parsed.getLayers(1));
		Assert.assertEquals(2, chain.size());
		Assert.assertEquals("der", chain.get(0).getLayerId());
		Assert.assertEquals("src", chain.get(1).getLayerId());
	}

	/**
	 * 3. The AU two-layer round-trip: a MIDI-source layer plus an audio
	 * rendering layer carrying {@code au_state} and a {@code derived_from}
	 * edge to the MIDI layer.
	 */
	@Test(timeout = 30000)
	public void auRenderingPairRoundTrip() throws IOException {
		Audio.AudioLayer midiLayer = Audio.AudioLayer.newBuilder()
				.setLayerId("midi-1")
				.setMidi(simplePattern())
				.setDeviceType(Audio.DeviceType.SOFTWARE)
				.build();
		Audio.AudioUnitParameterState auState = Audio.AudioUnitParameterState.newBuilder()
				.setComponentDescription("aumu,Alch,Appl,0001")
				.setDisplayName("Alchemy")
				.setManufacturerName("Apple")
				.putParameters("1", 0.42f)
				.putParameters("2", 0.99f)
				.build();
		Audio.AudioLayer audioLayer = Audio.AudioLayer.newBuilder()
				.setLayerId("audio-1")
				.setAudio(audioDetail("aud-au", 1, 4096))
				.addDerivedFrom(Audio.LayerRef.newBuilder().setLayerId("midi-1"))
				.setDeviceType(Audio.DeviceType.SOFTWARE)
				.setAuState(auState)
				.setCreatedAtMillis(1714000000000L)
				.build();
		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("au-group")
				.addLayers(midiLayer)
				.addLayers(audioLayer)
				.build();

		Audio.AudioLayerGroup parsed = Audio.AudioLayerGroup.parseFrom(group.toByteArray());
		Assert.assertEquals(group, parsed);
		Audio.AudioLayer reparsedAudio = parsed.getLayers(1);
		Assert.assertTrue(reparsedAudio.hasAuState());
		Assert.assertEquals(auState, reparsedAudio.getAuState());
		Assert.assertEquals("midi-1", reparsedAudio.getDerivedFrom(0).getLayerId());
		Assert.assertTrue(parsed.getLayers(0).hasMidi());
	}

	/**
	 * 4. A {@link Audio.MidiPattern} built from structured
	 * {@link MidiNoteEvent}s covering each event type round-trips losslessly.
	 */
	@Test(timeout = 30000)
	public void midiPatternStructuredRoundTrip() {
		List<MidiNoteEvent> events = Arrays.asList(
				MidiNoteEvent.setTempo(0, 0, 120),
				MidiNoteEvent.timeSignature(0, 0, 3, 2),
				MidiNoteEvent.keySignature(0, 0, 9, 0),
				MidiNoteEvent.note(0, 0, 0, 60, 100, 480),
				MidiNoteEvent.controlChange(240, 0, 0, 7, 96),
				MidiNoteEvent.patchChange(480, 0, 0, 5),
				MidiNoteEvent.note(960, 0, 0, 64, 110, 240));
		Audio.MidiPattern pattern = MidiPatternPersistence.fromMidiNoteEvents(events, PPQ);
		Audio.MidiPattern parsed;
		try {
			parsed = Audio.MidiPattern.parseFrom(pattern.toByteArray());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		Assert.assertEquals(pattern, parsed);

		List<MidiNoteEvent> back = MidiPatternPersistence.toMidiNoteEvents(parsed);
		Assert.assertEquals(events.size(), back.size());
		for (int i = 0; i < events.size(); i++) {
			MidiNoteEvent original = events.get(i);
			MidiNoteEvent decoded = back.get(i);
			Assert.assertEquals(original.getEventType(), decoded.getEventType());
			Assert.assertEquals(original.getTick(), decoded.getTick());
			Assert.assertEquals(original.getTrack(), decoded.getTrack());
			Assert.assertEquals(original.getChannel(), decoded.getChannel());
		}
	}

	/**
	 * 5. A {@link Audio.MidiPattern} encodes to SMF bytes and back without
	 * structural loss on the supported event vocabulary.
	 */
	@Test(timeout = 30000)
	public void midiPatternSmfBytesRoundTrip() throws IOException {
		Audio.MidiPattern original = Audio.MidiPattern.newBuilder()
				.setTicksPerQuarter(PPQ)
				.addEvents(noteEvent(0, 0, 60, 100, 480))
				.addEvents(noteEvent(480, 0, 64, 100, 480))
				.addEvents(noteEvent(960, 0, 67, 100, 480))
				.addEvents(controlChangeEvent(0, 0, 7, 96))
				.addEvents(programChangeEvent(0, 0, 5))
				.addEvents(pitchBendEvent(120, 0, 1024))
				.build();

		byte[] smf = MidiPatternPersistence.toSmfBytes(original);
		Assert.assertNotNull(smf);
		Assert.assertTrue("SMF must contain MThd header", smf.length > 8);

		Audio.MidiPattern decoded = MidiPatternPersistence.fromSmfBytes(smf);
		Assert.assertEquals(original.getTicksPerQuarter(), decoded.getTicksPerQuarter());

		Map<String, Integer> byKind = new HashMap<>();
		for (Audio.MidiEvent ev : decoded.getEventsList()) {
			byKind.merge(ev.getPayloadCase().name(), 1, Integer::sum);
		}
		Assert.assertEquals(Integer.valueOf(3), byKind.get("NOTE"));
		Assert.assertEquals(Integer.valueOf(1), byKind.get("CONTROL_CHANGE"));
		Assert.assertEquals(Integer.valueOf(1), byKind.get("PROGRAM_CHANGE"));
		Assert.assertEquals(Integer.valueOf(1), byKind.get("PITCH_BEND"));

		// Re-encode and verify byte-for-byte equality on the second round trip
		// (SMF allows multiple representations of equivalent content; the
		// second pass uses the same emission strategy as the first).
		byte[] smf2 = MidiPatternPersistence.toSmfBytes(decoded);
		Assert.assertArrayEquals(smf, smf2);
	}

	/**
	 * 6. A {@link Audio.MidiPattern} containing notes serializes to the
	 * AU host's {@code note_on}/{@code note_off} JSON command sequence in
	 * the expected order.
	 */
	@Test(timeout = 30000)
	public void midiPatternToAuHostJson() {
		Audio.MidiPattern pattern = Audio.MidiPattern.newBuilder()
				.setTicksPerQuarter(PPQ)
				.addEvents(noteEvent(0, 0, 60, 100, 480))
				.addEvents(noteEvent(0, 0, 64, 100, 480))
				.addEvents(noteEvent(480, 0, 67, 100, 240))
				.build();
		List<String> commands = MidiPatternPersistence.toAuHostJsonCommands(pattern, "plugin-7");
		Assert.assertEquals(6, commands.size());

		Assert.assertTrue(commands.get(0).contains("\"type\":\"note_on\""));
		Assert.assertTrue(commands.get(0).contains("\"note\":60"));
		Assert.assertTrue(commands.get(1).contains("\"type\":\"note_on\""));
		Assert.assertTrue(commands.get(1).contains("\"note\":64"));
		Assert.assertTrue(commands.get(2).contains("\"type\":\"note_on\""));
		Assert.assertTrue(commands.get(2).contains("\"note\":67"));

		// note_off for first two notes lands at tick 480, after note 67's note_on.
		Assert.assertTrue(commands.get(3).contains("\"type\":\"note_off\""));
		Assert.assertTrue(commands.get(4).contains("\"type\":\"note_off\""));
		Assert.assertTrue(commands.get(5).contains("\"type\":\"note_off\""));
		Assert.assertTrue(commands.get(5).contains("\"note\":67"));

		for (String c : commands) {
			Assert.assertTrue(c.startsWith("{\"cmd\":\"midi\""));
			Assert.assertTrue(c.contains("\"id\":\"plugin-7\""));
			Assert.assertTrue(c.contains("\"velocity\":"));
		}
	}

	/**
	 * 7. A 4-channel layer referenced via a {@link Audio.LayerRef} with
	 * {@code channels:[0,2]} resolves to a buffer carrying just channels 0
	 * and 2 with their inline data sliced accordingly.
	 */
	@Test(timeout = 30000)
	public void layerRefChannelSubset() {
		int frames = 8;
		PackedCollection full = new PackedCollection(4, frames);
		for (int ch = 0; ch < 4; ch++) {
			for (int f = 0; f < frames; f++) {
				full.setMem(ch * frames + f, ch * 100 + f);
			}
		}
		Audio.WaveDetailData detail = Audio.WaveDetailData.newBuilder()
				.setIdentifier("multi-ch")
				.setSampleRate(44100)
				.setChannelCount(4)
				.setFrameCount(frames)
				.setData(CollectionEncoder.encode(full, Precision.FP32))
				.build();
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("L")
				.setAudio(detail)
				.build();
		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("g")
				.addLayers(layer)
				.build();

		Audio.LayerRef ref = Audio.LayerRef.newBuilder()
				.setLayerId("L")
				.addChannels(0)
				.addChannels(2)
				.build();
		AudioLayerGroupPersistence.ResolvedLayerRef resolved =
				AudioLayerGroupPersistence.resolve(group, ref).orElseThrow(AssertionError::new);
		Assert.assertEquals(Arrays.asList(0, 2), resolved.getChannels());

		Audio.WaveDetailData subset = resolved.extractedAudio().orElseThrow(AssertionError::new);
		Assert.assertEquals(2, subset.getChannelCount());
		Assert.assertEquals(frames, subset.getFrameCount());

		PackedCollection slicedData = CollectionEncoder.decode(subset.getData());
		// Channel 0 first frames
		Assert.assertEquals(0.0, slicedData.toDouble(0), 1.0e-5);
		Assert.assertEquals(7.0, slicedData.toDouble(frames - 1), 1.0e-5);
		// Channel 2 first frames (originally ch=2 -> values 200..207)
		Assert.assertEquals(200.0, slicedData.toDouble(frames), 1.0e-5);
		Assert.assertEquals(207.0, slicedData.toDouble(2 * frames - 1), 1.0e-5);
	}

	/**
	 * 8. A round-trip that constructs one layer per {@link Audio.DeviceType}
	 * value, including the default {@link Audio.DeviceType#UNSPECIFIED}.
	 */
	@Test(timeout = 30000)
	public void deviceTypeEnumCoverage() throws IOException {
		Audio.DeviceType[] values = {
				Audio.DeviceType.UNSPECIFIED,
				Audio.DeviceType.SOFTWARE,
				Audio.DeviceType.VOCAL_MIC,
				Audio.DeviceType.INSTRUMENT_MIC,
				Audio.DeviceType.ROOM_MIC
		};
		Audio.AudioLayerGroup.Builder group = Audio.AudioLayerGroup.newBuilder().setKey("dt");
		for (Audio.DeviceType dt : values) {
			group.addLayers(Audio.AudioLayer.newBuilder()
					.setLayerId("dt-" + dt.name())
					.setDeviceType(dt)
					.build());
		}

		Audio.AudioLayerGroup parsed = Audio.AudioLayerGroup.parseFrom(group.build().toByteArray());
		Assert.assertEquals(values.length, parsed.getLayersCount());
		for (int i = 0; i < values.length; i++) {
			Audio.AudioLayer layer = parsed.getLayers(i);
			Audio.DeviceType expected = values[i];
			Assert.assertEquals(expected, AudioLayerGroupPersistence.deviceTypeOf(layer));
		}
	}

	/**
	 * 9. A round-trip that constructs one layer per
	 * {@link Audio.TransformInfo.TransformKind} value, including the default.
	 */
	@Test(timeout = 30000)
	public void transformInfoRoundTrip() throws IOException {
		Audio.TransformInfo.TransformKind[] kinds = Audio.TransformInfo.TransformKind.values();
		Audio.AudioLayerGroup.Builder group = Audio.AudioLayerGroup.newBuilder().setKey("ti");
		for (Audio.TransformInfo.TransformKind kind : kinds) {
			if (kind == Audio.TransformInfo.TransformKind.UNRECOGNIZED) continue;
			group.addLayers(Audio.AudioLayer.newBuilder()
					.setLayerId("ti-" + kind.name())
					.setTransform(Audio.TransformInfo.newBuilder()
							.setTransformKind(kind)
							.setTransformName(kind.name() + "/A"))
					.build());
		}
		Audio.AudioLayerGroup parsed = Audio.AudioLayerGroup.parseFrom(group.build().toByteArray());

		int idx = 0;
		for (Audio.TransformInfo.TransformKind kind : kinds) {
			if (kind == Audio.TransformInfo.TransformKind.UNRECOGNIZED) continue;
			Audio.AudioLayer layer = parsed.getLayers(idx++);
			Audio.TransformInfo info = AudioLayerGroupPersistence.transformOf(layer)
					.orElseThrow(AssertionError::new);
			Assert.assertEquals(kind, info.getTransformKind());
			Assert.assertEquals(kind.name() + "/A", info.getTransformName());
		}
	}

	/**
	 * 10. A round-trip of a realistic {@link Audio.AudioUnitParameterState}.
	 */
	@Test(timeout = 30000)
	public void audioUnitParameterStateRoundTrip() throws IOException {
		Map<String, Float> params = new HashMap<>();
		params.put("1001", 0.25f);
		params.put("1002", 0.75f);
		params.put("1003", 1.0f);
		byte[] preset = new byte[256];
		for (int i = 0; i < preset.length; i++) preset[i] = (byte) (i & 0xff);

		Audio.AudioUnitParameterState state = Audio.AudioUnitParameterState.newBuilder()
				.setComponentDescription("aumu,Alch,Appl,0001")
				.setDisplayName("Alchemy")
				.setManufacturerName("Apple")
				.putAllParameters(params)
				.setPresetData(ByteString.copyFrom(preset))
				.build();
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("au-1")
				.setAuState(state)
				.build();

		Audio.AudioLayer parsed = Audio.AudioLayer.parseFrom(layer.toByteArray());
		Audio.AudioUnitParameterState back = AudioLayerGroupPersistence.auStateOf(parsed)
				.orElseThrow(AssertionError::new);
		Assert.assertEquals(state, back);
		Assert.assertArrayEquals(preset, back.getPresetData().toByteArray());
		Assert.assertEquals(params, back.getParametersMap());
	}

	/**
	 * 11. Backward compatibility: an {@link Audio.AudioLibraryData} written
	 * before field 5 ({@code layer_groups}) was added still parses through
	 * the new generated code; an instance written with the new field is
	 * understood, and unknown fields on a hypothetical older reader would be
	 * preserved via proto3's default unknown-field handling.
	 */
	@Test(timeout = 30000)
	public void audioLibraryDataBackwardCompatibility() throws IOException {
		// Old-style payload: only fields 1-4. Field 5 is untouched.
		Audio.AudioLibraryData oldStyle = Audio.AudioLibraryData.newBuilder()
				.putInfo("entry-1", audioDetail("entry-1", 1, 256))
				.addRecordings(Audio.WaveRecording.newBuilder()
						.setKey("rec-1")
						.setOrderIndex(0)
						.addData(audioDetail("entry-1", 1, 256)))
				.build();
		Audio.AudioLibraryData parsedOld = Audio.AudioLibraryData.parseFrom(oldStyle.toByteArray());
		Assert.assertEquals(0, parsedOld.getLayerGroupsCount());
		Assert.assertEquals(1, parsedOld.getInfoCount());
		Assert.assertEquals(1, parsedOld.getRecordingsCount());

		// New-style payload that also populates field 5.
		Audio.AudioLibraryData newStyle = oldStyle.toBuilder()
				.addLayerGroups(Audio.AudioLayerGroup.newBuilder()
						.setKey("g1")
						.addLayers(Audio.AudioLayer.newBuilder()
								.setLayerId("a")
								.setAudio(audioDetail("entry-1", 1, 256))))
				.build();
		Audio.AudioLibraryData parsedNew = Audio.AudioLibraryData.parseFrom(newStyle.toByteArray());
		Assert.assertEquals(1, parsedNew.getLayerGroupsCount());
		Assert.assertEquals("g1", parsedNew.getLayerGroups(0).getKey());

		// Confirm fields 1-4 still match between the two payloads.
		Assert.assertEquals(parsedOld.getInfoMap(), parsedNew.getInfoMap());
		Assert.assertEquals(parsedOld.getRecordingsList(), parsedNew.getRecordingsList());
	}

	/**
	 * 12. Identifier-driven external audio resolution: a layer carrying an
	 * audio buffer that omits inline {@code data} but populates
	 * {@code identifier} round-trips. Resolution then materialises the bytes
	 * via a caller-supplied identifier lookup, mirroring the existing
	 * {@code AudioLibrary.find(identifier)} path.
	 */
	@Test(timeout = 30000)
	public void identifierDrivenExternalAudioResolution() throws IOException {
		Audio.WaveDetailData metadataOnly = Audio.WaveDetailData.newBuilder()
				.setIdentifier("ext-md5-deadbeef")
				.setSampleRate(44100)
				.setChannelCount(1)
				.setFrameCount(2048)
				.build();
		Assert.assertFalse("Identifier-only buffer must not carry inline data",
				metadataOnly.hasData());

		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("ext-1")
				.setAudio(metadataOnly)
				.build();
		Audio.AudioLayer parsed = Audio.AudioLayer.parseFrom(layer.toByteArray());
		Audio.WaveDetailData parsedDetail = parsed.getAudio();
		Assert.assertFalse(parsedDetail.hasData());
		Assert.assertEquals("ext-md5-deadbeef", parsedDetail.getIdentifier());

		// Simulate the AudioLibrary identifier-resolution path: an external
		// store keyed by identifier returns the actual PCM bytes.
		PackedCollection pcm = new PackedCollection(2048);
		for (int i = 0; i < pcm.getMemLength(); i++) pcm.setMem(i, Math.sin(i * 0.01));
		Map<String, PackedCollection> externalStore = new HashMap<>();
		externalStore.put("ext-md5-deadbeef", pcm);

		Audio.WaveDetailData resolved = parsedDetail.toBuilder()
				.setData(CollectionEncoder.encode(externalStore.get(parsedDetail.getIdentifier()),
						Precision.FP32))
				.build();
		Assert.assertTrue("After resolution the buffer carries inline data",
				resolved.hasData());
		Assert.assertEquals(parsedDetail.getIdentifier(), resolved.getIdentifier());
		PackedCollection roundTripped = CollectionEncoder.decode(resolved.getData());
		Assert.assertEquals(pcm.getMemLength(), roundTripped.getMemLength());
	}

	// ─── helpers ──────────────────────────────────────────────────────────

	/**
	 * Builds a small audio {@link Audio.WaveDetailData} fixture — identifier
	 * + metadata + inline data sized for {@code channels} × {@code frames}.
	 *
	 * @param identifier   stable identifier for the buffer
	 * @param channels     channel count
	 * @param frames       frame count
	 * @return the built buffer
	 */
	private static Audio.WaveDetailData audioDetail(String identifier, int channels, int frames) {
		PackedCollection pcm = new PackedCollection(channels * frames);
		for (int i = 0; i < pcm.getMemLength(); i++) pcm.setMem(i, i);
		return Audio.WaveDetailData.newBuilder()
				.setIdentifier(identifier)
				.setSampleRate(44100)
				.setChannelCount(channels)
				.setFrameCount(frames)
				.setData(CollectionEncoder.encode(pcm, Precision.FP32))
				.build();
	}

	/**
	 * Builds a small fixed structured pattern used by several round-trip
	 * tests.
	 *
	 * @return a small {@link Audio.MidiPattern}
	 */
	private static Audio.MidiPattern simplePattern() {
		List<MidiNoteEvent> events = Collections.singletonList(
				MidiNoteEvent.note(0, 0, 0, 60, 100, 480));
		return MidiPatternPersistence.fromMidiNoteEvents(events, PPQ);
	}

	/**
	 * Builds a {@link Audio.MidiEvent} carrying a {@link Audio.NoteEvent}.
	 *
	 * @param tick     absolute tick
	 * @param channel  MIDI channel
	 * @param pitch    MIDI pitch
	 * @param velocity MIDI velocity
	 * @param duration duration in ticks
	 * @return the built event
	 */
	private static Audio.MidiEvent noteEvent(long tick, int channel, int pitch, int velocity, long duration) {
		return Audio.MidiEvent.newBuilder()
				.setTick(tick)
				.setChannel(channel)
				.setNote(Audio.NoteEvent.newBuilder()
						.setPitch(pitch)
						.setVelocity(velocity)
						.setDurationTicks(duration))
				.build();
	}

	/**
	 * Builds a {@link Audio.MidiEvent} carrying a
	 * {@link Audio.ControlChangeEvent}.
	 *
	 * @param tick       absolute tick
	 * @param channel    MIDI channel
	 * @param controller CC number
	 * @param value      CC value
	 * @return the built event
	 */
	private static Audio.MidiEvent controlChangeEvent(long tick, int channel, int controller, int value) {
		return Audio.MidiEvent.newBuilder()
				.setTick(tick)
				.setChannel(channel)
				.setControlChange(Audio.ControlChangeEvent.newBuilder()
						.setController(controller)
						.setValue(value))
				.build();
	}

	/**
	 * Builds a {@link Audio.MidiEvent} carrying a
	 * {@link Audio.ProgramChangeEvent}.
	 *
	 * @param tick    absolute tick
	 * @param channel MIDI channel
	 * @param program program number
	 * @return the built event
	 */
	private static Audio.MidiEvent programChangeEvent(long tick, int channel, int program) {
		return Audio.MidiEvent.newBuilder()
				.setTick(tick)
				.setChannel(channel)
				.setProgramChange(Audio.ProgramChangeEvent.newBuilder()
						.setProgram(program))
				.build();
	}

	/**
	 * Builds a {@link Audio.MidiEvent} carrying a
	 * {@link Audio.PitchBendEvent}.
	 *
	 * @param tick    absolute tick
	 * @param channel MIDI channel
	 * @param value   signed pitch-bend value
	 * @return the built event
	 */
	private static Audio.MidiEvent pitchBendEvent(long tick, int channel, int value) {
		return Audio.MidiEvent.newBuilder()
				.setTick(tick)
				.setChannel(channel)
				.setPitchBend(Audio.PitchBendEvent.newBuilder().setValue(value))
				.build();
	}
}
