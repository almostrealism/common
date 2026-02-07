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

package io.flowtree.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Default implementation of {@link UrlProfilingTask.Producer} that reads
 * a list of URLs from a remote file and randomly selects from them.
 *
 * @author Michael Murray
 */
public class DefaultProducer implements UrlProfilingTask.Producer {
	private String dir, uri, sufix;
	private List<String> files;
	private int size = 10;

	public void init() {
		InputStream is = null;

		try {
			is = new URL(this.dir).openStream();
		} catch (IOException ioe) {
			throw new RuntimeException("DefaultProducer: Error initializing -- " +
								ioe.getMessage());
		}

		BufferedReader in = new BufferedReader(new InputStreamReader(is));
		this.files = new ArrayList<>();

		String line;

		try {
			line = in.readLine();

			while (line != null) {
				this.files.add(line);
				line = in.readLine();
			}

			in.close();
		} catch (IOException ioe) {
			throw new RuntimeException("DefaultProducer: Error initializing -- " +
					ioe.getMessage());
		}

		System.out.println("DefaultProducer: Initialized with " + this.files.size() + " URLs.");
	}

	@Override
	public String nextURL() {
		StringBuilder b = new StringBuilder();

		if (this.uri != null) b.append(this.uri);
		b.append(this.files.get(ThreadLocalRandom.current().nextInt(this.files.size())));
		if (this.sufix != null) b.append(this.sufix);

		return b.toString();
	}

	@Override
	public int nextSize() { return this.size; }

	@Override
	public void set(String key, String value) {
		if (key.equals("dir")) {
			this.dir = value;
			this.init();
		} else if (key.equals("uri")) {
			this.uri = value;
			if (this.dir == null) this.set("dir", uri + "files.txt");
		} else if (key.equals("size")) {
			this.size = Integer.parseInt(value);
		}
	}

	@Override
	public String encode() {
		StringBuilder b = new StringBuilder();

		if (this.dir != null) {
			b.append(":dir=");
			b.append(this.dir);
		}

		if (this.uri != null) {
			b.append(":uri=");
			b.append(this.uri);
		}

		b.append(":size=");
		b.append(this.size);

		return b.toString();
	}
}
