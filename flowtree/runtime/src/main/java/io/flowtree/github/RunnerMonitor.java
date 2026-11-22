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

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors an organization's self-hosted GitHub Actions runners and answers
 * "which runner should this job use?".
 *
 * <p>It reads the runner list through the controller's existing
 * {@link GitHubProxyHandler} — the same HTTP path and the same per-organization
 * token every other GitHub operation uses — and caches it for a few seconds so a
 * burst of pipeline runs does not hit GitHub's rate limit. {@link #select} then
 * walks a preference order of {@link RunnerFleet fleets} and returns the
 * {@code runs-on} list for the first fleet that has an online, idle runner
 * carrying the job's required label, falling back to the GitHub-hosted runner
 * when none does (see {@link RunnerSelection}).</p>
 *
 * <p>The default fleet preference is the CUDA fleet, then the OpenCL/ROCm fleet,
 * then the Linux CPU fleet, then macOS last (macOS runners are the scarcest).
 * The owner can reorder it without a commit by setting
 * {@code AR_CI_RUNNER_FLEET_PREFERENCE} (a comma-separated list of fleet labels)
 * on the controller; {@code AR_CI_RUNNER_ORG} changes the organization whose
 * runners are monitored. An individual request may also pass its own preference
 * order, so another job can reuse this with a different fleet policy.</p>
 *
 * @author  Michael Murray
 */
public class RunnerMonitor implements ConsoleFeatures {

    /** The organization monitored when a request names none. */
    public static final String DEFAULT_ORG =
            SystemUtils.getProperty("AR_CI_RUNNER_ORG", "almostrealism");

    /** How long a runner listing is reused before it is fetched again, in milliseconds. */
    public static final long DEFAULT_CACHE_MILLIS = 5000L;

    /**
     * The known CI fleets, each a capability label and the {@code runs-on} list
     * that targets it. The required label (e.g. {@code ar-build}) is appended by
     * {@link RunnerFleet#runsOnWith(String)} when a fleet is chosen.
     */
    public static final List<RunnerFleet> DEFAULT_FLEETS = List.of(
            new RunnerFleet("ar-ci-cuda", List.of("self-hosted", "linux", "ar-ci-cuda")),
            new RunnerFleet("ar-ci-cl", List.of("self-hosted", "linux", "ar-ci-cl")),
            new RunnerFleet("ar-ci", List.of("self-hosted", "linux", "ar-ci")),
            new RunnerFleet("macos", List.of("self-hosted", "macos")));

    /** The default fleet preference order, by capability label. */
    public static final List<String> DEFAULT_PREFERENCE =
            List.of("ar-ci-cuda", "ar-ci-cl", "ar-ci", "macos");

    /** A cached runner listing with the time it was fetched. */
    private record Cached(RunnerListing listing, long fetchedAt) { }

    /** The GitHub operations, holding the per-organization tokens. */
    private final GitHubProxyHandler github;

    /** The cache duration in milliseconds. */
    private final long cacheMillis;

    /** The known fleets, by label. */
    private final List<RunnerFleet> fleets;

    /** The default preference order, by fleet label. */
    private final List<String> defaultPreference;

    /** The most recent listing per organization. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Creates a monitor over {@code github} with the default cache duration,
     * fleets and preference order (the last resolved from
     * {@code AR_CI_RUNNER_FLEET_PREFERENCE} when set).
     *
     * @param github the GitHub operations
     */
    public RunnerMonitor(GitHubProxyHandler github) {
        this(github, DEFAULT_CACHE_MILLIS, DEFAULT_FLEETS, resolveDefaultPreference());
    }

    /**
     * Creates a monitor with explicit configuration, for tests.
     *
     * @param github            the GitHub operations
     * @param cacheMillis       the cache duration in milliseconds
     * @param fleets            the known fleets
     * @param defaultPreference the default preference order, by fleet label
     */
    public RunnerMonitor(GitHubProxyHandler github, long cacheMillis,
                         List<RunnerFleet> fleets, List<String> defaultPreference) {
        this.github = github;
        this.cacheMillis = cacheMillis;
        this.fleets = List.copyOf(fleets);
        this.defaultPreference = List.copyOf(defaultPreference);
    }

    /**
     * Resolves the default preference order from
     * {@code AR_CI_RUNNER_FLEET_PREFERENCE}, falling back to
     * {@link #DEFAULT_PREFERENCE} when it is unset or blank.
     *
     * @return the preference order, by fleet label
     */
    private static List<String> resolveDefaultPreference() {
        String configured = SystemUtils.getProperty("AR_CI_RUNNER_FLEET_PREFERENCE", null);
        List<String> parsed = splitLabels(configured);
        return parsed.isEmpty() ? DEFAULT_PREFERENCE : parsed;
    }

    /**
     * Splits a comma-separated label list, dropping blanks.
     *
     * @param csv the comma-separated labels, or {@code null}
     * @return the labels, never {@code null}
     */
    public static List<String> splitLabels(String csv) {
        List<String> labels = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String label = part.trim();
                if (!label.isEmpty()) labels.add(label);
            }
        }
        return labels;
    }

    /**
     * Returns the organization's runners, from the cache when a recent listing
     * is held and from GitHub otherwise.
     *
     * @param org the organization login, or {@code null}/blank for {@link #DEFAULT_ORG}
     * @return the listing
     */
    public RunnerListing runners(String org) {
        String organization = org == null || org.isBlank() ? DEFAULT_ORG : org;
        long now = System.currentTimeMillis();
        Cached held = cache.get(organization);
        if (held != null && now - held.fetchedAt() < cacheMillis) {
            return held.listing();
        }
        RunnerListing listing = github.listOrganizationRunners(organization);
        if (!listing.ok()) {
            warn("Could not list runners for " + organization + ": " + listing.error());
        }
        cache.put(organization, new Cached(listing, now));
        return listing;
    }

    /**
     * Chooses the runner a job requiring {@code requiredLabel} should use.
     *
     * @param org            the organization, or {@code null}/blank for {@link #DEFAULT_ORG}
     * @param requiredLabel  the label every candidate runner must carry (e.g. {@code ar-build})
     * @param preferenceOverride the preference order to use, or {@code null}/empty for the
     *                       controller default
     * @return the selection
     */
    public RunnerSelection select(String org, String requiredLabel, List<String> preferenceOverride) {
        List<String> order = preferenceOverride == null || preferenceOverride.isEmpty()
                ? defaultPreference : preferenceOverride;
        List<RunnerFleet> preference = new ArrayList<>();
        for (String label : order) {
            RunnerFleet fleet = fleetFor(label);
            if (fleet != null) {
                preference.add(fleet);
            } else {
                warn("Ignoring unknown fleet label in preference order: " + label);
            }
        }
        return RunnerSelection.choose(requiredLabel, preference, runners(org));
    }

    /**
     * Returns the known fleet with the given label, or {@code null}.
     *
     * @param label the fleet's capability label
     * @return the fleet, or {@code null} when none is known by that label
     */
    private RunnerFleet fleetFor(String label) {
        for (RunnerFleet fleet : fleets) {
            if (fleet.label().equalsIgnoreCase(label)) return fleet;
        }
        return null;
    }
}
