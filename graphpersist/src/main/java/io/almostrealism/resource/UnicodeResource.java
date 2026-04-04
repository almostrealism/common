/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.resource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Hashtable;
import java.util.TreeSet;

/**
 * A {@link ResourceAdapter} that holds text content as a map of byte-offset to string chunk,
 * allowing segments received at different offsets to be assembled in order.
 *
 * <p>Data is assembled in offset order by {@link #getData()}, which concatenates all chunks
 * and returns the result as a UTF-8 byte array.</p>
 */
public class UnicodeResource extends ResourceAdapter<byte[]> {
	/** Ordered map of byte offsets to the string chunks loaded at each offset. */
	private final Hashtable<Long, String> data = new Hashtable<>();

	/**
	 * Constructs an empty {@link UnicodeResource} with no data.
	 */
	public UnicodeResource() { }

	/**
	 * Constructs a {@link UnicodeResource} pre-loaded with the given string at offset 0.
	 *
	 * @param data The initial text content
	 */
	public UnicodeResource(String data) { this.data.put(0L, data); }

	/**
	 * Constructs a {@link UnicodeResource} by reading all text from the given file.
	 *
	 * @param f The file to read
	 * @throws IOException If reading the file fails
	 */
	public UnicodeResource(File f) throws IOException {
		this(new FileInputStream(f));
	}

	/**
	 * Constructs a {@link UnicodeResource} by reading all text from the given input stream.
	 *
	 * @param in The input stream to read from
	 * @throws IOException If reading the stream fails
	 */
	public UnicodeResource(InputStream in) throws IOException {
		read(in);
	}

	/** {@inheritDoc} Stores the byte array decoded as a string at the given offset. */
	public synchronized void load(byte[] data, long offset, int len) {
		this.data.put(offset, new String(data, 0, len));
	}

	@Override
	public synchronized void load(IOStreams io) throws IOException { read(io.in); }

	@Override
	public synchronized void loadFromURI() throws IOException {
		read(new URL(getURI()).openStream());
	}
	
	/**
	 * Reads all text from the given input stream, storing it as a single chunk at offset 0.
	 *
	 * @param in The input stream to read from
	 * @throws IOException If reading fails
	 */
	private void read(InputStream in) throws IOException {
		StringBuffer buf = new StringBuffer();
		
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
			String line;
			
			while ((line = r.readLine()) != null) {
				buf.append(line);
				buf.append("\n");
			}
		}

		data.clear();
		data.put(0L, buf.toString());
	}

	@Override
	public byte[] getData() {
		TreeSet<Long> l = new TreeSet<>();
		l.addAll(data.keySet());

		StringBuffer buf = new StringBuffer();

		for (long k : l) {
			buf.append(data.get(k));
		}

		return buf.toString().getBytes();
	}
}
