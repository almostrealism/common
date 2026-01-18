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

import java.util.function.Consumer;

/**
 * A simple interface for text output with support for indentation.
 *
 * <p>PrintWriter provides a basic abstraction for writing text output with
 * configurable indentation levels. Unlike {@link java.io.PrintWriter}, this
 * interface focuses on simple string output with hierarchical indentation
 * support, making it suitable for generating formatted text like code,
 * configuration files, or structured logs.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (PrintWriter out = new FilePrintWriter(new File("output.txt"))) {
 *     out.println("public class Example {");
 *     out.moreIndent();
 *     out.println("public void method() {");
 *     out.moreIndent();
 *     out.println("// code here");
 *     out.lessIndent();
 *     out.println("}");
 *     out.lessIndent();
 *     out.println("}");
 * }
 * }</pre>
 *
 * <h2>Factory Method</h2>
 * <pre>{@code
 * // Create a PrintWriter from a Consumer
 * PrintWriter pw = PrintWriter.of(System.out::print);
 * pw.println("Hello, world!");
 * }</pre>
 *
 * @see FilePrintWriter
 * @see PrintStreamPrintWriter
 */
public interface PrintWriter extends AutoCloseable {
	/** Increases the indent. */
	void moreIndent();
	
	/** Reduces the indent. */
	void lessIndent();
	
	/** Appends the specified String. */
	void print(String s);
	
	/** Appends the specified String, followed by a new line character. */
	void println(String s);
	
	/** Appends a new line character. */
	void println();

	/**
	 * Closes this writer and releases any associated resources.
	 * The default implementation does nothing.
	 */
	@Override
	default void close() { }

	/**
	 * Creates a PrintWriter that delegates to the specified consumer.
	 * Note: The returned writer does not support indentation.
	 *
	 * @param c the consumer to receive output strings
	 * @return a new PrintWriter instance
	 */
	static PrintWriter of(Consumer<String> c) {
		return new PrintWriter() {
			// TODO  Support indent
			@Override
			public void moreIndent() { }

			@Override
			public void lessIndent() { }

			@Override
			public void print(String s) {
				c.accept(s);
			}

			@Override
			public void println(String s) {
				print(s);
				println();
			}

			@Override
			public void println() {
				c.accept("\n");
			}
		};
	}
}
