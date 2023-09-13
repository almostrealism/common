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

package org.almostrealism.io.test;

import org.almostrealism.io.JobOutput;
import org.junit.Test;

import java.util.Optional;
import java.util.stream.Stream;

public class JobOutputDecodingTest {
	@Test
	public void decode() {
		JobOutput output = new JobOutput();
		output.setTaskId("test-task");
		output.setOutput("asdfasdf");
		output.setUser("user");
		output.setPassword("password");
		output.setTime(1234567);

		Optional<JobOutput> result = Stream.of(output)
								.map(JobOutput::encode)
								.map(JobOutput::decode)
								.findAny();
		assert result.isPresent();

		assert result.get().getTaskId().equals("test-task");
		assert result.get().getOutput().equals("asdfasdf");
		assert result.get().getUser().equals("user");
		assert result.get().getPassword().equals("password");
		assert result.get().getTime() == 1234567l;
	}
}
