/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;


/**
 * A {@link PrintWriter} implementation that writes to a file.
 *
 * <p>FilePrintWriter supports indentation using 4-space increments and
 * automatically flushes output to the file. The file is opened for writing
 * when the writer is constructed.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (FilePrintWriter writer = new FilePrintWriter(new File("output.txt"))) {
 *     writer.println("Line 1");
 *     writer.moreIndent();
 *     writer.println("Indented line");
 *     writer.lessIndent();
 *     writer.println("Back to no indent");
 * }
 * }</pre>
 *
 * @see PrintWriter
 * @see PrintStreamPrintWriter
 */
public class FilePrintWriter implements PrintWriter {
	private StringBuffer indent;
	private java.io.PrintWriter out;

	/**
	 * Creates a new FilePrintWriter that writes to the specified file.
	 * If the file exists, it will be overwritten.
	 *
	 * @param f the file to write to
	 * @throws FileNotFoundException if the file cannot be created or opened
	 */
	public FilePrintWriter(File f) throws FileNotFoundException {
		this.out = new java.io.PrintWriter(new FileOutputStream(f), true);
	}

	/**
	 * Closes the underlying file writer.
	 */
	@Override
	public void close() { this.out.close(); }

	/**
	 * Increases the indentation level by 4 spaces.
	 */
	@Override
	public void moreIndent() { this.indent.append("    "); }

	/**
	 * Decreases the indentation level by 4 spaces.
	 */
	@Override
	public void lessIndent() { this.indent.delete(this.indent.length() - 4, this.indent.length()); }

	/**
	 * Prints a string with the current indentation prefix.
	 *
	 * @param s the string to print
	 */
	@Override
	public void print(String s) { this.out.print(this.indent.toString() + s); }

	/**
	 * Prints a string with indentation, followed by a newline.
	 *
	 * @param s the string to print
	 */
	@Override
	public void println(String s) { this.out.println(this.indent.toString() + s); }

	/**
	 * Prints a newline character.
	 */
	@Override
	public void println() { this.out.println(); }
}
