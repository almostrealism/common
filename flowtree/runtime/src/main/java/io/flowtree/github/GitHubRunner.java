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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A single self-hosted GitHub Actions runner, as reported by
 * {@code GET /orgs/{org}/actions/runners}.
 *
 * <p>This is the subset of a runner's record that runner selection needs: its
 * {@code name} (for diagnostics), its {@code status} ({@code "online"} or
 * {@code "offline"}), whether it is currently {@code busy}, and the {@code labels}
 * it advertises. A runner is a candidate for a job only while it is both online
 * and not busy — {@link #idle()} — and only when it carries every label the job
 * requires.</p>
 *
 * @param name   the runner's name
 * @param status the runner's connection status, {@code "online"} or {@code "offline"}
 * @param busy   whether the runner is currently running a job
 * @param labels the labels the runner advertises
 *
 * @author  Michael Murray
 */
public record GitHubRunner(String name, String status, boolean busy, List<String> labels) {

    /**
     * Whether the runner is connected to GitHub and able to accept work.
     *
     * @return {@code true} when {@link #status} is {@code "online"}
     */
    public boolean online() {
        return "online".equalsIgnoreCase(status);
    }

    /**
     * Whether the runner could take a job right now: online and not already
     * running one.
     *
     * @return {@code true} when the runner is online and not busy
     */
    public boolean idle() {
        return online() && !busy;
    }

    /**
     * Whether the runner advertises the given label, compared without regard to
     * case (GitHub treats runner labels case-insensitively).
     *
     * @param label the label to look for
     * @return {@code true} when the runner carries {@code label}
     */
    public boolean hasLabel(String label) {
        if (label == null) return false;
        for (String advertised : labels) {
            if (label.equalsIgnoreCase(advertised)) return true;
        }
        return false;
    }

    /**
     * Whether the runner advertises every one of the given labels.
     *
     * <p>A runner's label set from the API includes its read-only labels
     * ({@code self-hosted}, the OS, the architecture) alongside its custom ones,
     * so this is what decides whether a {@code runs-on} list would actually land
     * on the runner: a macOS runner carrying {@code ar-ci} still fails a
     * {@code [self-hosted, linux, ar-ci]} check because it has no {@code linux}
     * label.</p>
     *
     * @param required the labels the runner must all carry
     * @return {@code true} when the runner carries every one of {@code required}
     */
    public boolean hasLabels(Iterable<String> required) {
        for (String label : required) {
            if (!hasLabel(label)) return false;
        }
        return true;
    }

    /**
     * Parses a runner from one element of the {@code runners} array of the
     * {@code GET /orgs/{org}/actions/runners} response.
     *
     * @param node the runner object
     * @return the parsed runner
     */
    public static GitHubRunner from(JsonNode node) {
        String name = node.path("name").asText("");
        String status = node.path("status").asText("");
        boolean busy = node.path("busy").asBoolean(false);
        List<String> labels = new ArrayList<>();
        JsonNode labelArray = node.path("labels");
        if (labelArray.isArray()) {
            for (JsonNode label : labelArray) {
                String name2 = label.path("name").asText("");
                if (!name2.isEmpty()) labels.add(name2);
            }
        }
        return new GitHubRunner(name, status, busy, List.copyOf(labels));
    }
}
