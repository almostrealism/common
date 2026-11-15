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

package io.flowtree;

import io.flowtree.test.ServerTestBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

/**
 * Exercises the cache bookkeeping and the direct-connection guards of
 * {@link ServerResourceManager} without any peer, output server, or distribution
 * task present. These are the paths a passive server reaches while serving cached
 * resources locally: cache sizing, membership, non-blocking look-up of an absent
 * or already-resident entry, and the short-circuit that refuses a loop-back host.
 *
 * @author Michael Murray
 */
public class ServerResourceManagerTest extends ServerTestBase {

	/**
	 * Builds a passive {@link Server} whose resource manager can be driven directly.
	 *
	 * @return a server that binds no socket and owns no peers
	 * @throws IOException if the server cannot be constructed
	 */
	private static Server host() throws IOException {
		Properties props = new Properties();
		props.setProperty("server.port", "-1");
		props.setProperty("nodes.initial", "0");
		props.setProperty("servers.total", "0");
		return new Server(props);
	}

	/** The maximum cache size round-trips through its accessors. */
	@Test(timeout = 60000)
	public void maxCacheRoundTrips() throws IOException {
		ServerResourceManager manager = new ServerResourceManager(host(), 4, null);

		Assert.assertEquals(4, manager.getMaxCache());
		manager.setMaxCache(9);
		Assert.assertEquals(9, manager.getMaxCache());
	}

	/** Cache membership reflects entries inserted through the live cache map. */
	@Test(timeout = 60000)
	public void cacheContainsReflectsLiveMap() throws IOException {
		ServerResourceManager manager = new ServerResourceManager(host(), 4, null);

		Assert.assertFalse(manager.cacheContains("resource:one"));
		manager.getCache().put("resource:one", "payload");
		Assert.assertTrue(manager.cacheContains("resource:one"));
	}

	/** A look-up of an absent, not-loading URI returns {@code null} without blocking. */
	@Test(timeout = 60000)
	public void loadFromCacheReturnsNullForAbsent() throws IOException {
		ServerResourceManager manager = new ServerResourceManager(host(), 4, null);
		Assert.assertNull(manager.loadFromCache("resource:absent"));
	}

	/** A resident entry is returned immediately from the cache. */
	@Test(timeout = 60000)
	public void loadFromCacheReturnsResidentEntry() throws IOException {
		ServerResourceManager manager = new ServerResourceManager(host(), 4, null);
		Object payload = new Object();
		manager.getCache().put("resource:present", payload);

		Assert.assertSame(payload, manager.loadFromCache("resource:present"));
	}

	/** A direct resource stream to a loop-back or empty host is refused with {@code null}. */
	@Test(timeout = 60000)
	public void directStreamToLoopbackIsRefused() throws IOException {
		ServerResourceManager manager = new ServerResourceManager(host(), 4, null);

		Assert.assertNull(manager.getResourceStream("localhost", 7767, "/x"));
		Assert.assertNull(manager.getResourceStream("", 7767, "/x"));
		Assert.assertNull(manager.getResourceStream((String) null, 7767, "/x"));
	}

	/** With no peers registered, a distributed stream request yields {@code null}. */
	@Test(timeout = 60000)
	public void distributedStreamWithNoPeersIsNull() throws IOException {
		ServerResourceManager manager = new ServerResourceManager(host(), 4, null);
		Assert.assertNull(manager.getResourceStream("resource:/missing"));
	}
}
