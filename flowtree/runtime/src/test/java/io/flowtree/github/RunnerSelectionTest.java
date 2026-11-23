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

package io.flowtree.github;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link RunnerSelection#choose}: the preference order, idle-versus-busy,
 * offline runners, a missing label, and the permission-failure path.
 */
public class RunnerSelectionTest extends TestSuiteBase {

    /** The default fleet order the production monitor uses. */
    private static final List<RunnerFleet> FLEETS = RunnerMonitor.DEFAULT_FLEETS;

    /** A runner carrying every listed label, online and idle. */
    private static GitHubRunner idle(String name, String... labels) {
        return new GitHubRunner(name, "online", false, List.of(labels));
    }

    /** The CUDA fleet wins when it has an idle, labelled runner, even though it is first of several. */
    @Test(timeout = 10000)
    public void prefersTheFirstFleetWithAnIdleRunner() {
        RunnerListing listing = RunnerListing.of(List.of(
                idle("cuda-1", "self-hosted", "linux", "ar-ci-cuda", "ar-build"),
                idle("cpu-1", "self-hosted", "linux", "ar-ci", "ar-build")));

        RunnerSelection selection = RunnerSelection.choose("ar-build", FLEETS, listing);

        assertFalse("a self-hosted fleet was chosen", selection.hosted());
        assertEquals("ar-ci-cuda", selection.fleet());
        assertEquals(List.of("self-hosted", "linux", "ar-ci-cuda", "ar-build"), selection.runsOn());
        assertTrue(selection.listed());
    }

    /** When the preferred fleet's only runner is busy, the next fleet with an idle runner is chosen. */
    @Test(timeout = 10000)
    public void skipsABusyFleetForTheNextIdleOne() {
        RunnerListing listing = RunnerListing.of(List.of(
                new GitHubRunner("cuda-1", "online", true,
                        List.of("self-hosted", "linux", "ar-ci-cuda", "ar-build")),
                idle("cl-1", "self-hosted", "linux", "ar-ci-cl", "ar-build")));

        RunnerSelection selection = RunnerSelection.choose("ar-build", FLEETS, listing);

        assertEquals("ar-ci-cl", selection.fleet());
        assertEquals(List.of("self-hosted", "linux", "ar-ci-cl", "ar-build"), selection.runsOn());
    }

    /** An offline runner never wins, even when it carries the right labels. */
    @Test(timeout = 10000)
    public void ignoresAnOfflineRunner() {
        RunnerListing listing = RunnerListing.of(List.of(
                new GitHubRunner("cuda-1", "offline", false,
                        List.of("self-hosted", "linux", "ar-ci-cuda", "ar-build")),
                idle("macos-1", "self-hosted", "macos", "ar-ci", "ar-build")));

        RunnerSelection selection = RunnerSelection.choose("ar-build", FLEETS, listing);

        Assert.assertEquals("the offline CUDA runner is skipped; macOS is the only idle one",
                "macos", selection.fleet());
        assertEquals(List.of("self-hosted", "macos", "ar-build"), selection.runsOn());
    }

    /** A runner idle in a preferred fleet but lacking the required label does not match. */
    @Test(timeout = 10000)
    public void requiresTheRequiredLabel() {
        RunnerListing listing = RunnerListing.of(List.of(
                idle("cuda-1", "self-hosted", "linux", "ar-ci-cuda"),
                idle("cpu-1", "self-hosted", "linux", "ar-ci", "ar-build")));

        RunnerSelection selection = RunnerSelection.choose("ar-build", FLEETS, listing);

        Assert.assertEquals("the CUDA runner lacks ar-build", "ar-ci", selection.fleet());
    }

    /** With no idle, labelled runner anywhere, the job falls back to the hosted runner. */
    @Test(timeout = 10000)
    public void fallsBackToHostedWhenNothingMatches() {
        RunnerListing listing = RunnerListing.of(List.of(
                new GitHubRunner("cuda-1", "online", true,
                        List.of("self-hosted", "linux", "ar-ci-cuda", "ar-build"))));

        RunnerSelection selection = RunnerSelection.choose("ar-build", FLEETS, listing);

        assertTrue(selection.hosted());
        assertNull(selection.fleet());
        assertEquals(List.of("ubuntu-latest"), selection.runsOn());
        assertTrue("the list was read, so this is a busy-fleet fallback, not a permission one",
                selection.listed());
    }

    /**
     * A permission failure (or an unreachable GitHub) falls back to hosted but is
     * marked as not listed and carries the reason, so it is never mistaken for a
     * quiet "every fleet is busy".
     */
    @Test(timeout = 10000)
    public void permissionFailureIsReportedNotSilentlyHosted() {
        RunnerListing listing = RunnerListing.failed(
                "GitHub returned HTTP 403 listing runners for almostrealism");

        RunnerSelection selection = RunnerSelection.choose("ar-build", FLEETS, listing);

        assertTrue(selection.hosted());
        assertEquals(List.of("ubuntu-latest"), selection.runsOn());
        assertFalse("a permission failure is not a successful listing", selection.listed());
        assertTrue(selection.reason().contains("403"));
    }
}
