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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the binary-file-litter restart warning rendered by
 * {@link InstructionPromptBuilder#setInvalidFilesViolation(String)}.
 */
public class InstructionPromptBuilderInvalidFilesTest extends TestSuiteBase {

	/** The litter warning and the detected file list appear when set. */
	@Test(timeout = 30000)
	public void invalidFilesWarningPrependedWhenSet() {
		String prompt = new InstructionPromptBuilder()
				.setPrompt("do the work")
				.setInvalidFilesViolation("model.bin, sub/weights.bin")
				.build();

		assertTrue("Expected binary file litter warning header",
				prompt.contains("BINARY FILE LITTER"));
		assertTrue("Expected the detected files to be listed",
				prompt.contains("model.bin, sub/weights.bin"));
		assertTrue("Expected guidance to remove or relocate the files",
				prompt.contains(".bin"));
	}

	/** No litter warning is rendered when the violation is unset. */
	@Test(timeout = 30000)
	public void noInvalidFilesWarningWhenUnset() {
		String prompt = new InstructionPromptBuilder()
				.setPrompt("do the work")
				.build();

		assertFalse("No litter warning expected when violation is unset",
				prompt.contains("BINARY FILE LITTER"));
	}
}
