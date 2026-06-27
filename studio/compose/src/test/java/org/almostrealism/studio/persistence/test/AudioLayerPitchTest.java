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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.studio.persistence.AudioLayerPitch;
import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Tests for {@link AudioLayerPitch}, the single accessor for a layer's
 * captured pitch.
 */
public class AudioLayerPitchTest {

	/** The structured field wins when it disagrees with the display text. */
	@Test
	public void prefersStructuredFieldOverLayerId() {
		/* layer_id text says C4 (60) but the structured field says 61 —
		   the structured field is authoritative and must win. */
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("DLSMusicDevice C4")
				.setRootMidiNote(61)
				.build();

		assertEquals(OptionalInt.of(61), AudioLayerPitch.capturedMidiNote(layer));
		assertSame(WesternChromatic.CS4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** Presence-tracking distinguishes a set 0 from an unset field. */
	@Test
	public void readsStructuredFieldAtZero() {
		/* MIDI note 0 is a valid note (C-1); proto presence-tracking must
		   distinguish it from an unset field. */
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("Plugin C-1")
				.setRootMidiNote(0)
				.build();

		assertEquals(OptionalInt.of(0), AudioLayerPitch.capturedMidiNote(layer));
	}

	/** Legacy layers without the field recover pitch from the display text. */
	@Test
	public void fallsBackToLayerIdParseForLegacyLayers() {
		/* A layer saved before root_midi_note existed: pitch lives only in
		   the trailing token of the display name. */
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("DLSMusicDevice C#4")
				.build();

		assertEquals(OptionalInt.of(61), AudioLayerPitch.capturedMidiNote(layer));
		assertSame(WesternChromatic.CS4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** Only the trailing token is parsed, so spaced plugin names are fine. */
	@Test
	public void fallbackHandlesMultiWordPluginName() {
		Audio.AudioLayer layer = Audio.AudioLayer.newBuilder()
				.setLayerId("My Fancy Synth A4")
				.build();

		assertEquals(OptionalInt.of(69), AudioLayerPitch.capturedMidiNote(layer));
		assertSame(WesternChromatic.A4, AudioLayerPitch.capturedKeyPosition(layer));
	}

	/** No structured field and no parseable note name yields empty/null. */
	@Test
	public void returnsEmptyWhenNoPitchRecoverable() {
		Audio.AudioLayer noPitch = Audio.AudioLayer.newBuilder()
				.setLayerId("Recording without a note name")
				.build();
		assertEquals(OptionalInt.empty(), AudioLayerPitch.capturedMidiNote(noPitch));
		assertNull(AudioLayerPitch.capturedKeyPosition(noPitch));

		assertEquals(OptionalInt.empty(), AudioLayerPitch.capturedMidiNote(null));
		assertNull(AudioLayerPitch.capturedKeyPosition(null));
	}

	/** MIDI-to-key-position mapping anchors against known reference notes. */
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

		KeyPosition<?> belowRange = AudioLayerPitch.capturedKeyPosition(
				Audio.AudioLayer.newBuilder().setRootMidiNote(0).build());
		assertNull("MIDI 0 (C-1) is below the WesternChromatic range", belowRange);
	}
}
