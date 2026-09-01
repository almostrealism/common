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

import org.almostrealism.audio.api.Audio.AudioLayer;
import org.almostrealism.audio.api.Audio.WaveDetailData;
import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.studio.persistence.AudioLayerGroupLibrary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins where a group belongs in the library's directory structure.
 *
 * <p>A group is not a file, so it has no location of its own; it has to be
 * placed where its members are. The rule is the most specific directory holding
 * every member that can be found, and the interesting cases are all about the
 * word "found" — a library that has lost a sample must not rearrange the group
 * that sample belonged to.</p>
 *
 * <p>These exercise the placement arithmetic directly, over a directory tree
 * with no audio in it, so what is under test is where a group lands rather than
 * anything about reading audio.</p>
 */
public class AudioLayerGroupPlacementTest extends TestSuiteBase {
	/**
	 * A group library rooted at a directory, whose members are whatever the
	 * test says they are.
	 *
	 * <p>Placement asks only two things: which files a group's members resolve
	 * to, and where the library root is. Supplying the first directly keeps
	 * these tests clear of identifier resolution, which is
	 * {@link org.almostrealism.audio.AudioLibrary}'s to get right and is
	 * covered where it lives.</p>
	 */
	private static class PlacedGroups extends AudioLayerGroupLibrary {
		/** Creates a group library rooted at the given directory. */
		PlacedGroups(File root) {
			super(null, null, root);
		}

		/**
		 * Returns where the given files place a group.
		 *
		 * @param files the members that were found
		 * @return the directory the group belongs in
		 */
		File placeFor(List<File> files) {
			return commonDirectory(files);
		}
	}

	/** The library root these tests place groups within. */
	private File root;

	/**
	 * Creates a directory tree beneath the library root.
	 *
	 * @param path slash-separated directory path
	 * @return the created directory
	 * @throws IOException if it cannot be created
	 */
	private File directory(String path) throws IOException {
		File dir = new File(root, path);
		Files.createDirectories(dir.toPath());
		return dir;
	}

