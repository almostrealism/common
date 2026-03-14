/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.io;

import java.io.PrintStream;

/**
 * A {@link PrintWriter} implementation that writes to a {@link PrintStream}.
 *
 * <p>PrintStreamPrintWriter supports indentation using tab characters.
 * Each call to {@link #moreIndent()} adds one tab, and {@link #lessIndent()}
 * removes one. This is commonly used with {@code System.out} or {@code System.err}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PrintWriter writer = new PrintStreamPrintWriter(System.out);
 * writer.println("Root level");
 * writer.moreIndent();
 * writer.println("Indented with one tab");
 * writer.moreIndent();
 * writer.println("Indented with two tabs");
 * writer.lessIndent();
 * writer.lessIndent();
 * writer.println("Back to root");
 * }</pre>
 *
 * @see PrintWriter
 * @see FilePrintWriter
 */
public class PrintStreamPrintWriter implements PrintWriter {
	private PrintStream out;
	private int indent = 0;

	/**
	 * Creates a new PrintStreamPrintWriter that writes to the specified stream.
	 *
	 * @param out the print stream to write to
	 */
	public PrintStreamPrintWriter(PrintStream out) {
		this.out = out;
	}

	/**
	 * Increases the indentation level by one tab.
	 */
	@Override
	public void moreIndent() { indent++; }

	/**
	 * Decreases the indentation level by one tab.
	 */
	@Override
	public void lessIndent() { indent--; }

	/**
	 * Prints a string without indentation prefix.
	 *
	 * @param s the string to print
	 */
	@Override
	public void print(String s) {
		this.out.print(s);
	}

	/**
	 * Prints the current indentation followed by the string and a newline.
	 *
	 * @param s the string to print
	 */
	@Override
	public void println(String s) {
		for (int i = 0; i < indent; i++) {
			print("\t");
		}

		print(s);
		println();
	}

	/**
	 * Prints a newline character.
	 */
	@Override
	public void println() {
		this.out.println();
	}
}
