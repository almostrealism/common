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

/**
 * Interface for handling and storing job execution output.
 *
 * <p>OutputHandler is implemented by classes that need to receive and process
 * the results of job executions. This enables pluggable output handling
 * strategies such as file storage, database persistence, or network transmission.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class FileOutputHandler implements OutputHandler {
 *     @Override
 *     public void storeOutput(long time, int uid, JobOutput output) {
 *         // Store output to a file
 *         String filename = String.format("job_%d_%d.out", uid, time);
 *         Files.write(Path.of(filename), output.getData());
 *     }
 * }
 * }</pre>
 *
 * @see JobOutput
 */
public interface OutputHandler {
	/**
	 * Stores the output from a job execution.
	 *
	 * @param time the timestamp when the output was produced (typically epoch millis)
	 * @param uid a unique identifier for the job
	 * @param output the job output to store
	 */
	void storeOutput(long time, int uid, JobOutput output);
}