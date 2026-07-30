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

package org.almostrealism.music.notes.test;

import org.almostrealism.audio.notes.NoteAudioGroup;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.notes.GroupNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link GroupNoteSource}: a saved group surfaces as exactly one
 * selectable candidate, and an unusable (pitchless-only) group is filtered out
 * of the candidate set by the existing validity machinery.
 *
 * @see GroupNoteSource
 * @see NoteAudioGroup
 */
public class GroupNoteSourceTest extends TestSuiteBase {

	/** Shared tuning so members can compute frequency ratios. */
	private final KeyboardTuning tuning = new DefaultKeyboardTuning();

	/** Builds a tuned member over a small constant source at the given root. */
	private NoteAudioProvider member(KeyPosition<?> root) {
		PackedCollection source = new PackedCollection(1024);
		a(cp(source), sin(integers(0, 1024).multiply(2.0 * Math.PI * 4.0 / 1024.0))).get().run();
		NoteAudioProvider note = NoteAudioProvider.create(() -> source, root);
		note.setTuning(tuning);
		return note;
	}

	/**
	 * A group of several members is surfaced as a single {@link NoteAudioGroup}
	 * candidate: one note, one pattern note, one valid candidate in a choice.
	 */
	@Test(timeout = 30000)
	public void groupSurfacesAsSingleCandidate() {
		NoteAudioGroup group = new NoteAudioGroup(List.of(
				member(WesternChromatic.C4), member(WesternChromatic.C5)));
		GroupNoteSource source = new GroupNoteSource(group, "grp");

		Assert.assertEquals("Group is exactly one raw note", 1, source.getNotes().size());
		Assert.assertSame("The raw note is the group itself", group, source.getNotes().get(0));
		Assert.assertEquals("Group is exactly one pattern note", 1, source.getPatternNotes().size());

		NoteAudioChoice choice = NoteAudioChoice.fromSource("g", source, 0, 9, true);
		choice.setTuning(tuning);
		Assert.assertEquals("Group is one valid candidate in the choice",
				1, choice.getValidPatternNotes().size());
	}

	/**
	 * A pitchless-only group is invalid and is filtered out of the choice's valid
	 * candidate set — the feature degrades gracefully without producing a broken
	 * candidate.
	 */
	@Test(timeout = 30000)
	public void unusableGroupFilteredFromCandidates() {
		PackedCollection source = new PackedCollection(1024);
		NoteAudioProvider pitchless = NoteAudioProvider.create(() -> source, KeyPosition.none());
		pitchless.setTuning(tuning);
		GroupNoteSource groupSource =
				new GroupNoteSource(new NoteAudioGroup(List.of(pitchless)), "grp");

		NoteAudioChoice choice = NoteAudioChoice.fromSource("g", groupSource, 0, 9, true);
		choice.setTuning(tuning);
		Assert.assertTrue("Pitchless-only group must not appear as a valid candidate",
				choice.getValidPatternNotes().isEmpty());
	}
}
