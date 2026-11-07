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

import io.almostrealism.db.DatabaseConnection;
import io.almostrealism.db.Query;
import io.almostrealism.resource.Resource;
import io.flowtree.Server.ResourceProvider;
import io.flowtree.msg.Message;
import org.almostrealism.io.JobOutput;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests for the in-memory virtual file-system bookkeeping of
 * {@link ResourceDistributionTask}: directory creation, child enumeration,
 * parent resolution, directory detection, resource lookup, chunk serving,
 * message dispatch, and cache eviction. These paths do not touch the local
 * database or peer network, so the database-loading step of construction is
 * stubbed out (via {@link InMemoryTask}) to keep the tests pure unit tests. The
 * JVM-wide static state that construction mutates is snapshotted and restored by
 * {@link ResourceDistributionStaticStateTest}.
 */
public class ResourceDistributionTaskTest extends ResourceDistributionStaticStateTest {

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

	/**
	 * A {@link ResourceProvider} that always returns the same fixed resource,
	 * used to verify that {@link ResourceDistributionTask#getResource(String)}
	 * delegates to the provider registered on a directory.
	 */
	private static final class StubProvider implements ResourceProvider {
		/** The resource this provider serves for every request. */
		private final Resource served;

		/**
		 * Constructs a provider that serves the given resource.
		 *
		 * @param served the resource returned by both {@code loadResource} overloads
		 */
		private StubProvider(Resource served) {
			this.served = served;
		}

		@Override
		public Resource loadResource(String uri) {
			return served;
		}

		@Override
		public Resource loadResource(String uri, String exclude) {
			return served;
		}
	}

	/**
	 * {@link ResourceDistributionTask#setResourceProvider(String, ResourceProvider)}
	 * must reject a URI that names a leaf resource rather than a directory,
	 * returning {@code false} without attaching the provider.
	 */
	@Test(timeout = 5000)
	public void setResourceProviderRejectsNonDirectory() {
		InMemoryTask task = populatedTask();
		Assert.assertFalse(task.setResourceProvider("/files/a.txt",
				new StubProvider(new DistributedResource("/files/a.txt"))));
	}

	/**
	 * A directory with a registered {@link ResourceProvider} must delegate
	 * {@link ResourceDistributionTask#getResource(String)} to that provider and
	 * return the resource it supplies.
	 */
	@Test(timeout = 5000)
	public void providerBackedDirectoryDelegatesGetResource() {
		InMemoryTask task = new InMemoryTask();
		task.createDirectory("/remote");
		DistributedResource served = new DistributedResource("/remote/file");

		Assert.assertTrue(task.setResourceProvider("/remote/", new StubProvider(served)));
		Assert.assertSame(served, task.getResource("/remote/"));
	}

	/**
	 * When a provider returns a plain {@link Resource} that is not already a
	 * {@link DistributedResource}, {@link ResourceDistributionTask#getResource(String)}
	 * must wrap it in a {@link DistributedResource} that carries the same URI and
	 * byte count.
	 */
	@Test(timeout = 5000)
	public void providerPlainResourceIsWrapped() {
		InMemoryTask task = new InMemoryTask();
		task.createDirectory("/wrap");
		ChunkResource plain = new ChunkResource("/wrap/x", new byte[][] { "data".getBytes() });

		Assert.assertTrue(task.setResourceProvider("/wrap/", new StubProvider(plain)));

		DistributedResource got = task.getResource("/wrap/");
		Assert.assertEquals("resource:///wrap/x", got.getURI());
		Assert.assertEquals(4L, got.getTotalBytes());
	}

	/**
	 * {@link ResourceDistributionTask#neighbors(Resource)} is not part of the
	 * distributed file-system graph contract and must throw.
	 */
	@Test(timeout = 5000, expected = UnsupportedOperationException.class)
	public void neighborsIsUnsupported() {
		new InMemoryTask().neighbors(null);
	}

	/**
	 * {@link ResourceDistributionTask#countNodes()} is not supported and must
	 * throw.
	 */
	@Test(timeout = 5000, expected = UnsupportedOperationException.class)
	public void countNodesIsUnsupported() {
		new InMemoryTask().countNodes();
	}

	/**
	 * The scheduling accessors report fixed values: priority is pinned at
	 * {@code 1.0} and ignores {@link ResourceDistributionTask#setPriority(double)},
	 * completeness is {@code 0}, the task never reports complete, and it does not
	 * encode to a wire string.
	 */
	@Test(timeout = 5000)
	public void schedulingAccessorsReportFixedValues() {
		InMemoryTask task = new InMemoryTask();

		Assert.assertEquals(1.0, task.getPriority(), 0.0);
		task.setPriority(5.0);
		Assert.assertEquals(1.0, task.getPriority(), 0.0);
		Assert.assertEquals(0.0, task.getCompleteness(), 0.0);
		Assert.assertFalse(task.isComplete());
		Assert.assertNull(task.encode());
		Assert.assertEquals("ResourceDistributionTask (null)", task.getName());
		Assert.assertEquals("ResourceDistributionTask (0, 0)", task.toString());
	}

