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

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link InstructionPromptBuilder} verifying that conditional
 * sections are included or excluded based on builder configuration.
 */
public class InstructionPromptBuilderTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void includesUserPromptAlways() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Fix the authentication bug")
			.build();
		assertTrue("Expected prompt text in output",
			result.contains("Fix the authentication bug"));
	}

	@Test(timeout = 30000)
	public void includesCommunicationSectionWhenUrlSet() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.build();
		assertTrue("Expected Communication section when workstream URL is set",
			result.contains("## Communication"));
		assertTrue("Expected send_message reference",
			result.contains("send_message"));
	}

	@Test(timeout = 30000)
	public void excludesMessageSectionWhenNoUrl() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertFalse("Expected no send_message reference without workstream URL",
			result.contains("send_message"));
		assertFalse("Expected no Communication section without workstream URL",
			result.contains("## Communication"));
	}

	@Test(timeout = 30000)
	public void includesTestIntegrityWhenProtected() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setProtectTestFiles(true)
			.build();
		assertTrue("Expected Test Integrity Policy section",
			result.contains("Test Integrity Policy"));
	}

	@Test(timeout = 30000)
	public void includesMergeConflictSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setHasMergeConflicts(true)
			.setBaseBranch("master")
			.setTargetBranch("feature/work")
			.setMergeConflictFiles(Arrays.asList("src/Main.java", "README.md"))
			.build();
		assertTrue("Expected Merge Conflicts section",
			result.contains("## Merge Conflicts"));
		assertTrue("Expected conflict file src/Main.java in output",
			result.contains("src/Main.java"));
		assertTrue("Expected conflict file README.md in output",
			result.contains("README.md"));
	}

	@Test(timeout = 30000)
	public void includesBranchCatchupSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setTargetBranch("feature/my-branch")
			.build();
		assertTrue("Expected Branch Awareness section when target branch is set",
			result.contains("Branch Awareness"));
		assertTrue("Expected workstream_context reference",
			result.contains("workstream_context"));
	}

	@Test(timeout = 30000)
	public void includesBudgetSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setMaxTurns(100)
			.setMaxBudgetUsd(5.0)
			.build();
		assertTrue("Expected budget amount in output",
			result.contains("$5.00"));
		assertTrue("Expected turn limit in output",
			result.contains("100 turns"));
	}

	@Test(timeout = 30000)
	public void includesPlanningDocSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setPlanningDocument("docs/planning/feature-plan.md")
			.build();
		assertTrue("Expected Planning Document section",
			result.contains("## Planning Document"));
		assertTrue("Expected planning document path in output",
			result.contains("docs/planning/feature-plan.md"));
	}

	@Test(timeout = 30000)
	public void includesDependentReposSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setTargetBranch("feature/work")
			.setDependentRepoPaths(Arrays.asList(
				"/workspace/project/repo-a",
				"/workspace/project/repo-b"))
			.build();
		assertTrue("Expected Dependent Repositories section",
			result.contains("## Dependent Repositories"));
		assertTrue("Expected first dep repo path in output",
			result.contains("/workspace/project/repo-a"));
		assertTrue("Expected second dep repo path in output",
			result.contains("/workspace/project/repo-b"));
	}

	@Test(timeout = 30000)
	public void excludesDependentReposSectionWhenUnset() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertFalse("Expected no Dependent Repositories section when paths not set",
			result.contains("## Dependent Repositories"));
	}
}
