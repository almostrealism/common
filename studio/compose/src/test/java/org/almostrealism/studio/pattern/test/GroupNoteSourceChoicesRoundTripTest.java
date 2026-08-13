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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.audio.notes.NoteAudioGroup;
import org.almostrealism.music.notes.GroupNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.pattern.NoteAudioChoiceList;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Guards the choices file (the persisted {@link NoteAudioChoiceList}, written
 * as {@code pattern-factory.json}) against sources that cannot be read back.
 *
 * <p>The file is written on every scene save and read on every application
 * start. {@link org.almostrealism.music.notes.NoteAudioSource} is serialized
 * polymorphically by class name, so any source implementation that reaches a
 * choice must survive a write/read cycle through
 * {@link AudioSceneLoader#defaultMapper()}. A source that cannot fails the
 * whole file: the reader aborts on the first unreadable entry, and the caller
 * cannot recover the other choices from it.</p>
 */
public class GroupNoteSourceChoicesRoundTripTest extends TestSuiteBase {

	/**
	 * A choice carrying a {@link GroupNoteSource} must survive the write-then-read
	 * cycle the choices file performs. The group source itself is derived from the
	 * library rather than stored, so what must survive is the choice — the group
	 * source is re-added by the code that assembled it.
	 */
	@Test(timeout = 60000)
	public void choiceWithGroupSourceSurvivesChoicesFile() throws Exception {
		NoteAudioChoice choice = new NoteAudioChoice();
		choice.setName("Group Test");
		choice.setSources(new ArrayList<>(
				List.of(new GroupNoteSource(new NoteAudioGroup(List.of()), "test-group"))));

		NoteAudioChoiceList choices = new NoteAudioChoiceList();
		choices.add(choice);

		File file = File.createTempFile("group-choices", ".json");
		file.deleteOnExit();

		AudioSceneLoader.defaultMapper().writeValue(file, choices);
		String json = Files.readString(file.toPath());
		log("choicesJson=" + json);

		Assert.assertFalse("A derived group source was written into the choices file",
				json.contains(GroupNoteSource.class.getName()));

		NoteAudioChoiceList read = AudioSceneLoader.defaultMapper()
				.readValue(file, NoteAudioChoiceList.class);

		Assert.assertEquals("Choice count changed across the round trip",
				1, read.size());
		Assert.assertEquals("Choice name changed across the round trip",
				"Group Test", read.get(0).getName());
	}

	/**
	 * A choices file already containing a group source — written before those
	 * were excluded — must still load, minus the entry that cannot be
	 * constructed. Failing the whole read would discard every other choice the
	 * user has configured.
	 */
	@Test(timeout = 60000)
	public void unreadableStoredSourceDoesNotDiscardTheFile() throws Exception {
		String poisoned = "[{\"name\":\"Legacy\",\"sources\":["
				+ "{\"@type\":\"" + GroupNoteSource.class.getName() + "\",\"origin\":\"old-group\"}"
				+ "]}]";

		File file = File.createTempFile("legacy-choices", ".json");
		file.deleteOnExit();
		Files.writeString(file.toPath(), poisoned);

		NoteAudioChoiceList read = AudioSceneLoader.defaultMapper()
				.readValue(file, NoteAudioChoiceList.class);

		Assert.assertEquals("A stored source that cannot be constructed discarded the file",
				1, read.size());
		Assert.assertEquals("Legacy", read.get(0).getName());
		Assert.assertTrue("The unconstructable source was not dropped",
				read.get(0).getSources().isEmpty());
	}
}
