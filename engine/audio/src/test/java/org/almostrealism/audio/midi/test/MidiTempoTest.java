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

package org.almostrealism.audio.midi.test;

import org.almostrealism.audio.midi.MidiTempo;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import javax.sound.midi.MetaMessage;
import java.util.Optional;

/**
 * Tests for {@link MidiTempo}, the Set Tempo meta event shared by every
 * Standard MIDI File reader and writer.
 */
public class MidiTempoTest extends TestSuiteBase {

	/** A positive BPM is held as the whole number of microseconds per beat. */
	@Test(timeout = 30000)
	public void ofBpmUsesWholeMicrosecondsPerBeat() {
		assertEquals(500_000, MidiTempo.ofBpm(120).microsPerBeat());
		assertEquals(666_666, MidiTempo.ofBpm(90).microsPerBeat());
		assertEquals(1_000_000, MidiTempo.ofBpm(60).microsPerBeat());
	}

	/** A BPM that is not positive is the MIDI default of 120 BPM. */
	@Test(timeout = 30000)
	public void ofBpmTreatsNonPositiveTempoAsDefault() {
		assertEquals(MidiTempo.DEFAULT, MidiTempo.ofBpm(0));
		assertEquals(MidiTempo.DEFAULT, MidiTempo.ofBpm(-5));
		assertEquals(120, MidiTempo.DEFAULT.bpm());
	}

	/**
	 * A BPM too slow for the 24-bit field is clamped to the field's limit,
	 * while 4 BPM, the slowest whole tempo that fits, is kept exactly.
	 */
	@Test(timeout = 30000)
	public void ofBpmClampsToTheSetTempoField() {
		assertEquals(MidiTempo.MAX_MICROSECONDS_PER_BEAT, MidiTempo.ofBpm(1).microsPerBeat());
		assertEquals(MidiTempo.MAX_MICROSECONDS_PER_BEAT, MidiTempo.ofBpm(3).microsPerBeat());
		assertEquals(15_000_000, MidiTempo.ofBpm(4).microsPerBeat());
	}

	/** The BPM of a tempo is rounded, and a zero tempo has zero BPM. */
	@Test(timeout = 30000)
	public void bpmRoundsAndHandlesZero() {
		assertEquals(90, new MidiTempo(666_666).bpm());
		assertEquals(120, new MidiTempo(499_999).bpm());
		assertEquals(0, new MidiTempo(0).bpm());
	}

	/** A tempo outside the 24-bit field cannot be constructed. */
	@Test(timeout = 30000)
	public void constructorRejectsValuesOutsideTheSetTempoField() {
		assertRejected(-1);
		assertRejected(MidiTempo.MAX_MICROSECONDS_PER_BEAT + 1);
	}

	/**
	 * Asserts that constructing a tempo of {@code microsPerBeat} fails.
	 *
	 * @param microsPerBeat the out-of-range tempo
	 */
	private static void assertRejected(int microsPerBeat) {
		try {
			new MidiTempo(microsPerBeat);
		} catch (IllegalArgumentException expected) {
			return;
		}

		throw new AssertionError("A tempo of " + microsPerBeat + " microseconds per beat was accepted");
	}

	/** A tempo survives encoding to a meta message and reading it back. */
	@Test(timeout = 30000)
	public void metaMessageRoundTrips() throws Exception {
		for (int micros : new int[] {0, 500_000, 666_666, MidiTempo.MAX_MICROSECONDS_PER_BEAT}) {
			MetaMessage message = new MidiTempo(micros).toMetaMessage();
			assertEquals(MidiTempo.META_TYPE, message.getType());
			assertEquals(3, message.getData().length);
			assertEquals(Optional.of(new MidiTempo(micros)), MidiTempo.of(message));
		}
	}

	/** A meta message that is not a complete Set Tempo event has no tempo. */
	@Test(timeout = 30000)
	public void ofIgnoresOtherAndTruncatedMessages() throws Exception {
		assertEquals(Optional.empty(), MidiTempo.of(new MetaMessage(0x58, new byte[] {4, 2, 24, 8}, 4)));
		assertEquals(Optional.empty(), MidiTempo.of(new MetaMessage(MidiTempo.META_TYPE, new byte[] {7, 0xA}, 2)));
	}
}
