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

package io.flowtree.workstream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration entry for a GitHub organization token.
 *
 * <p>Maps an organization name to a GitHub personal access token.
 * When a workstream specifies a {@code githubOrg}, the controller
 * proxy selects the matching token for GitHub API calls.</p>
 *
 * @author Michael Murray
 * @see WorkstreamConfig#mergedGithubOrgTokens()
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubOrgEntry {

    /** The GitHub personal access token for authenticating as this organization. */
    private String token;

    /** Returns the GitHub personal access token for this organization. */
    public String getToken() { return token; }
    /** Sets the GitHub personal access token for this organization. */
    public void setToken(String token) { this.token = token; }
}
