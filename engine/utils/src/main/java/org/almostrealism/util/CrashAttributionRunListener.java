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

import org.almostrealism.io.ConsoleFeatures;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * A JUnit {@link RunListener} that emits a flushed, greppable breadcrumb naming each test as it
 * starts and finishes, so that a forked test JVM which dies without saying goodbye still leaves
 * the running test's identity in the console log.
 *
 * <p>Surefire's own "Crashed tests" summary is populated from the tests the fork reported as
 * running over its control channel. When a native fault, {@code OutOfMemoryError} or process exit
 * happens in a <em>detached worker thread</em> after the test thread has already reported the
 * method finished — the pattern behind the intermittent Utils CPU-lane fork deaths — that summary
 * is empty, and the console log does not say which test was in flight. Each breadcrumb this
 * listener writes is flushed to the fork's standard output as it is produced (through
 * {@link org.almostrealism.io.Console}, whose root writes to {@link System#out}, which auto-flushes
 * per line), so the control channel's loss on a hard crash does not take the attribution with it:
 * the last {@code testStarted=} line without a matching {@code testFinished=} names the method that
 * was running, and a crash after a {@code testFinished=} with no following {@code testStarted=}
 * identifies it as an asynchronous fault that outlived its test.</p>
 *
 * <p>It is registered as a Surefire {@code listener} in the {@code engine/utils} module's
 * {@code pom.xml}; it is pure diagnostics and changes nothing about how tests run.</p>
 *
 * @author  Michael Murray
 */
@RunListener.ThreadSafe
public class CrashAttributionRunListener extends RunListener implements ConsoleFeatures {
	@Override
	public void testStarted(Description description) {
		log("testStarted=" + description.getDisplayName());
	}

	@Override
	public void testFinished(Description description) {
		log("testFinished=" + description.getDisplayName());
	}

	@Override
	public void testFailure(Failure failure) {
		log("testFailure=" + failure.getDescription().getDisplayName());
	}

	@Override
	public void testIgnored(Description description) {
		log("testIgnored=" + description.getDisplayName());
	}
}
