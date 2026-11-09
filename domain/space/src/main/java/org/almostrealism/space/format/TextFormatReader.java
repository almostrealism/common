/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.space.format;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the data lines of a line-oriented text format: lines of whitespace-separated values,
 * where lines starting with {@code #} are comments. It counts lines so that a reader can say
 * where a problem is. It does not close the stream it reads, which belongs to its caller.
 */
class TextFormatReader {
	/** The name of the format being read, used in error messages. */
	private final String format;

	/** The underlying line reader. */
	private final BufferedReader in;

	/** The number of lines read so far, including comments. */
	private int lineNumber;

	/**
	 * Creates a reader for the given format over the given stream, decoded as UTF-8.
	 *
	 * @param format the name of the format, used in error messages
	 * @param in     the stream to read
	 */
	TextFormatReader(String format, InputStream in) {
		this.format = format;
		this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
	}

	/**
	 * Returns the next line that is not a comment, or {@code null} at the end of the data.
	 *
	 * @return the next data line, or {@code null}
	 * @throws IOException if reading fails
	 */
	String nextOrNull() throws IOException {
		while (true) {
			String line = in.readLine();
			if (line == null) return null;

			lineNumber++;
			if (!line.startsWith("#")) return line;
		}
	}

	/**
	 * Returns the next line that is not a comment, where the data must not end yet.
	 *
	 * @return the next data line
	 * @throws EOFException if the data ends first
	 * @throws IOException  if reading fails
	 */
	String next() throws IOException {
		String line = nextOrNull();
		if (line == null) {
			throw new EOFException(format + " data ended after line " + lineNumber);
		}

		return line;
	}

	/**
	 * Returns the values on the next line that is not a comment, where the data must not end yet.
	 *
	 * @return the values on the line
	 * @throws IOException if the data ends first, reading fails, or a value is not a number
	 */
	double[] nextValues() throws IOException {
		return values(next());
	}

	/**
	 * Returns the given number of values from a data line, read from the start of the line.
	 *
	 * @param line  the line
	 * @param count the number of values the line must start with
	 * @return the first {@code count} values on the line
	 * @throws IOException if the line has fewer values or a value is not a number
	 */
	double[] values(String line, int count) throws IOException {
		double[] values = values(line);
		if (values.length < count) {
			throw error("expected " + count + " values but found " + values.length);
		}

		return values;
	}

	/**
	 * Returns the values on a data line.
	 *
	 * @param line the line
	 * @return the values on the line, which may be empty
	 * @throws IOException if a value is not a number
	 */
	double[] values(String line) throws IOException {
		String trimmed = line.trim();
		if (trimmed.isEmpty()) return new double[0];

		String[] terms = trimmed.split("\\s+");
		double[] values = new double[terms.length];

		for (int i = 0; i < terms.length; i++) {
			try {
				values[i] = Double.parseDouble(terms[i]);
			} catch (NumberFormatException e) {
				throw error("\"" + terms[i] + "\" is not a number");
			}
		}

		return values;
	}

	/**
	 * Returns an exception describing a problem with the line most recently read.
	 *
	 * @param problem what is wrong with the line
	 * @return the exception
	 */
	IOException error(String problem) {
		return new IOException(format + " line " + lineNumber + ": " + problem);
	}
}
