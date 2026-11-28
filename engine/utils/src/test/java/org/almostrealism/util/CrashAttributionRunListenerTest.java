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

import org.almostrealism.io.Console;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

import java.util.function.Consumer;

/**
 * Verifies that {@link CrashAttributionRunListener} emits a greppable breadcrumb for each
 * JUnit lifecycle event it handles, and that the breadcrumb carries the test's display name.
 *
 * <p>Each assertion captures the text the listener writes by registering a transient listener
 * on the {@link Console#root() root console} — the same sink the breadcrumb travels through to
 * reach a fork's standard output — so the test exercises the production routing path rather than
 * a mock. The captured text is asserted to contain both the {@code eventName=} marker and the
 * description's display name, which together are what a crash log needs to name the in-flight
 * test.</p>
 */
public class CrashAttributionRunListenerTest extends TestSuiteBase {

	/** The listener under test. */
	private CrashAttributionRunListener listener;

	/** Accumulates every chunk the root console emits while a capture is installed. */
	private StringBuilder captured;

	/** The capture listener registered on the root console; removed during teardown. */
	private Consumer<String> capture;

	/**
	 * Installs a fresh listener and a root-console capture before each test so the breadcrumbs
	 * emitted by the production {@code log} path can be asserted on directly.
	 */
	@Before
	public void installCapture() {
		listener = new CrashAttributionRunListener();
		captured = new StringBuilder();
		capture = s -> captured.append(s);
		Console.root().addListener(capture);
	}

	/**
	 * Removes the capture listener after each test so it does not leak across the shared root
	 * console into unrelated tests.
	 */
	@After
	public void removeCapture() {
		Console.root().removeListener(capture);
	}

	/** A representative description whose display name the breadcrumbs should carry. */
	private Description sampleDescription() {
		return Description.createTestDescription(CrashAttributionRunListenerTest.class, "sampleMethod");
	}

	/** The display name the sample description reports, used to assert attribution. */
	private String sampleDisplayName() {
		return sampleDescription().getDisplayName();
	}

	/**
	 * A started test is recorded with a {@code testStarted=} marker carrying the display name,
	 * which is the breadcrumb a crash with no matching finish points at.
	 */
	@Test(timeout = 30000)
	public void testStartedEmitsNamedBreadcrumb() {
		listener.testStarted(sampleDescription());

		String output = captured.toString();
		Assert.assertTrue("missing testStarted marker in: " + output,
				output.contains("testStarted=" + sampleDisplayName()));
	}

	/**
	 * A finished test is recorded with a {@code testFinished=} marker carrying the display name,
	 * which is what distinguishes an asynchronous fault that outlived its test from an in-flight one.
	 */
	@Test(timeout = 30000)
	public void testFinishedEmitsNamedBreadcrumb() {
		listener.testFinished(sampleDescription());

		String output = captured.toString();
		Assert.assertTrue("missing testFinished marker in: " + output,
				output.contains("testFinished=" + sampleDisplayName()));
	}

	/**
	 * A failure is recorded with a {@code testFailure=} marker carrying the failing test's display
	 * name, drawn from the failure's description rather than its throwable.
	 */
	@Test(timeout = 30000)
	public void testFailureEmitsNamedBreadcrumb() {
		listener.testFailure(new Failure(sampleDescription(), new IllegalStateException("boom")));

		String output = captured.toString();
		Assert.assertTrue("missing testFailure marker in: " + output,
				output.contains("testFailure=" + sampleDisplayName()));
	}

	/**
	 * An ignored test is recorded with a {@code testIgnored=} marker carrying the display name.
	 */
	@Test(timeout = 30000)
	public void testIgnoredEmitsNamedBreadcrumb() {
		listener.testIgnored(sampleDescription());

		String output = captured.toString();
		Assert.assertTrue("missing testIgnored marker in: " + output,
				output.contains("testIgnored=" + sampleDisplayName()));
	}

	/**
	 * Breadcrumbs are prefixed with the listener's own class name by the {@code ConsoleFeatures}
	 * mixin, so a reader can attribute the line to this diagnostic and grep it apart from ordinary
	 * test output.
	 */
	@Test(timeout = 30000)
	public void breadcrumbCarriesListenerClassName() {
		listener.testStarted(sampleDescription());

		String output = captured.toString();
		Assert.assertTrue("breadcrumb not attributed to the listener in: " + output,
				output.contains(CrashAttributionRunListener.class.getSimpleName() + ": testStarted="));
	}
}