	/**
	 * {@link ResourceDistributionTask#executeQuery(Query)} must return
	 * {@code null} when the query's key column is not the resource-URI column,
	 * since such a query cannot identify a chunk to serve.
	 */
	@Test(timeout = 5000)
	public void executeQueryRejectsNonUriColumn() {
		InMemoryTask task = new InMemoryTask();
		Query q = new Query("tbl");
		q.setColumn(0, "not_the_uri_column");
		Assert.assertNull(task.executeQuery(q));
	}

	/**
	 * A negative chunk index can never match a stored chunk, so
	 * {@link ResourceDistributionTask#executeQuery(Query)} must return
	 * {@code null} before consulting the inventory.
	 */
	@Test(timeout = 5000)
	public void executeQueryRejectsNegativeIndex() {
		InMemoryTask task = new InMemoryTask();
		Query q = new Query("tbl");
		q.setColumn(0, DatabaseConnection.uriColumn);
		q.setValue(0, "/doc");
		q.setValue(1, "-1");
		Assert.assertNull(task.executeQuery(q));
	}

	/**
	 * When the requested chunk is cached in memory,
	 * {@link ResourceDistributionTask#executeQuery(Query)} must return a
	 * single-entry map from the chunk index to that chunk's bytes.
	 */
	@Test(timeout = 5000)
	public void executeQueryReturnsCachedChunk() {
		InMemoryTask task = new InMemoryTask();
		byte[] chunk = "zzz".getBytes();
		task.put("/doc", new DistributedResource(new ChunkResource("/doc", new byte[][] { chunk })));

		Query q = new Query("tbl");
		q.setColumn(0, DatabaseConnection.uriColumn);
		q.setValue(0, "/doc");
		q.setValue(1, "0");

		Map result = task.executeQuery(q);
		Assert.assertNotNull(result);
		Assert.assertArrayEquals(chunk, (byte[]) result.get(Integer.valueOf(0)));
	}

	/**
	 * {@link ResourceDistributionTask#createResource(String)} must return
	 * {@code null} for a URI that already exists, short-circuiting before any
	 * peer notification.
	 */
	@Test(timeout = 5000)
	public void createResourceReturnsNullWhenPresent() {
		InMemoryTask task = populatedTask();
		Assert.assertNull(task.createResource("/files/a.txt"));
	}

	/**
	 * {@link ResourceDistributionTask#deleteResource(String)} must refuse to
	 * delete the root even when it is registered, returning {@code false} and
	 * leaving the entry in place.
	 */
	@Test(timeout = 5000)
	public void deleteResourceRejectsRoot() {
		InMemoryTask task = new InMemoryTask();
		task.put("/", new DistributedResource("/"));
		Assert.assertFalse(task.deleteResource("/"));
		Assert.assertNotNull(task.getResource("/"));
	}

	/**
	 * Deleting a directory that has no children succeeds vacuously, returning
	 * {@code true} without touching the peer network.
	 */
	@Test(timeout = 5000)
	public void deleteEmptyDirectoryReturnsTrue() {
		InMemoryTask task = new InMemoryTask();
		task.createDirectory("/empty");
		Assert.assertTrue(task.deleteDirectory("/empty"));
	}

	/**
	 * {@link ResourceDistributionTask#disconnect(io.flowtree.msg.NodeProxy)} does
	 * no work for the distributed file system and must report {@code 0}.
	 */
	@Test(timeout = 5000)
	public void disconnectReportsZero() {
		Assert.assertEquals(0, new InMemoryTask().disconnect(null));
	}

	/**
	 * {@link ResourceDistributionTask#nextJob()} must return {@code null} when no
	 * local {@link OutputServer} is running, since a distribution job cannot be
	 * dispatched without one.
	 */
	@Test(timeout = 5000)
	public void nextJobReturnsNullWithoutOutputServer() {
		Assert.assertNull(new InMemoryTask().nextJob());
	}

	/**
	 * A message whose type is neither a distributed-resource URI nor an
	 * invalidation must not be handled, so
	 * {@link ResourceDistributionTask#recievedMessage(Message, int)} returns
	 * {@code false}.
	 */
	@Test(timeout = 5000)
	public void receivedUnrelatedMessageReturnsFalse() throws IOException {
		Message m = new Message(Message.Job, -3, null);
		m.setString("/x");
		Assert.assertFalse(new InMemoryTask().recievedMessage(m, 0));
	}

