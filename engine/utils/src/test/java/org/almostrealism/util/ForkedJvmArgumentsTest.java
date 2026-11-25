/*
 * Copyright 2026 Michael Murray
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

import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Verifies that every JVM argument handed to the surefire fork actually reaches that fork, in
 * order. It is the reproduction and regression gate for the ar-test-runner defect where only the
 * first {@code jvm_args} entry reached the forked JVM and every later entry silently became a
 * separate Maven property that never influenced the test JVM.
 *
 * <p>The test reads the arguments the JVM was actually launched with from
 * {@link java.lang.management.RuntimeMXBean#getInputArguments()} and logs them so a run's output
 * records the effective fork arguments. When driven with the {@code ar.expectedForkedJvmArgs}
 * property it asserts that each expected token is present and that their relative order is
 * preserved; without that property it only logs, so an ordinary suite run (CI included, where no
 * such property is set) neither requires special arguments nor fails.</p>
 *
 * <p>The driver property is itself passed through {@code jvm_args}, so a tool that forwarded only
 * the first entry would deliver the driver (listing the tokens to expect) but drop the tokens
 * themselves, turning the dropped delivery into a concrete assertion failure here rather than a
 * silent wrong-heap run.</p>
 */
public class ForkedJvmArgumentsTest extends TestSuiteBase {

	/**
	 * The property naming the {@code ;}-separated tokens that must appear, in order, in the forked
	 * JVM's input arguments. A {@code ;} separator is used because surefire splits {@code argLine}
	 * on whitespace, so a token cannot itself contain a space.
	 */
	public static final String EXPECTED_ARGS_PROPERTY = "ar.expectedForkedJvmArgs";

	/**
	 * Logs the forked JVM's actual launch arguments and, when {@link #EXPECTED_ARGS_PROPERTY} is
	 * set, asserts that every token it names is present in those arguments and in the given order.
	 */
	@Test(timeout = 30000)
	public void everyForkedJvmArgumentReachesTheFork() {
		List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
		log("forkedJvmInputArguments=" + inputArguments);

		String expected = System.getProperty(EXPECTED_ARGS_PROPERTY, "").trim();
		if (expected.isEmpty()) {
			return;
		}

		int searchFrom = 0;
		for (String token : expected.split(";")) {
			if (token.isEmpty()) {
				continue;
			}

			int relativeIndex = inputArguments.subList(searchFrom, inputArguments.size()).indexOf(token);
			Assert.assertTrue(
					"forked JVM is missing argument '" + token + "' at or after position "
							+ searchFrom + "; actual input arguments: " + inputArguments,
					relativeIndex >= 0);
			int index = searchFrom + relativeIndex;
			searchFrom = index + 1;
		}
	}
}
