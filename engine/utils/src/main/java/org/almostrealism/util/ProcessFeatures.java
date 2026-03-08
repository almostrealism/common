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

package org.almostrealism.util;

/**
 * Interface providing utilities for executing external system processes.
 *
 * <p>This interface provides a simple API for running external commands
 * and capturing their exit codes. Output from the process is inherited
 * (displayed on the current console).</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class BuildRunner implements ProcessFeatures {
 *     public void build() {
 *         int exitCode = run("mvn", "clean", "install");
 *         if (exitCode != 0) {
 *             System.err.println("Build failed with exit code: " + exitCode);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 */
public interface ProcessFeatures {

	/**
	 * Executes an external command and waits for it to complete.
	 * Standard output and error streams are inherited from the current process.
	 *
	 * @param command the command and arguments to execute
	 * @return the exit code of the process, or -1 if an exception occurred
	 */
	default int run(String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			builder.inheritIO();

			Process process = builder.start();
			return process.waitFor();
		} catch (Exception e) {
			e.printStackTrace();
			return -1;
		}
	}
}
