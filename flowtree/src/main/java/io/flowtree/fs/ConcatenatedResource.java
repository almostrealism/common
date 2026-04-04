/*
 * Copyright 2018 Michael Murray
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

import io.almostrealism.persist.ResourceHeaderParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * A {@link DistributedResource} whose content is formed by logically
 * concatenating the byte streams of all resources in a given directory of
 * the distributed file system.
 *
 * <p>The underlying storage contains a single header line of the form
 * {@code <ConcatenatedResource><directory-uri>}. When the resource is
 * read, the header is parsed to discover the constituent child resources
 * and a {@link ConcatenatedInputStream} is constructed to stream them
 * sequentially, separated by newline bytes.
 *
 * <p>The total byte count reported by {@link #getTotalBytes()} is the
 * sum of all children's sizes plus the separator newlines.
 *
 * @author  Michael Murray
 */
public class ConcatenatedResource extends DistributedResource {

	/** Magic prefix that identifies a resource as a {@link ConcatenatedResource}. */
	private static final String header = "<ConcatenatedResource>";

	/** Suffix appended after the last child resource's content (currently empty). */
	private static final String footer = "";

	/**
	 * A {@link ResourceHeaderParser} that recognises the
	 * {@link ConcatenatedResource} header byte sequence and maps it to the
	 * {@link ConcatenatedResource} class.
	 */
	public static class ConcatenatedResourceHeaderParser implements ResourceHeaderParser {

		/**
		 * Returns {@code true} if the given header bytes start with the
		 * {@code <ConcatenatedResource>} magic string.
		 *
		 * @param head the first bytes of the resource
		 * @return {@code true} if this parser handles the resource
		 */
		public boolean doesHeaderMatch(byte[] head) {
			String s = new String(head);
			return s.startsWith(header);
		}

		/**
		 * Returns the {@link ConcatenatedResource} class so that the resource
		 * framework can instantiate the correct type.
		 *
		 * @return {@code ConcatenatedResource.class}
		 */
		public Class getResourceClass() { return ConcatenatedResource.class; }
	}

	/**
	 * An {@link InputStream} that transparently concatenates the input streams
	 * of multiple distributed child resources, inserting a newline separator
	 * between consecutive resources.
	 */
	public static class ConcatenatedInputStream extends InputStream {

		/** Ordered list of child resource URIs to concatenate. */
		private final String[] files;

		/** Separator bytes injected between consecutive child streams. */
		private byte[] sep = "\n".getBytes();

		/** The input stream currently being read. */
		private InputStream current;

		/** Index into {@link #files} pointing to the next file to open. */
		private int index;

		/** Position within the {@link #sep} byte array currently being emitted. */
		private int sepIndex = 0;

		/** Running count of bytes read from the current child stream. */
		private int bytes;

		/**
		 * Constructs a new {@link ConcatenatedInputStream} that will read the
		 * given child resource URIs in order.
		 *
		 * @param files ordered array of distributed-resource URIs to concatenate
		 */
		public ConcatenatedInputStream(String[] files) {
			this.files = files;
			this.index = 0;
		}

		/**
		 * Returns the next byte from the concatenated stream. The stream
		 * transitions from one child resource to the next by emitting a
		 * separator sequence. Returns {@code -1} when all child resources
		 * have been exhausted.
		 *
		 * @return the next byte (0–255), or {@code -1} at end-of-stream
		 * @throws IOException if a child resource stream cannot be opened or read
		 */
		public int read() throws IOException {
			if (this.index == 0 && this.current == null) this.next();

			int read = -1;
			if (this.sepIndex <= 0) read = this.current.read();

			if (read < 0) {
				if (this.sepIndex < this.sep.length) {
					return this.sep[this.sepIndex++];
				} else if (this.sepIndex == this.sep.length) {
					while (read < 0) {
						if (this.index >= this.files.length) {
							return -1;
						} else {
							this.next();
							read = this.current.read();
						}
					}

					this.sepIndex = 0;
				}
			}

			if (read >= 0) this.bytes++;
			return read;
		}

		/**
		 * Opens the next child resource stream from
		 * {@link ResourceDistributionTask#getCurrentTask()}, closing the
		 * previously open stream first. If this is the last child, the footer
		 * bytes are appended to the separator sequence.
		 *
		 * @throws IOException if the next resource stream cannot be obtained
		 */
		private void next() throws IOException {
			if (this.current != null) {
				System.out.println("ConcatenatedResource: Read " + this.bytes +
									" from " + this.current);
				this.current.close();
			}

			this.bytes = 0;
			this.current = ResourceDistributionTask.getCurrentTask().
							getResource(this.files[this.index++]).getInputStream();
			if (index == this.files.length)
				this.sep = (new String(this.sep) + footer).getBytes();
		}
	}

	/**
	 * Returns an {@link InputStream} that reads the header line from the
	 * underlying distributed storage, resolves the directory URI, and returns a
	 * {@link ConcatenatedInputStream} that streams all child resources in order.
	 *
	 * @return an input stream over the concatenated child resources
	 * @throws RuntimeException wrapping any {@link IOException} encountered
	 *                          while reading the header
	 */
	public InputStream getInputStream() {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		BufferedReader buf = new BufferedReader(new InputStreamReader(super.getInputStream()));

		try {
			String l = buf.readLine();
			buf.close();
			l = l.substring(header.length());
			String[] c = t.getChildren(l);
			return new ConcatenatedInputStream(c);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns the total byte count of this concatenated resource. This is the
	 * sum of all children's {@link DistributedResource#getTotalBytes()} values
	 * plus one newline separator byte per child plus the footer bytes.
	 *
	 * @return total byte length of the concatenated content
	 * @throws RuntimeException wrapping any {@link IOException} encountered
	 *                          while reading the header
	 */
	public long getTotalBytes() {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();

		try (BufferedReader buf = new BufferedReader(new InputStreamReader(super.getInputStream()))) {
			String l = buf.readLine();
			l = l.substring(header.length());
			String[] c = t.getChildren(l);
			long tot = c.length * 2L;

			for (int i = 0; i < c.length; i++) {
				DistributedResource r = t.getResource(c[i]);
				tot += r.getTotalBytes();
			}

			tot += footer.getBytes().length;
			return tot;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Creates a ConcatenatedResource at the specified URI on the distributed database.
	 * This ConcatenatedResource will concatenate the contents of the specified directory.
	 *
	 * @param uri  URI pointing to where the resource shall reside.
	 * @param dir  Directory containing files to use for the resource.
	 * @throws IOException if an I/O error occurs while writing to the output stream
	 */
	public static void createConcatenatedResource(String uri, String dir) throws IOException {
		try (PrintStream out = new PrintStream(ResourceDistributionTask.
									getCurrentTask().getOutputStream(uri))) {
			out.println(header + dir);
			out.flush();
		}
	}
}
