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

package io.almostrealism.code;

import java.io.*;
import java.net.URL;
import java.util.Hashtable;
import java.util.TreeSet;

import org.almostrealism.io.IOStreams;

public class UnicodeResource extends ResourceAdapter<byte[]> {
	private Hashtable<Long, String> data = new Hashtable<>();
	
	public UnicodeResource() { }
	
	public UnicodeResource(String data) { this.data.put(0l, data); }

	public UnicodeResource(File f) throws IOException {
		read(new FileInputStream(f));
	}

	public synchronized void load(byte data[], long offset, int len) {
		this.data.put(offset, new String(data, 0, len));
	}

	@Override
	public synchronized void load(IOStreams io) throws IOException { read(io.in); }

	@Override
	public synchronized void loadFromURI() throws IOException {
		read(new URL(getURI()).openStream());
	}
	
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
		data.put(0l, buf.toString());
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
