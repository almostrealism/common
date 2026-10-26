/*
 * Copyright 2026 Michael Murray
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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link PostCompletionConfig}, the post-completion command settings
 * shared by {@link CodingAgentJob} and {@link CodingAgentJobFactory}.
 */
public class PostCompletionConfigTest extends TestSuiteBase {

	/** A populated configuration survives its own encode/decode cycle. */
	@Test(timeout = 10000)
	public void encodedFormRoundTrips() {
		PostCompletionConfig source = new PostCompletionConfig();
		source.setCommand("mvn test");
		source.setWorkingDir("/tmp/work");
		source.setTimeoutSeconds(600);
		source.setMaxPasses(7);

		StringBuilder sb = new StringBuilder();
		source.appendEncoded(sb);

		PostCompletionConfig decoded = new PostCompletionConfig();
		for (String pair : sb.substring(2).split("::")) {
			String[] kv = pair.split(":=", 2);
			assertTrue("unconsumed key " + kv[0], decoded.applySetting(kv[0], kv[1]));
		}
		assertEquals("mvn test", decoded.getCommand());
		assertEquals("/tmp/work", decoded.getWorkingDir());
		assertEquals(600, decoded.getTimeoutSeconds());
		assertEquals(7, decoded.getMaxPasses());
	}

	/** Nothing is encoded, and nothing is copied, while no command is set. */
	@Test(timeout = 10000)
	public void disabledConfigurationIsInert() {
		PostCompletionConfig source = new PostCompletionConfig();
		source.setWorkingDir("/tmp/work");

		StringBuilder sb = new StringBuilder();
		source.appendEncoded(sb);
		assertEquals("", sb.toString());

		PostCompletionConfig target = new PostCompletionConfig();
		source.applyTo(target);
		assertNull(target.getWorkingDir());
		assertFalse(target.isEnabled());
	}

	/** {@link PostCompletionConfig#applyTo} copies every value of an enabled configuration. */
	@Test(timeout = 10000)
	public void applyToCopiesEnabledConfiguration() {
		PostCompletionConfig source = new PostCompletionConfig();
		source.setCommand("make check");
		source.setWorkingDir("/src");
		source.setTimeoutSeconds(90);
		source.setMaxPasses(2);

		PostCompletionConfig target = new PostCompletionConfig();
		source.applyTo(target);
		assertEquals("make check", target.getCommand());
		assertEquals("/src", target.getWorkingDir());
		assertEquals(90, target.getTimeoutSeconds());
		assertEquals(2, target.getMaxPasses());
	}

	/** Clearing the command resets its dependents and removes their published keys. */
	@Test(timeout = 10000)
	public void clearingCommandResetsDependentsAndPublishedKeys() {
		Map<String, String> published = new HashMap<>();
		PostCompletionConfig config = new PostCompletionConfig(published::put);
		config.setCommand("mvn test");
		config.setWorkingDir("/tmp/work");
		config.setTimeoutSeconds(600);
		assertEquals("600", published.get("postCmdTimeout"));

		config.setCommand(null);
		assertNull(config.getWorkingDir());
		assertEquals(PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, config.getTimeoutSeconds());
		assertNull(published.get("postCmd"));
		assertNull(published.get("postCmdDir"));
		assertNull(published.get("postCmdTimeout"));
	}

	/** A non-positive pass cap is rejected. */
	@Test(timeout = 10000)
	public void nonPositivePassCapRejected() {
		PostCompletionConfig config = new PostCompletionConfig();
		try {
			config.setMaxPasses(0);
			fail("a pass cap of 0 must be rejected");
		} catch (IllegalArgumentException expected) {
			assertEquals(PostCompletionCommandRule.DEFAULT_MAX_PASSES, config.getMaxPasses());
		}
	}

	/** The factory carries its post-completion settings onto the jobs it creates. */
	@Test(timeout = 10000)
	public void factoryPropagatesToCreatedJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setPostCompletionCommand("mvn verify");
		factory.setPostCompletionWorkingDir("/repo");
		factory.setPostCompletionTimeoutSeconds(120);
		factory.setMaxPostCompletionPasses(4);

		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertEquals("mvn verify", job.getPostCompletionCommand());
		assertEquals("/repo", job.getPostCompletionWorkingDir());
		assertEquals(120, job.getPostCompletionTimeoutSeconds());
		assertEquals(4, job.getMaxPostCompletionPasses());
	}
}
