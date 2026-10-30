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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A single commit a job pushed to a dependent repository, carried from the job
 * to the controller so the controller can open (or reuse) a companion pull
 * request for it.
 *
 * <p>A job records one of these for every dependent repo it actually pushed to
 * (see {@link GitCommitHandler#handleDependentRepos(GitRepositorySetup)}), and
 * it travels on the completion event as the {@code dependentRepoPushes} array.
 * The type owns its own JSON form so the job that writes the array and the
 * controller that reads it share one serialisation — {@link #toJson(ArrayNode)}
 * appends this push to an array node, and {@link #listFromJson(String)} parses
 * the whole array back out of a completion-event body.</p>
 *
 * @param repoUrl    the dependent repository's clone URL
 * @param branch     the branch the push targeted (the job's target branch)
 * @param commitHash the full SHA of the pushed HEAD commit
 */
public record DependentRepoPush(String repoUrl, String branch, String commitHash) {

    /** Shared Jackson mapper for {@link #listFromJson(String)}. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The field under which the pushes travel on a completion-event body. */
    static final String JSON_FIELD = "dependentRepoPushes";

    /**
     * Appends this push to {@code array} as a JSON object with
     * {@code repoUrl}, {@code branch}, and {@code commitHash} members.
     *
     * @param array the array node to append to
     */
    public void toJson(ArrayNode array) {
        ObjectNode node = array.addObject();
        node.put("repoUrl", repoUrl);
        node.put("branch", branch);
        node.put("commitHash", commitHash);
    }

    /**
     * Renders this push for an unreviewed-commits warning as
     * {@code repoUrl branch shortHash}, where the hash is shortened to its
     * first seven characters.
     *
     * @return a human-readable one-line description of this push
     */
    public String describe() {
        String shortHash = commitHash != null && commitHash.length() > 7
                ? commitHash.substring(0, 7) : commitHash;
        return repoUrl + " " + branch + " " + shortHash;
    }

    /**
     * Renders a list of pushes as a comma-joined, human-readable list for an
     * unreviewed-commits warning.
     *
     * @param pushes the pushes to describe
     * @return the comma-joined descriptions of each push
     */
    public static String describe(List<DependentRepoPush> pushes) {
        List<String> parts = new ArrayList<>();
        for (DependentRepoPush push : pushes) {
            parts.add(push.describe());
        }
        return String.join(", ", parts);
    }

    /**
     * Parses the {@code dependentRepoPushes} array out of a completion-event
     * JSON body. A body with no such array (or a malformed one) yields an empty
     * list rather than an error, because a job with no dependent-repo pushes is
     * the common case and must not fail the controller's event handling.
     *
     * @param body the completion-event JSON body, or {@code null}
     * @return the pushes the body carried, never {@code null}
     */
    public static List<DependentRepoPush> listFromJson(String body) {
        List<DependentRepoPush> pushes = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return pushes;
        }
        try {
            JsonNode array = MAPPER.readTree(body).path(JSON_FIELD);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    pushes.add(new DependentRepoPush(
                            node.path("repoUrl").asText(null),
                            node.path("branch").asText(null),
                            node.path("commitHash").asText(null)));
                }
            }
        } catch (Exception e) {
            // A body the controller cannot parse carries no usable pushes; the
            // rest of the completion event is handled from its own fields.
        }
        return pushes;
    }
}
