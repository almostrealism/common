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
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderFilter;
import org.almostrealism.audio.data.FileWaveDataProviderFilter.FilterOn;
import org.almostrealism.audio.data.FileWaveDataProviderFilter.FilterType;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.studio.persistence.AudioLayerGroupLibrary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Pins what a filter means when what it is filtering is a group.
 *
 * <p>Sample selection filters on a file's name or its path. Once the library is
 * understood as a library of groups, the thing being filtered is a group, and a
 * group of twelve samples spread over two folders has neither a file name nor a
 * file path. It has its own name and its own place, and those are what a filter
 * matches against.</p>
 *
 * <p>The property that matters is the last one here: a group standing for a
 * loose file must answer every filter exactly as that file does. If it does
 * not, then adopting the group model silently changes what a user's existing
 * text filters select — their whole way of organising a library would quietly
 * stop working, with nothing to see. That is the acceptance criterion for the
 * transition, so it is checked against the real file-level matching rather than
 * against a restatement of it.</p>
 */
public class AudioLayerGroupFilterTest extends TestSuiteBase {
	/**
	 * A group library rooted at a directory, told directly where its groups
	 * are.
	 *
	 * <p>Resolving members is the library's to do and is covered where it
	 * lives; what is under test here is the value a filter is given.</p>
	 */
	private static class FilterableGroups extends AudioLayerGroupLibrary {
		/** Where every group is placed. */
		private final File location;

		/**
		 * Creates a group library placing its groups at the given directory.
		 *
		 * @param root     the library root
		 * @param location where groups are placed
		 */
		FilterableGroups(File root, File location) {
			super(null, null, root);
			this.location = location;
		}

		@Override
		public File locate(Audio.AudioLayerGroup group) { return location; }
	}

	/** The library root these tests filter within. */
	private File root;

	/**
	 * Creates a library root with the given directories.
	 *
	 * @param directories directories to create beneath the root
	 * @throws IOException if they cannot be created
	 */
	private void library(String... directories) throws IOException {
		root = Files.createTempDirectory("filter-library").toFile();

		for (String directory : directories) {
			Files.createDirectories(new File(root, directory).toPath());
		}
	}

	/**
	 * Returns a group with the given key.
	 *
	 * @param key the group key
	 * @return the group
	 */
	private Audio.AudioLayerGroup group(String key) {
		return Audio.AudioLayerGroup.newBuilder().setKey(key).build();
	}

	/**
	 * Returns a filter.
	 *
	 * @param on   what to match against
	 * @param type how to match
	 * @param text what to match
	 * @return the filter
	 */
	private FileWaveDataProviderFilter filter(FilterOn on, FilterType type, String text) {
		return new FileWaveDataProviderFilter(on, type, text);
	}

	/** A group is filtered by name on the key it is known by. */
	@Test(timeout = 30000)
	public void nameFiltersOnTheGroupKey() throws IOException {
		library();
		AudioLayerGroupLibrary groups = new FilterableGroups(root, root);

		Assert.assertEquals("kit 01", groups.filterValue(FilterOn.NAME, group("kit 01")));
		Assert.assertTrue(groups.matches(
				filter(FilterOn.NAME, FilterType.STARTS_WITH, "kit"), group("kit 01")));
		Assert.assertFalse(groups.matches(
				filter(FilterOn.NAME, FilterType.STARTS_WITH, "pad"), group("kit 01")));
	}

	/** A group is filtered by path on the directory it belongs in. */
	@Test(timeout = 30000)
	public void pathFiltersOnTheDirectoryTheGroupBelongsIn() throws IOException {
		library("drums/kicks");
		File kicks = new File(root, "drums/kicks");
		AudioLayerGroupLibrary groups = new FilterableGroups(root, kicks);

		Assert.assertEquals("drums" + File.separator + "kicks",
				groups.filterValue(FilterOn.PATH, group("kit")));
		Assert.assertTrue(groups.matches(
				filter(FilterOn.PATH, FilterType.STARTS_WITH, "drums"), group("kit")));
		Assert.assertFalse(groups.matches(
				filter(FilterOn.PATH, FilterType.STARTS_WITH, "pads"), group("kit")));
	}

	/** A group belonging at the root has an empty path, as a file there does. */
	@Test(timeout = 30000)
	public void aGroupAtTheRootHasAnEmptyPath() throws IOException {
		library();
		AudioLayerGroupLibrary groups = new FilterableGroups(root, root);

		Assert.assertEquals("", groups.filterValue(FilterOn.PATH, group("kit")));
	}

	/**
	 * A group standing for a loose file answers every filter as that file does.
	 *
	 * <p>This is the whole safety of the transition. Each filter is applied
	 * twice — once to the file through the machinery that has always applied
	 * it, and once to the group standing for that file — and the two answers
	 * must agree. Comparing against the real file-level matching is deliberate:
	 * a restatement of what it does could agree with itself while both were
	 * wrong.</p>
	 */
	@Test(timeout = 30000)
	public void aLooseFileIsFilteredIdenticallyThroughItsGroup() throws IOException {
		library("drums/kicks");

		File wav = new File(root, "drums/kicks/Kick 01.wav");
		Files.write(wav.toPath(), new byte[0]);

		FileWaveDataProvider provider = new FileWaveDataProvider(wav.getAbsolutePath());
		FileWaveDataProviderTree tree = new FileWaveDataProviderNode(root);

		AudioLayerGroupLibrary groups =
				new FilterableGroups(root, wav.getParentFile());
		Audio.AudioLayerGroup synthetic = groups.syntheticGroup(wav, "abcd1234");

		List<FileWaveDataProviderFilter> filters = List.of(
				filter(FilterOn.NAME, FilterType.STARTS_WITH, "Kick"),
				filter(FilterOn.NAME, FilterType.STARTS_WITH, "Snare"),
				filter(FilterOn.NAME, FilterType.CONTAINS, "01"),
				filter(FilterOn.NAME, FilterType.EQUALS, "Kick 01.wav"),
				filter(FilterOn.NAME, FilterType.ENDS_WITH, ".wav"),
				filter(FilterOn.PATH, FilterType.STARTS_WITH, "drums"),
				filter(FilterOn.PATH, FilterType.CONTAINS, "kicks"),
				filter(FilterOn.PATH, FilterType.STARTS_WITH, "pads"),
				filter(FilterOn.PATH, FilterType.EQUALS, "drums/kicks"));

		for (FileWaveDataProviderFilter f : filters) {
			Assert.assertEquals(
					"Filter " + f.getFilterOn() + " " + f.getFilterType()
							+ " \"" + f.getFilter() + "\" must select the group "
							+ "standing for a file exactly as it selects the file",
					f.matches(tree, provider), groups.matches(f, synthetic));
		}
	}

}
