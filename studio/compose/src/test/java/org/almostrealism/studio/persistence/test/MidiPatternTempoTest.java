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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.studio.persistence.MidiPatternPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Tests for the Set Tempo meta event (type {@code 0x51}) handling of
 * {@link MidiPatternPersistence#toSmfBytes(Audio.MidiPattern)} and
 * {@link MidiPatternPersistence#fromSmfBytes(byte[])}.
 */
public class MidiPatternTempoTest extends TestSuiteBase {

	/** Ticks per quarter note for the patterns built by these tests. */
	private static final int PPQ = 480;

	/**
	 * A tempo is encoded as the whole number of microseconds per beat.
	 */
	@Test(timeout = 30000)
	public void smfEncodesTempoAsMicrosecondsPerBeat() throws Exception {
		assertEquals("Microseconds per beat at 120 BPM", 500_000, encodedTempo(120));
		assertEquals("Microseconds per beat at 90 BPM", 666_666, encodedTempo(90));
	}

	/**
	 * A tempo survives an SMF round trip at the BPM it was written with.
	 */
	@Test(timeout = 30000)
	public void smfTempoRoundTripsBpm() throws Exception {
		assertEquals("BPM after a round trip at 120", 120, roundTripBpm(120));
		assertEquals("BPM after a round trip at 90", 90, roundTripBpm(90));
		assertEquals("BPM after a round trip at 200", 200, roundTripBpm(200));
	}

	/**
	 * A Set Tempo event holding zero microseconds per beat decodes to zero BPM
	 * rather than dividing by zero.
	 */
	@Test(timeout = 30000)
	public void smfDecodesZeroTempoAsZeroBpm() throws Exception {
		Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
		Track track = sequence.createTrack();
		track.add(new MidiEvent(new MetaMessage(0x51, new byte[] {0, 0, 0}, 3), 0));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		MidiSystem.write(sequence, 1, out);

		assertEquals("BPM of a zero tempo", 0, decodedBpm(out.toByteArray()));
	}

	/**
	 * A pattern whose tempo is not positive (including an unset proto field)
	 * is written at the MIDI default of 120 BPM, as {@code MidiFileReader}
	 * writes it, rather than as 1 BPM, whose 60,000,000 microseconds per beat
	 * wrapped in the 24-bit Set Tempo field to a tempo of about 6.2 BPM.
	 */
	@Test(timeout = 30000)
	public void smfEncodesNonPositiveTempoAsDefault() throws Exception {
		assertEquals("Microseconds per beat for a zero tempo", 500_000, encodedTempo(0));
		assertEquals("Microseconds per beat for a negative tempo", 500_000, encodedTempo(-5));
	}

	/**
	 * A BPM too slow for the 24-bit Set Tempo field is clamped to the slowest
	 * tempo the field can hold instead of wrapping to a faster one.
	 */
	@Test(timeout = 30000)
	public void smfClampsTempoSlowerThanTheSetTempoField() throws Exception {
		assertEquals("Microseconds per beat at 2 BPM", 0xFFFFFF, encodedTempo(2));
	}

	/**
	 * Builds a pattern holding a single SET_TEMPO event at {@code bpm}.
	 *
	 * @param bpm the tempo in beats per minute
	 * @return the pattern
	 */
	private static Audio.MidiPattern tempoPattern(int bpm) {
		return Audio.MidiPattern.newBuilder()
				.setTicksPerQuarter(PPQ)
				.addEvents(Audio.MidiEvent.newBuilder()
						.setTick(0)
						.setSetTempo(Audio.SetTempoEvent.newBuilder().setBpm(bpm)))
				.build();
	}

	/**
	 * Encodes a single SET_TEMPO event to SMF bytes and returns the
	 * microseconds per beat stored in them.
	 *
	 * @param bpm the tempo in beats per minute
	 * @return the encoded microseconds per beat
	 */
	private static int encodedTempo(int bpm) throws Exception {
		byte[] smf = MidiPatternPersistence.toSmfBytes(tempoPattern(bpm));
		Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(smf));

		for (Track track : sequence.getTracks()) {
			for (int i = 0; i < track.size(); i++) {
				if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
					byte[] data = meta.getData();
					return (data[0] & 0xFF) << 16 | (data[1] & 0xFF) << 8 | data[2] & 0xFF;
				}
			}
		}

		throw new AssertionError("No Set Tempo event was written");
	}

	/**
	 * Encodes a single SET_TEMPO event to SMF bytes and decodes it again.
	 *
	 * @param bpm the tempo in beats per minute
	 * @return the decoded BPM
	 */
	private static int roundTripBpm(int bpm) throws Exception {
		return decodedBpm(MidiPatternPersistence.toSmfBytes(tempoPattern(bpm)));
	}

	/**
	 * Decodes SMF bytes and returns the BPM of their only SET_TEMPO event.
	 *
	 * @param smf the SMF bytes
	 * @return the decoded BPM
	 */
	private static int decodedBpm(byte[] smf) throws Exception {
		Audio.MidiPattern decoded = MidiPatternPersistence.fromSmfBytes(smf);

		for (Audio.MidiEvent event : decoded.getEventsList()) {
			if (event.hasSetTempo()) return event.getSetTempo().getBpm();
		}

		throw new AssertionError("No SET_TEMPO event was decoded");
	}
}
