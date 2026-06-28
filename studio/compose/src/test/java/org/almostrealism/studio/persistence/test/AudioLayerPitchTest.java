/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.persistence.test;

import com.google.protobuf.InvalidProtocolBufferException;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.studio.persistence.AudioLayerPitch;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link AudioLayerPitch}, the single accessor for a layer's
 * captured pitch — modelled on the internal {@link KeyPosition} type rather
 * than on a MIDI note number.
 */
public class AudioLayerPitchTest {

	/** Builds a layer carrying {@code position} as its structured captured pitch. */
	private static Audio.AudioLayer layerWithPitch(String layerId, KeyPosition<?> position) {
		return Audio.AudioLayer.newBuilder()
				.setLayerId(layerId)
				.setCapturedPitch(AudioLayerPitch.toKeyPositionData(position))
				.build();
	}

	/** The structured field wins when it disagrees with the display text. */
	@Test
	public void prefersStructuredFieldOverLayerId() {
		/* layer_id text says C4 but the structured field says CS4 (C#4) —
		   the structured KeyPosition is authoritative and must win. */
		Audio.AudioLayer layer = layerWithPitch("DLSMusicDevice C4", WesternChromatic.CS4);
		assertSame(WesternChromatic.CS4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** The structured KeyPosition survives a real protobuf serialize/parse cycle. */
	@Test
	public void structuredKeyPositionSurvivesProtoRoundTrip() throws InvalidProtocolBufferException {
		for (WesternChromatic key : WesternChromatic.values()) {
			Audio.AudioLayer layer = layerWithPitch("Plugin " + key, key);
			Audio.AudioLayer reparsed = Audio.AudioLayer.parseFrom(layer.toByteArray());
			assertSame("KeyPosition must survive proto write + read for " + key,
					key, AudioLayerPitch.capturedKeyPosition(reparsed));
		}
	}

	/** Legacy layers without the field recover pitch from the display text. */
	@Test
	public void fallsBackToLayerIdParseForLegacyLayers() {
		/* A layer saved before captured_pitch existed: pitch lives only in
		   the trailing token of the display name. */
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("DLSMusicDevice C#4")
				.build();
		assertSame(WesternChromatic.CS4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** Only the trailing token is parsed, so spaced plugin names are fine. */
	@Test
	public void fallbackHandlesMultiWordPluginName() {
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("My Fancy Synth A4")
				.build();
		assertSame(WesternChromatic.A4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** No structured field and no parseable note name yields null. */
	@Test
	public void returnsNullWhenNoPitchRecoverable() {
		Audio.AudioLayer noPitch = Audio.AudioLayer.newBuilder()
				.setLayerId("Recording without a note name")
				.build();
		assertNull(AudioLayerPitch.capturedKeyPosition(noPitch));
		assertNull(AudioLayerPitch.capturedKeyPosition(null));
	}

	/**
	 * A structured field that cannot be reconstructed (out-of-range position)
	 * falls back to the legacy display-text parse rather than reporting no
	 * pitch outright.
	 */
	@Test
	public void unreconstructableStructuredFieldFallsBackToLayerId() {
		Audio.KeyPositionData outOfRange = Audio.KeyPositionData.newBuilder()
				.setSystem(Audio.KeyPositionData.PitchSystem.WESTERN_CHROMATIC)
				.setPosition(-1)
				.build();
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("Plugin C#4")
				.setCapturedPitch(outOfRange)
				.build();
		assertSame(WesternChromatic.CS4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** toKeyPositionData / fromKeyPositionData round-trips every WesternChromatic key. */
	@Test
	public void keyPositionDataRoundTripsEveryWesternChromatic() {
		for (WesternChromatic key : WesternChromatic.values()) {
			Audio.KeyPositionData data = AudioLayerPitch.toKeyPositionData(key);
			assertSame(Audio.KeyPositionData.PitchSystem.WESTERN_CHROMATIC, data.getSystem());
			assertEquals(key.position(), data.getPosition());
			assertSame("Round-trip must recover the identical key for " + key,
					key, AudioLayerPitch.fromKeyPositionData(data));
		}
	}

	/** Writing a null or unsupported KeyPosition is a programming error. */
	@Test
	public void toKeyPositionDataRejectsNull() {
		try {
			AudioLayerPitch.toKeyPositionData(null);
			fail("Expected IllegalArgumentException for a null KeyPosition");
		} catch (IllegalArgumentException expected) {
			/* The contract: a null position is a caller error, not a no-op. */
		}
	}

	/** fromKeyPositionData reports null for null, unspecified, or out-of-range input. */
	@Test
	public void fromKeyPositionDataHandlesUnknownInput() {
		assertNull(AudioLayerPitch.fromKeyPositionData(null));

		Audio.KeyPositionData unspecified = Audio.KeyPositionData.newBuilder()
				.setPosition(39)
				.build();
		assertNull("An unspecified pitch system is not reconstructable",
				AudioLayerPitch.fromKeyPositionData(unspecified));

		Audio.KeyPositionData tooHigh = Audio.KeyPositionData.newBuilder()
				.setSystem(Audio.KeyPositionData.PitchSystem.WESTERN_CHROMATIC)
				.setPosition(88)
				.build();
		assertNull("Position 88 is past C8 (87)",
				AudioLayerPitch.fromKeyPositionData(tooHigh));
	}

	/** MIDI-to-key-position bridge anchors against known reference notes. */
	@Test
	public void mapsMidiToWesternChromaticPositions() {
		/* WesternChromatic positions: A0 = 0 (MIDI 21), C4 = 39 (MIDI 60),
		   A4 = 48 (MIDI 69), C8 = 87 (MIDI 108). */
		assertSame(WesternChromatic.A0, AudioLayerPitch.midiToKeyPosition(21));
		assertEquals(39, AudioLayerPitch.midiToKeyPosition(60).position());
		assertSame(WesternChromatic.A4, AudioLayerPitch.midiToKeyPosition(69));
		assertSame(WesternChromatic.C8, AudioLayerPitch.midiToKeyPosition(108));
	}

	/** Valid MIDI notes outside A0..C8 have no WesternChromatic position. */
	@Test
	public void returnsNullForNotesOutsideWesternChromaticRange() {
		/* WesternChromatic spans MIDI 21 (A0) .. 108 (C8); notes outside
		   that have no key position even though they are valid MIDI. */
		assertNull("MIDI 20 is below A0", AudioLayerPitch.midiToKeyPosition(20));
		assertNull("MIDI 109 is above C8", AudioLayerPitch.midiToKeyPosition(109));
	}

	/** Sanity: the structured form never collapses through a MIDI integer. */
	@Test
	public void capturedPitchHasNoMidiField() {
		Audio.AudioLayer layer = layerWithPitch("Plugin C4", WesternChromatic.C4);
		assertTrue(layer.hasCapturedPitch());
		assertEquals(39, layer.getCapturedPitch().getPosition());
	}
}