	/**
	 * Creates an empty file at the given path beneath the library root.
	 *
	 * @param path slash-separated file path
	 * @return the created file
	 * @throws IOException if it cannot be created
	 */
	private File file(String path) throws IOException {
		File file = new File(root, path);
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), new byte[0]);
		return file;
	}

	/**
	 * Places a group whose found members are the given files.
	 *
	 * @param members the members that were found
	 * @return the directory the group belongs in
	 * @throws IOException if the library root cannot be created
	 */
	private File place(File... members) throws IOException {
		if (root == null) root = Files.createTempDirectory("library").toFile();

		List<File> files = new ArrayList<>(List.of(members));
		return new PlacedGroups(root).placeFor(files);
	}

	/**
	 * Prepares an empty library root.
	 *
	 * @throws IOException if it cannot be created
	 */
	private void library() throws IOException {
		root = Files.createTempDirectory("library").toFile();
	}

	/** Members sharing a directory place the group in that directory. */
	@Test(timeout = 30000)
	public void membersInOneDirectoryPlaceTheGroupThere() throws IOException {
		library();
		File kicks = directory("drums/kicks");

		Assert.assertEquals(kicks,
				place(file("drums/kicks/a.wav"), file("drums/kicks/b.wav")));
	}

	/** Members in sibling directories place the group in their ancestor. */
	@Test(timeout = 30000)
	public void membersInSiblingDirectoriesPlaceTheGroupAbove() throws IOException {
		library();
		File drums = directory("drums");

		Assert.assertEquals(drums,
				place(file("drums/kicks/a.wav"), file("drums/snares/b.wav")));
	}

	/** A lone member places the group in its own directory. */
	@Test(timeout = 30000)
	public void aLoneMemberPlacesTheGroupInItsDirectory() throws IOException {
		library();
		File kicks = directory("drums/kicks");

		Assert.assertEquals(kicks, place(file("drums/kicks/a.wav")));
	}

	/**
	 * Directories are compared by segment, not by characters.
	 *
	 * <p>{@code drums} and {@code drumsticks} share a character prefix and no
	 * directory. Comparing text would place the group in a directory that does
	 * not exist.</p>
	 */
	@Test(timeout = 30000)
	public void directoriesAreComparedBySegment() throws IOException {
		library();

		File placed = place(file("drums/a.wav"), file("drumsticks/b.wav"));

		Assert.assertEquals(root, placed);
	}

	/**
	 * A member that cannot be found does not move the group.
	 *
	 * <p>This is the case the rule exists for: a group whose members are all in
	 * one folder stays in that folder when the library loses one of them,
	 * rather than climbing to the root for a reason the user cannot see. A
	 * member that was not found is simply not among the files placement is
	 * given.</p>
	 */
	@Test(timeout = 30000)
	public void aMissingMemberDoesNotMoveTheGroup() throws IOException {
		library();
		File kicks = directory("drums/kicks");

		File whole = place(file("drums/kicks/a.wav"), file("drums/kicks/b.wav"));
		File missingOne = place(file("drums/kicks/a.wav"));

		Assert.assertEquals(kicks, whole);
		Assert.assertEquals(whole, missingOne);
	}

	/**
	 * What is derived from the index is worked out again when the index is.
	 *
	 * <p>Which files the groups claim, and what pitch each was captured at, are
	 * both read through the index — so both are only as current as the index
	 * they came from. Holding either until told to drop it does not work: the
	 * thing that would have to do the telling is whatever saved a group, and it
	 * has no reason to know that anything was derived. Without this, a group
	 * saved during a session leaves its members showing loose rows until the
	 * application is restarted.</p>
	 */
	@Test(timeout = 30000)
	public void whatIsDerivedFromTheIndexIsWorkedOutAgainWhenItChanges()
			throws IOException {
		library();

		File wav = file("drums/a.wav");
		AudioLibrary audio = new AudioLibrary(
				new FileWaveDataProviderNode(root), OutputLine.sampleRate);

		long before = audio.getIndexGeneration();
		audio.indexFiles();

		Assert.assertNotEquals("Indexing must be visible as a change",
				before, audio.getIndexGeneration());

		long after = audio.getIndexGeneration();
		audio.indexFiles();

		Assert.assertNotEquals("Indexing again is another change",
				after, audio.getIndexGeneration());
		Assert.assertNotNull(wav);
	}

	/** A group with nothing findable belongs at the root. */
	@Test(timeout = 30000)
	public void aGroupWithNothingFindableBelongsAtTheRoot() throws IOException {
		library();

		Assert.assertEquals(root, place());
	}

	/** A member outside the library cannot place a group inside it. */
	@Test(timeout = 30000)
	public void aMemberOutsideTheLibraryIsIgnored() throws IOException {
		library();
		File outside = Files.createTempFile("stray", ".wav").toFile();
		outside.deleteOnExit();

		File kicks = directory("drums/kicks");

		Assert.assertEquals(kicks, place(file("drums/kicks/a.wav"), outside));
	}

	/** A member directly in the library root places the group at the root. */
	@Test(timeout = 30000)
	public void aMemberAtTheRootPlacesTheGroupAtTheRoot() throws IOException {
		library();

		Assert.assertEquals(root, place(file("a.wav"), file("drums/b.wav")));
	}

	/** A layer with no audio has no identifier to read. */
	@Test(timeout = 30000)
	public void aLayerWithoutAudioHasNoReference() {
		Assert.assertNull(AudioLayerGroupLibrary.audioRef(
				AudioLayer.newBuilder().setLayerId("silent").build()));
	}

	/** A stripped layer's identifier is its reference. */
	@Test(timeout = 30000)
	public void aStrippedLayerReadsItsReference() {
		Assert.assertEquals("abc", AudioLayerGroupLibrary.audioRef(
				AudioLayer.newBuilder().setAudioRef("abc").build()));
	}

	/**
	 * A layer still carrying its audio reads the identifier from it.
	 *
	 * <p>A group is stripped when it is saved, so an unstripped layer is one
	 * that has not been through that yet. Placement has to work for both, or a
	 * group would move once it was saved.</p>
	 */
	@Test(timeout = 30000)
	public void anUnstrippedLayerReadsItsInlineIdentifier() {
		Assert.assertEquals("def", AudioLayerGroupLibrary.audioRef(
				AudioLayer.newBuilder()
						.setAudio(WaveDetailData.newBuilder().setIdentifier("def"))
						.build()));
	}
}
