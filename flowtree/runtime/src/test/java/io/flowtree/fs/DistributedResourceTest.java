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

import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import org.almostrealism.util.TestSuiteBase;
import org.apache.commons.lang3.NotImplementedException;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

/**
 * Behavioural tests for {@link DistributedResource} that exercise the parts of
 * its contract reachable without a running node server or database: URI
 * normalisation, chunk-array sizing and byte accounting, the in-memory
 * {@link DistributedResource#getInputStream()} reader, and the unsupported
 * operations that must fail loudly.
 */
public class DistributedResourceTest extends TestSuiteBase {

	/**
	 * A minimal {@link Resource} whose {@link #getData()} returns a fixed
	 * {@code byte[][]} chunk array, used to drive the {@link DistributedResource}
	 * wrapping constructor without touching the network or database.
	 */
	private static final class ChunkResource implements Resource<byte[][]> {
		/** URI reported by this stub resource. */
		private String uri;

		/** Fixed chunk array returned by {@link #getData()}. */
		private final byte[][] data;

		/**
		 * Constructs a stub resource with the given URI and chunk data.
		 *
		 * @param uri  the resource URI
		 * @param data the chunk array to expose
		 */
		private ChunkResource(String uri, byte[][] data) {
			this.uri = uri;
			this.data = data;
		}

		@Override
		public void load(IOStreams io) { }

		@Override
		public void load(byte[] data, long offset, int len) { }

		@Override
		public void loadFromURI() { }

		@Override
		public void send(IOStreams io) { }

		@Override
		public void saveLocal(String file) { }

		@Override
		public String getURI() { return uri; }

		@Override
		public void setURI(String uri) { this.uri = uri; }

		@Override
		public byte[][] getData() { return data; }

		@Override
		public InputStream getInputStream() { return null; }

		@Override
		public Permissions getPermissions() { return new Permissions(); }
	}

	/**
	 * A URI beginning with a leading slash must be normalised to carry the
	 * explicit {@code resource://} scheme; a URI that already has a scheme is
	 * left unchanged.
	 */
	@Test(timeout = 5000)
	public void constructorNormalisesLeadingSlashUri() {
		DistributedResource slash = new DistributedResource("/photos/a.png");
		Assert.assertEquals("resource:///photos/a.png", slash.getURI());

		DistributedResource scheme = new DistributedResource("resource://photos/a.png");
		Assert.assertEquals("resource://photos/a.png", scheme.getURI());
	}

	/**
	 * A freshly constructed resource with only a URI reports no chunks, is not
	 * loaded, and carries default {@code root} permissions.
	 */
	@Test(timeout = 5000)
	public void freshResourceIsUnsizedAndUnloaded() {
		DistributedResource res = new DistributedResource("/x");
		Assert.assertEquals(-1, res.getSize());
		Assert.assertFalse(res.isLoaded());
		Assert.assertNull(res.getData());
		Assert.assertEquals(0L, res.getTotalBytes());
		Assert.assertEquals("root", res.getPermissions().getOwner());
	}

	/**
	 * The size-carrying constructor must allocate chunk-tracking arrays derived
	 * from the total byte count divided by the fixed chunk size.
	 */
	@Test(timeout = 5000)
	public void sizeConstructorAllocatesChunkArrays() {
		Permissions perms = new Permissions("alice");
		DistributedResource res = new DistributedResource("/big", perms, 1_500_000L);
		Assert.assertEquals(3, res.getSize());
		Assert.assertEquals(1_500_000L, res.getTotalBytes());
		Assert.assertFalse(res.isLoaded());
		Assert.assertSame(perms, res.getPermissions());
		Assert.assertNotNull(res.getData());
		Assert.assertEquals(3, ((byte[][]) res.getData()).length);
	}

	/**
	 * Wrapping a {@link Resource} whose data is a single chunk must mark the
	 * resource fully loaded and report the chunk length as the total byte count.
	 */
	@Test(timeout = 5000)
	public void wrappingConstructorMarksLoaded() {
		byte[][] chunks = { "hello".getBytes() };
		DistributedResource res = new DistributedResource(new ChunkResource("/greeting", chunks));

		Assert.assertEquals(1, res.getSize());
		Assert.assertTrue(res.isLoaded());
		Assert.assertEquals(5L, res.getTotalBytes());
		Assert.assertSame(chunks, res.getData());
	}

