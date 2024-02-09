/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.graph.io;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.ReceptorConsumer;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public class CSVReceptor<T> extends ReceptorConsumer<T> implements AutoCloseable {
	private PrintWriter ps;
	private long index;
	private int rate;

	public CSVReceptor(OutputStream out) {
		this(out, 1);
	}

	public CSVReceptor(OutputStream out, int rate) {
		super(null);
		this.rate = rate;
		ps = new PrintWriter(new OutputStreamWriter(out));
		setConsumer(p -> {
			if (index % this.rate == 0) {
				ps.println(index + "," + process(p));
				ps.flush();
			}

			index++;
		});
	}

	public void flush() {
		ps.flush();
	}

	@Override
	public void close() {
		ps.close();
	}

	private static String process(Object o) {
		if (o instanceof PackedCollection) {
			return String.valueOf(((PackedCollection<?>) o).toDouble(0));
		}

		return String.valueOf(o);
	}
}
