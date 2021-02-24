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