	/**
	 * The wrapping constructor must reject data that is not a {@code byte[][]}.
	 */
	@Test(timeout = 5000, expected = IllegalArgumentException.class)
	public void wrappingConstructorRejectsNonChunkData() {
		Resource<Object> bad = new Resource<Object>() {
			private String uri = "/bad";

			@Override
			public void load(IOStreams io) { }

			@Override
			public void load(byte[] data, long offset, int len) { }

			@Override
			public void loadFromURI() { }

			@Override
			public void send(IOStreams io) { }

			@Override
			public void saveLocal(String file) { }

			@Override
			public String getURI() { return uri; }

			@Override
			public void setURI(String uri) { this.uri = uri; }

			@Override
			public Object getData() { return "not chunks"; }

			@Override
			public InputStream getInputStream() { return null; }

			@Override
			public Permissions getPermissions() { return new Permissions(); }
		};

		new DistributedResource(bad);
	}

	/**
	 * The input stream returned for a fully cached single-chunk resource must
	 * reproduce that chunk's bytes exactly and then signal end-of-stream.
	 */
	@Test(timeout = 5000)
	public void inputStreamReadsCachedChunk() throws IOException {
		byte[] payload = "distributed".getBytes();
		DistributedResource res = new DistributedResource(new ChunkResource("/doc", new byte[][] { payload }));

		InputStream in = res.getInputStream();
		byte[] read = new byte[payload.length];
		int total = 0;
		int b;
		while (total < payload.length && (b = in.read()) >= 0) {
			read[total++] = (byte) b;
		}

		Assert.assertEquals(payload.length, total);
		Assert.assertArrayEquals(payload, read);
		Assert.assertEquals(-1, in.read());
	}

	/**
	 * A byte value of 200 (which is negative when stored in a signed byte) must
	 * be returned as an unsigned 0–255 integer by the input stream.
	 */
	@Test(timeout = 5000)
	public void inputStreamReturnsUnsignedBytes() throws IOException {
		byte[] payload = { (byte) 200 };
		DistributedResource res = new DistributedResource(new ChunkResource("/byte", new byte[][] { payload }));

		InputStream in = res.getInputStream();
		Assert.assertEquals(200, in.read());
		Assert.assertEquals(-1, in.read());
	}

	/**
	 * {@link DistributedResource#setExcludeHost(String)} must not disturb the
	 * resource identity or loaded state.
	 */
	@Test(timeout = 5000)
	public void setExcludeHostLeavesResourceUsable() {
		DistributedResource res = new DistributedResource(new ChunkResource("/e", new byte[][] { "z".getBytes() }));
		res.setExcludeHost("10.0.0.1");
		Assert.assertTrue(res.isLoaded());
		Assert.assertEquals(1L, res.getTotalBytes());
	}

	/**
	 * {@link DistributedResource#setURI(String)} replaces the URI verbatim and
	 * is reflected in {@link DistributedResource#toString()}.
	 */
	@Test(timeout = 5000)
	public void setUriReplacesUri() {
		DistributedResource res = new DistributedResource("/first");
		res.setURI("resource:///second");
		Assert.assertEquals("resource:///second", res.getURI());
		Assert.assertEquals("DistributedResource (resource:///second)", res.toString());
	}

	/**
	 * The byte-range {@code load} overload is unsupported and must throw
	 * {@link NotImplementedException}.
	 */
	@Test(timeout = 5000, expected = NotImplementedException.class)
	public void byteRangeLoadIsUnsupported() {
		new DistributedResource("/x").load(new byte[8], 0L, 8);
	}

	/**
	 * Distributed resources cannot be persisted to a local file and must throw
	 * {@link IOException} from {@link DistributedResource#saveLocal(String)}.
	 */
	@Test(timeout = 5000, expected = IOException.class)
	public void saveLocalIsUnsupported() throws IOException {
		new DistributedResource("/x").saveLocal("/tmp/whatever");
	}
}
