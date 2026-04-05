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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GitManagedJob} and {@link ClaudeCodeJob} serialization
 * via the {@code encode()} and {@code set()} methods. Uses
 * {@link ClaudeCodeJob} as the concrete implementation since
 * {@link GitManagedJob} is abstract.
 */
public class GitManagedJobSerializationTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void encodeIncludesAllFields() {
		ClaudeCodeJob job = new ClaudeCodeJob("task-1", "Fix the bug");
		job.setAllowedTools("Read,Edit,Bash");
		job.setMaxTurns(75);
		job.setTargetBranch("feature/fix");
		job.setBaseBranch("develop");

		String encoded = job.encode();

		assertTrue("Expected class name prefix",
			encoded.startsWith(ClaudeCodeJob.class.getName()));
		assertTrue("Expected prompt key",
			encoded.contains("prompt:="));
		assertTrue("Expected tools key",
			encoded.contains("tools:="));
		assertTrue("Expected maxTurns key",
			encoded.contains("maxTurns:="));
		assertTrue("Expected branch key",
			encoded.contains("branch:="));
		assertTrue("Expected baseBranch key",
			encoded.contains("baseBranch:="));
	}

	@Test(timeout = 30000)
	public void setRestoresAllFields() {
		ClaudeCodeJob job = new ClaudeCodeJob();
		job.set("taskId", "task-2");
		job.set("prompt", Base64.getEncoder()
			.encodeToString("Do the work".getBytes(StandardCharsets.UTF_8)));
		job.set("tools", Base64.getEncoder()
			.encodeToString("Read,Glob".getBytes(StandardCharsets.UTF_8)));
		job.set("maxTurns", "30");
		job.set("branch", Base64.getEncoder()
			.encodeToString("feature/test".getBytes(StandardCharsets.UTF_8)));

		assertEquals("task-2", job.getTaskId());
		assertEquals("Do the work", job.getPrompt());
		assertEquals("Read,Glob", job.getAllowedTools());
		assertEquals(30, job.getMaxTurns());
		assertEquals("feature/test", job.getTargetBranch());
	}

	@Test(timeout = 30000)
	public void encodeDecodeRoundTrip() {
		ClaudeCodeJob original = new ClaudeCodeJob("task-rt", "Round trip prompt");
		original.setAllowedTools("Read,Edit");
		original.setMaxTurns(42);
		original.setMaxBudgetUsd(7.5);
		original.setTargetBranch("feature/roundtrip");
		original.setBaseBranch("main");

		String encoded = original.encode();

		// Parse the wire format: className::key1:=value1::key2:=value2...
		ClaudeCodeJob restored = new ClaudeCodeJob();
		String[] parts = encoded.split("::");
		for (int i = 1; i < parts.length; i++) {
			int sep = parts[i].indexOf(":=");
			if (sep > 0) {
				String key = parts[i].substring(0, sep);
				String value = parts[i].substring(sep + 2);
				restored.set(key, value);
			}
		}

		assertEquals(original.getPrompt(), restored.getPrompt());
		assertEquals(original.getAllowedTools(), restored.getAllowedTools());
		assertEquals(original.getMaxTurns(), restored.getMaxTurns());
		assertEquals(original.getMaxBudgetUsd(), restored.getMaxBudgetUsd(), 0.001);
		assertEquals(original.getTargetBranch(), restored.getTargetBranch());
		assertEquals(original.getBaseBranch(), restored.getBaseBranch());
	}

	@Test(timeout = 30000)
	public void factoryEncodeDecodeRoundTrip() {
		ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory("Prompt A", "Prompt B");
		factory.setAllowedTools("Read,Edit,Bash");
		factory.setTargetBranch("feature/factory-test");
		factory.setMaxTurns(60);
		factory.setMaxBudgetUsd(15.0);

		String encoded = factory.encode();

		assertTrue("Expected Factory class name in encoded output",
			encoded.contains(ClaudeCodeJob.Factory.class.getName()));
		assertTrue("Expected prompts key in encoded output",
			encoded.contains("prompts:="));
		assertTrue("Expected branch key in encoded output",
			encoded.contains("branch:="));
		assertTrue("Expected tools key in encoded output",
			encoded.contains("tools:="));
	}

	@Test(timeout = 30000)
	public void factoryDependentReposRoundTrip() {
		ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory("Prompt");
		factory.setDependentRepos(Arrays.asList(
			"https://github.com/org/repo-a",
			"https://github.com/org/repo-b"));

		String encoded = factory.encode();
		assertTrue("Expected dependentRepos key in encoded output",
			encoded.contains("dependentRepos:="));

		// Parse the wire format to restore the factory
		ClaudeCodeJob.Factory restored = new ClaudeCodeJob.Factory();
		String[] parts = encoded.split("::");
		for (int i = 1; i < parts.length; i++) {
			int sep = parts[i].indexOf(":=");
			if (sep > 0) {
				restored.set(parts[i].substring(0, sep), parts[i].substring(sep + 2));
			}
		}

		List<String> repos = restored.getDependentRepos();
		assertNotNull("Expected non-null dependentRepos after decode", repos);
		assertEquals(2, repos.size());
		assertEquals("https://github.com/org/repo-a", repos.get(0));
		assertEquals("https://github.com/org/repo-b", repos.get(1));
	}

	@Test(timeout = 30000)
	public void factoryDependentReposAbsentWhenEmpty() {
		ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory("Prompt");
		factory.setDependentRepos(null);

		String encoded = factory.encode();
		assertFalse("Expected no dependentRepos key when null",
			encoded.contains("dependentRepos:="));

		ClaudeCodeJob.Factory factory2 = new ClaudeCodeJob.Factory("Prompt");
		factory2.setDependentRepos(Collections.emptyList());

		String encoded2 = factory2.encode();
		assertFalse("Expected no dependentRepos key when empty list",
			encoded2.contains("dependentRepos:="));
	}
}
