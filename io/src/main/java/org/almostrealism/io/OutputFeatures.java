/*
 * Copyright 2024 Michael Murray
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

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Provides utility methods for directing console output to files and other destinations.
 *
 * <p>This interface provides factory methods for creating output listeners that can be
 * attached to {@link Console} instances to capture logging output.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Direct all console output to a file
 * Console.root().addListener(OutputFeatures.fileOutput("/path/to/log.txt"));
 *
 * // Now all console output goes to both System.out and the file
 * Console.root().println("This is logged to file and console");
 * }</pre>
 *
 * <h2>Test Output Pattern</h2>
 * <p>Commonly used in tests to capture output for later review:</p>
 * <pre>{@code
 * @Test
 * public void myTest() {
 *     Console.root().addListener(
 *         OutputFeatures.fileOutput("/workspace/project/test_output/results.txt"));
 *
 *     // Test code with logging...
 * }
 * }</pre>
 *
 * @see Console#addListener(java.util.function.Consumer)
 * @see ConsoleFeatures
 */
public interface OutputFeatures {
	/**
	 * Creates a file output listener that writes console output to the specified file.
	 * The file is opened for writing immediately and flushed after each write.
	 *
	 * <p>If the file cannot be opened, an error message is printed and a no-op
	 * listener is returned.</p>
	 *
	 * @param destination the path to the output file
	 * @return a consumer that writes strings to the file
	 */
	static Consumer<String> fileOutput(String destination) {
		try {
			FileOutputStream f = new FileOutputStream(destination);
			PrintWriter out = new PrintWriter(new OutputStreamWriter(f));

			return s -> {
				try {
					out.print(s);
					out.flush();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
		} catch (FileNotFoundException e) {
			System.out.println("Output destination does not exist - " +
								e.getMessage());
			return s -> {};
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
