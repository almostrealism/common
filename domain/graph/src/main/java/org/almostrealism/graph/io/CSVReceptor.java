/*
 * Copyright 2024 Michael Murray
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

/**
 * A receptor that writes received values to a CSV (Comma-Separated Values) output stream.
 *
 * <p>{@code CSVReceptor} extends {@link ReceptorConsumer} to provide a way to log
 * or export data flowing through the computation graph to a file or stream in CSV
 * format. Each line contains the index (sample number) and the value.</p>
 *
 * <p>The receptor supports a configurable sampling rate to reduce output volume
 * when processing high-frequency data like audio samples.</p>
 *
 * <p>Output format:</p>
 * <pre>
 * index,value
 * 0,1.234
 * 100,2.345
 * 200,3.456
 * </pre>
 *
 * <p>Example usage for training loss logging:</p>
 * <pre>{@code
 * try (CSVReceptor<Double> receptor =
 *         new CSVReceptor<>(new FileOutputStream("results/loss.csv"), 100)) {
 *     optimizer.setReceptor(receptor);
 *     optimizer.optimize(epochs);
 * }
 * }</pre>
 *
 * @param <T> the type of values received (commonly Double or PackedCollection)
 * @author Michael Murray
 * @see ReceptorConsumer
 */
public class CSVReceptor<T> extends ReceptorConsumer<T> implements AutoCloseable {
	private final PrintWriter ps;
	private long index;
	private final int rate;
	private String lastValue;

	/**
	 * Creates a new CSVReceptor that writes every value to the output stream.
	 *
	 * @param out the output stream to write CSV data to
	 */
	public CSVReceptor(OutputStream out) {
		this(out, 1);
	}

	/**
	 * Creates a new CSVReceptor with a specified sampling rate.
	 *
	 * <p>Only every Nth value will be written to the output, where N is the rate.
	 * This is useful for reducing file size when logging high-frequency data.</p>
	 *
	 * @param out  the output stream to write CSV data to
	 * @param rate the sampling rate (1 = every value, 100 = every 100th value)
	 */
	public CSVReceptor(OutputStream out, int rate) {
		super(null);
		this.rate = rate;
		ps = new PrintWriter(new OutputStreamWriter(out));
		setConsumer(p -> {
			if (index % this.rate == 0) {
				lastValue = process(p);
				ps.println(index + "," + lastValue);
				ps.flush();
			}

			index++;
		});
	}

	/**
	 * Returns the last value that was written to the CSV output.
	 *
	 * @return the string representation of the last written value, or null if none
	 */
	public String getLastValue() {
		return lastValue;
	}

	/**
	 * Flushes any buffered output to the underlying stream.
	 */
	public void flush() {
		ps.flush();
	}

	/**
	 * Closes the underlying output stream.
	 *
	 * <p>This should be called when finished writing to ensure all data is flushed
	 * and resources are released. Using try-with-resources is recommended.</p>
	 */
	@Override
	public void close() {
		ps.close();
	}

	/**
	 * Converts a received value to its string representation for CSV output.
	 *
	 * <p>PackedCollection values are converted by extracting the first element.
	 * Other values use their default toString() representation.</p>
	 *
	 * @param o the object to convert
	 * @return the string representation of the value
	 */
	private static String process(Object o) {
		if (o instanceof PackedCollection) {
			return String.valueOf(((PackedCollection) o).toDouble(0));
		}

		return String.valueOf(o);
	}
}
