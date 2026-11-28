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

package io.flowtree.controller;

import java.time.Duration;
import java.time.Instant;

/**
 * A request for the throughput retrospective over a half-open window
 * {@code [from, to)}, with the optional filter and the silence threshold the
 * time measures use.
 *
 * <p>The presets the operator asks for ({@code 1h}, {@code 3h}, {@code 6h},
 * {@code 24h}, {@code 7d}) resolve to {@code [now - d, now)} through
 * {@link #ofPreset(String, Instant, Duration)}. The {@code silentThreshold} is the same
 * cutoff {@link DailyAgentTime} uses: a running job stops accruing agent time
 * once it has been silent longer than this (see
 * {@link JobInterval#end(Instant, Duration)}).</p>
 *
 * @param from            inclusive window start
 * @param to              exclusive window end
 * @param workstreamId    restrict to one workstream, or {@code null} for all
 * @param silentThreshold the silence after which a running job stops accruing
 *                        time; non-positive means it never stops
 * @author Michael Murray
 * @see WindowStatsAggregator
 */
public record WindowQuery(Instant from, Instant to, String workstreamId,
                          Duration silentThreshold) {

    /**
     * Resolves a preset name to the window length it stands for.
     *
     * @param preset one of {@code 1h}, {@code 3h}, {@code 6h}, {@code 24h},
     *               {@code 7d}
     * @return the window length for the preset
     * @throws IllegalArgumentException when the preset is not recognised
     */
    public static Duration presetDuration(String preset) {
        if (preset == null) {
            throw new IllegalArgumentException("preset is required");
        }
        switch (preset) {
            case "1h": return Duration.ofHours(1);
            case "3h": return Duration.ofHours(3);
            case "6h": return Duration.ofHours(6);
            case "24h": return Duration.ofHours(24);
            case "7d": return Duration.ofDays(7);
            default:
                throw new IllegalArgumentException("Unknown preset: " + preset
                        + ". Use 1h, 3h, 6h, 24h or 7d.");
        }
    }

    /**
     * Builds a query for a preset window ending at {@code now}, with no
     * workstream filter.
     *
     * @param preset          the preset name (see {@link #presetDuration(String)})
     * @param now             the window end
     * @param silentThreshold the silence after which a running job stops
     *                        accruing time
     * @return the query
     */
    public static WindowQuery ofPreset(String preset, Instant now, Duration silentThreshold) {
        Duration d = presetDuration(preset);
        return new WindowQuery(now.minus(d), now, null, silentThreshold);
    }

    /**
     * Returns a copy of this query filtered to one workstream.
     *
     * @param workstream the workstream to filter by, or {@code null} for all
     * @return the filtered query
     */
    public WindowQuery withWorkstream(String workstream) {
        return new WindowQuery(from, to, workstream, silentThreshold);
    }
}
