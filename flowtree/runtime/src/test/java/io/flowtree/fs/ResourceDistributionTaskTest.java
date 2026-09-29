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

package io.flowtree.fs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for the in-memory virtual file-system bookkeeping of
 * {@link ResourceDistributionTask}: directory creation, child enumeration,
 * parent resolution, directory detection, and resource lookup. These paths do
 * not touch the local database or peer network, so the database-loading step of
 * construction is stubbed out to keep the test a pure unit test.
 *
 * <p>Constructing a {@link ResourceDistributionTask} mutates two pieces of
 * JVM-wide static state: it assigns itself as the current task singleton and
 * registers the default resource-type parsers. Both are snapshotted before each
 * test and restored afterwards so these tests neither depend on nor leak state
 * into other tests running in the same JVM.
 */
public class ResourceDistributionTaskTest extends TestSuiteBase {

	/** The current-task singleton captured before each test. */
	private ResourceDistributionTask savedCurrent;

	/** The parser registry contents captured before each test. */
	private List savedResourceTypes;

	/**
	 * Captures the current-task singleton and parser registry so both can be
	 * restored after the test, keeping the JVM-wide static state unchanged across
	 * test boundaries.
	 */
	@Before
	public void snapshotStaticState() {
		savedCurrent = ResourceDistributionTask.getCurrentTask();
		savedResourceTypes = ResourceDistributionTaskState.snapshotRegistry();
	}

	/**
	 * Restores the current-task singleton and parser registry captured in
	 * {@link #snapshotStaticState()}.
	 */
	@After
	public void restoreStaticState() {
		ResourceDistributionTaskState.setCurrentTask(savedCurrent);
		ResourceDistributionTaskState.restoreRegistry(savedResourceTypes);
	}

	/**
	 * A {@link ResourceDistributionTask} whose database-backed file-list load is
	 * replaced with a no-op, allowing the in-memory inventory logic to be tested
	 * without a running {@link OutputServer}. Each test populates the inventory
	 * directly instead of loading it from a database.
	 */
	private static final class InMemoryTask extends ResourceDistributionTask {
		/** Constructs a task with no server, no jobs, and no inter-job sleep. */
		private InMemoryTask() {
			super(null, 0, 0);
		}

		@Override
		protected void initFiles(OutputServer server) {
		}
	}

	/**
	 * Builds a task pre-populated with a small directory tree:
	 * {@code /files/a.txt}, {@code /files/b.txt}, and a grandchild
	 * {@code /files/sub/c.txt}.
	 *
	 * @return the populated task
	 */
	private InMemoryTask populatedTask() {
		InMemoryTask task = new InMemoryTask();
		task.createDirectory("/files");
		task.put("/files/a.txt", new DistributedResource("/files/a.txt"));
		task.put("/files/b.txt", new DistributedResource("/files/b.txt"));
		task.put("/files/sub/c.txt", new DistributedResource("/files/sub/c.txt"));
		return task;
	}

	/**
	 * {@link ResourceDistributionTask#createDirectory(String)} must append a
	 * trailing slash, register the directory, and return {@code null} when the
	 * same directory is created a second time.
	 */
	@Test(timeout = 5000)
	public void createDirectoryNormalisesAndDeduplicates() {
		InMemoryTask task = new InMemoryTask();
		Assert.assertEquals("/data/", task.createDirectory("/data"));
		Assert.assertTrue(task.isDirectory("/data/"));
		Assert.assertNull(task.createDirectory("/data"));
	}

	/**
	 * {@link ResourceDistributionTask#getChildren(String)} must return the
	 * immediate children of a directory: direct files by their full path and a
	 * grandchild collapsed to its intermediate directory, never the grandchild
	 * itself.
	 */
	@Test(timeout = 5000)
	public void getChildrenReturnsImmediateEntries() {
		InMemoryTask task = populatedTask();
		List<String> children = Arrays.asList(task.getChildren("/files"));

		Assert.assertEquals(3, children.size());
		Assert.assertTrue(children.contains("/files/a.txt"));
		Assert.assertTrue(children.contains("/files/b.txt"));
		Assert.assertTrue(children.contains("/files/sub"));
		Assert.assertFalse(children.contains("/files/sub/c.txt"));
	}

	/**
	 * {@link ResourceDistributionTask#getParent(String)} must strip the final
	 * path segment, tolerate a trailing slash, and return {@code null} for the
	 * root.
	 */
	@Test(timeout = 5000)
	public void getParentResolvesEnclosingDirectory() {
		InMemoryTask task = new InMemoryTask();
		Assert.assertEquals("/files", task.getParent("/files/a.txt"));
		Assert.assertEquals("/files", task.getParent("/files/sub/"));
		Assert.assertNull(task.getParent("/"));
	}

	/**
	 * {@link ResourceDistributionTask#isDirectory(String)} must recognise the
	 * root and registered directories, treat a path that has children as a
	 * directory, and reject a leaf resource.
	 */
	@Test(timeout = 5000)
	public void isDirectoryDistinguishesDirsFromLeaves() {
		InMemoryTask task = populatedTask();
		Assert.assertTrue(task.isDirectory("/"));
		Assert.assertTrue(task.isDirectory("/files/"));
		Assert.assertTrue(task.isDirectory("/files/sub"));
		Assert.assertFalse(task.isDirectory("/files/a.txt"));
	}

	/**
	 * {@link ResourceDistributionTask#getResource(String)} must return the
	 * registered resource for a known URI and {@code null} for an unknown one.
	 */
	@Test(timeout = 5000)
	public void getResourceReturnsRegisteredEntry() {
		InMemoryTask task = populatedTask();
		DistributedResource found = task.getResource("/files/a.txt");
		Assert.assertNotNull(found);
		Assert.assertEquals("resource:///files/a.txt", found.getURI());
		Assert.assertNull(task.getResource("/files/missing.txt"));
	}

	/**
	 * {@link ResourceDistributionTask#getCurrentTask()} must return the most
	 * recently constructed task instance.
	 */
	@Test(timeout = 5000)
	public void constructionRegistersCurrentTask() {
		InMemoryTask task = new InMemoryTask();
		Assert.assertSame(task, ResourceDistributionTask.getCurrentTask());
	}

	/**
	 * {@link ResourceDistributionTask#deleteResource(String)} must short-circuit
	 * to {@code false} for a URI that is not registered, before any peer
	 * notification is attempted.
	 */
	@Test(timeout = 5000)
	public void deleteUnknownResourceReturnsFalse() {
		InMemoryTask task = populatedTask();
		Assert.assertFalse(task.deleteResource("/files/missing.txt"));
	}
}
