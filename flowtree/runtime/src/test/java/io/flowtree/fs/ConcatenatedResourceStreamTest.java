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

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tests for {@link ConcatenatedResource.ConcatenatedInputStream}, which streams
 * the contents of several distributed child resources in order, inserting a
 * newline separator between them. The child resources are served from an
 * in-memory {@link ResourceDistributionTask} so the concatenation logic — chunk
 * exhaustion, separator emission, and advancing to the next child — is exercised
 * without a database or peer network.
 *
 * <p>The stream resolves its children through the current-task singleton, which
 * the {@link ResourceDistributionStaticStateTest} base class snapshots and
 * restores around each test.
 */
public class ConcatenatedResourceStreamTest extends ResourceDistributionStaticStateTest {

	/**
	 * Reads every byte from the stream into a byte array.
	 *
	 * @param in the stream to drain
	 * @return the bytes read before end-of-stream
	 * @throws IOException if the stream cannot be read
	 */
	private static byte[] drain(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) >= 0) {
			out.write(b);
		}
		return out.toByteArray();
	}

	/**
	 * The concatenated stream must emit each child resource's bytes in order,
	 * separated by a single newline, and terminate with a trailing newline after
	 * the final child before signalling end-of-stream.
	 */
	@Test(timeout = 5000)
	public void concatenatesChildrenSeparatedByNewline() throws IOException {
		InMemoryTask task = new InMemoryTask();
		task.put("/a", new DistributedResource(new ChunkResource("/a", new byte[][] { "AA".getBytes() })));
		task.put("/b", new DistributedResource(new ChunkResource("/b", new byte[][] { "BBB".getBytes() })));

		ConcatenatedResource.ConcatenatedInputStream stream =
				new ConcatenatedResource.ConcatenatedInputStream(new String[] { "/a", "/b" });

		byte[] all = drain(stream);
		Assert.assertArrayEquals("AA\nBBB\n".getBytes(), all);
		Assert.assertEquals(-1, stream.read());
	}

	/**
	 * A single-child concatenation must reproduce that child's bytes followed by
	 * the trailing newline separator, confirming the end-of-children transition
	 * fires even with one file.
	 */
	@Test(timeout = 5000)
	public void singleChildIsFollowedByTrailingNewline() throws IOException {
		InMemoryTask task = new InMemoryTask();
		task.put("/only", new DistributedResource(new ChunkResource("/only", new byte[][] { "solo".getBytes() })));

		ConcatenatedResource.ConcatenatedInputStream stream =
				new ConcatenatedResource.ConcatenatedInputStream(new String[] { "/only" });

		Assert.assertArrayEquals("solo\n".getBytes(), drain(stream));
	}
}
