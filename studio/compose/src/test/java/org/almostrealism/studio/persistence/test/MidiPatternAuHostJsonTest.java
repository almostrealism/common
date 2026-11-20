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
import org.almostrealism.io.JsonString;
import org.almostrealism.studio.persistence.MidiPatternPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Pins the exact JSON lines {@link MidiPatternPersistence#toAuHostJsonCommands}
 * writes for the AU host, and in particular how the plugin id is written into
 * the {@code "id"} string literal: the id must always yield a well-formed JSON
 * string, whatever characters it carries.
 */
public class MidiPatternAuHostJsonTest extends TestSuiteBase {

	/** A plain id is written verbatim, and the whole line has the documented shape. */
	@Test(timeout = 30000)
	public void plainIdProducesExactCommandLines() {
		List<String> commands = commandsFor("plugin-7");
		Assert.assertEquals(2, commands.size());
		Assert.assertEquals(
				"{\"cmd\":\"midi\",\"id\":\"plugin-7\",\"type\":\"note_on\",\"note\":60,\"velocity\":100}",
				commands.get(0));
		Assert.assertEquals(
				"{\"cmd\":\"midi\",\"id\":\"plugin-7\",\"type\":\"note_off\",\"note\":60,\"velocity\":0}",
				commands.get(1));
	}

	/** Quote, backslash, newline, carriage return and tab in the id use their short escapes. */
	@Test(timeout = 30000)
	public void quoteBackslashAndWhitespaceAreEscaped() {
		Assert.assertEquals("a\\\"b\\\\c\\nd\\re\\tf", idOf(commandsFor("a\"b\\c\nd\re\tf").get(0)));
	}

	/** A {@code null} id is written as the empty string. */
	@Test(timeout = 30000)
	public void nullIdIsWrittenAsEmptyString() {
		Assert.assertEquals("", idOf(commandsFor(null).get(0)));
	}

	/** Non-ASCII text and characters at or above 0x20 pass through unchanged. */
	@Test(timeout = 30000)
	public void nonControlCharactersPassThrough() {
		Assert.assertEquals("Synth é中 /\u007f", idOf(commandsFor("Synth é中 /\u007f").get(0)));
	}

	/**
	 * Control characters JSON forbids in a string literal — backspace, form
	 * feed, ESC and the rest of C0 — are escaped rather than written raw, as
	 * {@link JsonString} writes every JSON string in the platform. A raw
	 * control character makes the line invalid JSON for a strict parser.
	 */
	@Test(timeout = 30000)
	public void otherControlCharactersAreEscaped() {
		String id = idOf(commandsFor("x\b\f\u001b\u0000y").get(0));
		Assert.assertEquals("x\\b\\f\\u001b\\u0000y", id);
		Assert.assertEquals(new JsonString("x\b\f\u001b\u0000y").escaped(), id);
	}

	/**
	 * Returns the AU host commands for a single note (pitch 60, velocity 100,
	 * one quarter note at tick 0) addressed to {@code pluginId}.
	 *
	 * @param pluginId the AU plugin instance id, or {@code null}
	 * @return the note_on and note_off command lines
	 */
	private static List<String> commandsFor(String pluginId) {
		Audio.MidiPattern pattern = Audio.MidiPattern.newBuilder()
				.setTicksPerQuarter(480)
				.addEvents(Audio.MidiEvent.newBuilder()
						.setTick(0)
						.setNote(Audio.NoteEvent.newBuilder()
								.setPitch(60)
								.setVelocity(100)
								.setDurationTicks(480)))
				.build();
		return MidiPatternPersistence.toAuHostJsonCommands(pattern, pluginId);
	}

	/**
	 * Returns the raw (still escaped) contents of the {@code "id"} literal in
	 * one command line.
	 *
	 * @param command a command line produced by {@link MidiPatternPersistence#toAuHostJsonCommands}
	 * @return the text between {@code "id":"} and {@code ","type"}
	 */
	private static String idOf(String command) {
		String start = "\"id\":\"";
		int from = command.indexOf(start) + start.length();
		int to = command.indexOf("\",\"type\":", from);
		return command.substring(from, to);
	}
}