	/**
	 * An invalidation for a URI that is not in the inventory is acknowledged
	 * (returns {@code true}) but adds nothing, so the URI remains absent.
	 */
	@Test(timeout = 5000)
	public void receivedInvalidateForUnknownUriAddsNothing() throws IOException {
		InMemoryTask task = new InMemoryTask();
		Message m = new Message(Message.DistributedResourceInvalidate, -3, null);
		m.setString("/ghost");

		Assert.assertTrue(task.recievedMessage(m, 0));
		Assert.assertNull(task.getResource("/ghost"));
	}

	/**
	 * A URI announcement for a resource already present, carrying no size, must
	 * be acknowledged without modifying the inventory.
	 */
	@Test(timeout = 5000)
	public void receivedUriForKnownResourceReturnsTrue() throws IOException {
		InMemoryTask task = populatedTask();
		Message m = new Message(Message.DistributedResourceUri, -3, null);
		m.setString("/files/a.txt");

		Assert.assertTrue(task.recievedMessage(m, 0));
		Assert.assertNotNull(task.getResource("/files/a.txt"));
	}

	/**
	 * A URI announcement whose declared size disagrees with the known resource's
	 * chunk count must still be acknowledged (returning {@code true}); the
	 * disagreement is logged, not fatal.
	 */
	@Test(timeout = 5000)
	public void receivedUriWithSizeDisagreementReturnsTrue() throws IOException {
		InMemoryTask task = populatedTask();
		Message m = new Message(Message.DistributedResourceUri, -3, null);
		m.setString("/files/a.txt" + JobOutput.ENTRY_SEPARATOR + "5");

		Assert.assertTrue(task.recievedMessage(m, 0));
	}

	/**
	 * When the tracked cache total exceeds {@link ResourceDistributionTask#maxCache},
	 * {@link ResourceDistributionTask#checkFull()} must evict a cached resource,
	 * clearing its in-memory data. With a single resource in the inventory the
	 * eviction target is deterministic.
	 */
	@Test(timeout = 5000)
	public void checkFullEvictsWhenOverCapacity() {
		InMemoryTask task = new InMemoryTask();
		DistributedResource res = new DistributedResource(new ChunkResource("/c", new byte[][] { "abcd".getBytes() }));
		task.put("/c", res);
		Assert.assertNotNull(res.getData());

		long savedMax = ResourceDistributionTask.maxCache;
		try {
			ResourceDistributionTask.maxCache = 0L;
			task.addCache(10L);
			task.checkFull();
			Assert.assertNull(res.getData());
		} finally {
			ResourceDistributionTask.maxCache = savedMax;
		}
	}

	/**
	 * When the cache total is brought back under the limit by
	 * {@link ResourceDistributionTask#subtractCache(long)}, a subsequent
	 * {@link ResourceDistributionTask#checkFull()} evicts nothing and the
	 * resource keeps its data.
	 */
	@Test(timeout = 5000)
	public void checkFullKeepsResourceWhenUnderCapacity() {
		InMemoryTask task = new InMemoryTask();
		DistributedResource res = new DistributedResource(new ChunkResource("/d", new byte[][] { "ab".getBytes() }));
		task.put("/d", res);

		task.addCache(100L);
		task.subtractCache(100L);
		task.checkFull();

		Assert.assertNotNull(res.getData());
	}

	/**
	 * {@link DistributedResource#cache()} must rewrite an {@code http://} URI to
	 * the internal {@code /http/} namespace and register the resource with the
	 * current task under that rewritten URI.
	 */
	@Test(timeout = 5000)
	public void cacheRewritesHttpUriAndRegisters() {
		InMemoryTask task = new InMemoryTask();
		DistributedResource res = new DistributedResource("http://example.com/img.png");
		res.cache();

		Assert.assertEquals("/http/example.com/img.png", res.getURI());
		Assert.assertSame(res, task.getResource("/http/example.com/img.png"));
	}

	/**
	 * {@link DistributedResource#cache()} must rewrite a {@code file:/} URI to the
	 * internal {@code /files/} namespace using the file's base name and register
	 * it with the current task.
	 */
	@Test(timeout = 5000)
	public void cacheRewritesFileUriAndRegisters() {
		InMemoryTask task = new InMemoryTask();
		DistributedResource res = new DistributedResource("file:/tmp/pic.png");
		res.cache();

		Assert.assertEquals("/files/pic.png", res.getURI());
		Assert.assertSame(res, task.getResource("/files/pic.png"));
	}
}
