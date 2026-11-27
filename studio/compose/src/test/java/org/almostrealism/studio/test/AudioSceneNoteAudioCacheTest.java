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

package org.almostrealism.studio.test;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioGroup;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.GroupNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.generative.NoOpGenerationProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests that destroying an {@link AudioScene} releases the entries its notes created in
 * the JVM-global note-audio cache, and that it leaves alone the entries of notes another
 * live scene still uses.
 *
 * <p>Each test builds its notes from in-memory buffers, so no sample library is needed.
 * Cache state is observed per provider through
 * {@link NoteAudioProvider#getCachedAudioCount()}, which is independent of whatever other
 * tests in the same JVM have cached.</p>
 */
public class AudioSceneNoteAudioCacheTest extends TestSuiteBase {
	/** Number of samples in each generated source buffer. */
	private static final int SOURCE_LENGTH = 2048;

	/**
	 * Builds a tuned provider over a reproducible random buffer.
	 *
	 * @param seed the random seed for the buffer
	 * @return a provider that has cached nothing yet
	 */
	private NoteAudioProvider provider(long seed) {
		PackedCollection source = rand(shape(SOURCE_LENGTH), new Random(seed))
				.multiply(2.0).add(-1.0).evaluate();
		NoteAudioProvider note = NoteAudioProvider.create(() -> source, WesternChromatic.C4);
		note.setTuning(new DefaultKeyboardTuning());
		return note;
	}

	/**
	 * Builds a choice whose only source is a one-member group over the given provider.
	 *
	 * @param provider the provider the choice delivers
	 * @return the choice
	 */
	private static NoteAudioChoice choice(NoteAudioProvider provider) {
		NoteAudioChoice choice = new NoteAudioChoice("cache-test");
		choice.getSources().add(new GroupNoteSource(new NoteAudioGroup(List.of(provider)), "cache-test"));
		return choice;
	}

	/**
	 * Builds a scene over the given choices that needs no sample library.
	 *
	 * @param choices the note audio choices of the scene
	 * @return a new registered scene
	 */
	private static AudioScene<?> scene(NoteAudioChoice... choices) {
		return new AudioScene<>(null, 120.0, 2, 2, OutputLine.sampleRate,
				new ArrayList<>(List.of(choices)), new NoOpGenerationProvider());
	}

	/**
	 * Computes audio for two pitches so the provider owns two tracked cache entries.
	 *
	 * @param provider the provider to populate
	 */
	private void populate(NoteAudioProvider provider) {
		provider.getAudio(WesternChromatic.C4, 0).evaluate();
		provider.getAudio(WesternChromatic.G4, 0).evaluate();
		assertEquals("Computed audio should be tracked by the shared cache",
				2, provider.getCachedAudioCount());
	}

	/** Destroying a scene drops the cache entries of the notes only it uses. */
	@Test(timeout = 120_000)
	public void destroyReleasesSceneNoteAudio() {
		NoteAudioProvider provider = provider(1);
		AudioScene<?> scene = scene(choice(provider));
		populate(provider);

		scene.destroy();

		assertEquals("Destroyed scene's note audio should be released from the shared cache",
				0, provider.getCachedAudioCount());
	}

	/**
	 * A scene that shares a choice with a live scene leaves that choice's entries tracked
	 * when it is destroyed, and the entries are released once the last scene using them
	 * is destroyed. Notes only the destroyed scene uses are released immediately.
	 */
	@Test(timeout = 120_000)
	public void destroyRetainsNoteAudioOfLiveScene() {
		NoteAudioProvider shared = provider(2);
		NoteAudioProvider own = provider(3);
		NoteAudioChoice sharedChoice = choice(shared);

		AudioScene<?> first = scene(sharedChoice, choice(own));
		AudioScene<?> second = scene(sharedChoice);

		try {
			populate(shared);
			populate(own);

			first.destroy();

			assertEquals("Note audio a live scene still uses must stay cached",
					2, shared.getCachedAudioCount());
			assertEquals("Note audio only the destroyed scene used should be released",
					0, own.getCachedAudioCount());
		} finally {
			second.destroy();
		}

		assertEquals("Note audio should be released once no live scene uses it",
				0, shared.getCachedAudioCount());
	}

	/**
	 * A provider that a live scene reaches through a different group than the destroyed
	 * scene stays cached: notes are compared at the level that holds the cache entries,
	 * not at the level the choices hold them.
	 */
	@Test(timeout = 120_000)
	public void destroyRetainsProviderSharedThroughAnotherGroup() {
		NoteAudioProvider shared = provider(5);
		NoteAudioProvider other = provider(6);

		AudioScene<?> first = scene(choice(shared));
		NoteAudioChoice secondChoice = new NoteAudioChoice("cache-test");
		secondChoice.getSources().add(new GroupNoteSource(
				new NoteAudioGroup(List.of(shared, other)), "cache-test"));
		AudioScene<?> second = scene(secondChoice);

		try {
			populate(shared);
			first.destroy();

			assertEquals("A provider a live scene reaches through its own group must stay cached",
					2, shared.getCachedAudioCount());
		} finally {
			second.destroy();
		}

		assertEquals("The provider should be released once no live scene reaches it",
				0, shared.getCachedAudioCount());
	}

	/**
	 * A released provider still delivers the same audio, computing and caching it again,
	 * so a release never breaks a note that is used afterwards.
	 */
	@Test(timeout = 120_000)
	public void releasedProviderRecomputesAudio() {
		NoteAudioProvider provider = provider(4);
		double[] before = provider.getAudio(WesternChromatic.G4, 0).evaluate().toArray();

		provider.releaseCachedAudio();
		assertEquals(0, provider.getCachedAudioCount());

		double[] after = provider.getAudio(WesternChromatic.G4, 0).evaluate().toArray();
		assertEquals(1, provider.getCachedAudioCount());
		assertEquals(before.length, after.length);
		for (int i = 0; i < before.length; i++) {
			assertEquals("Sample " + i + " must match after release", before[i], after[i], 0.0);
		}
	}

	/** A file source that has never loaded its note reports no loaded notes and loads none. */
	@Test(timeout = 60_000)
	public void unloadedFileSourceHasNoLoadedNotes() {
		FileNoteSource source = new FileNoteSource("missing-sample.wav");
		assertTrue("A note that was never loaded has no cached audio to release",
				source.getLoadedNotes().isEmpty());

		source.getNotes();
		assertEquals("Once loaded, the note is reported for release",
				1, source.getLoadedNotes().size());
	}
}
