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

package io.almostrealism.profile.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Tests for {@link OperationProfileNode#save(File)}, which must create any missing parent
 * directories so that a profile can be written into a fresh build tree, and must report
 * a parent that cannot be created rather than failing later with a less specific error.
 */
public class OperationProfileNodeSaveTests extends TestSuiteBase {

	/**
	 * Saving into a directory hierarchy that does not exist yet creates it, and the
	 * written profile loads back with the same name.
	 */
	@Test(timeout = 10000)
	public void createsMissingParentDirectories() throws IOException {
		File root = Files.createTempDirectory("profile-save").toFile();
		File file = new File(root, "a/b/c/profile.xml");
		Assert.assertFalse(file.getParentFile().exists());

		new OperationProfileNode("saved").save(file.getPath());

		Assert.assertTrue(file.isFile());
		Assert.assertEquals("saved", OperationProfileNode.load(file).getName());
	}

	/** Saving into an existing directory succeeds and replaces a previously saved file. */
	@Test(timeout = 10000)
	public void savesIntoExistingDirectory() throws IOException {
		File root = Files.createTempDirectory("profile-save").toFile();
		File file = new File(root, "profile.xml");

		new OperationProfileNode("first").save(file);
		new OperationProfileNode("second").save(file);

		Assert.assertEquals("second", OperationProfileNode.load(file).getName());
	}

	/**
	 * When a path component that should be a directory is a regular file, the parent
	 * cannot be created and the failure is reported as an {@link IOException} naming it.
	 */
	@Test(timeout = 10000)
	public void reportsUncreatableParent() throws IOException {
		File root = Files.createTempDirectory("profile-save").toFile();
		File blocker = new File(root, "blocker");
		Assert.assertTrue(blocker.createNewFile());

		File file = new File(blocker, "nested/profile.xml");

		try {
			new OperationProfileNode("blocked").save(file);
			Assert.fail("Expected an IOException when the parent directory cannot be created");
		} catch (IOException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("nested"));
		}

		Assert.assertFalse(file.exists());
	}
}
