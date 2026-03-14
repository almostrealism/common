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

import java.io.File;

/**
 * Utility class for resolving workspace paths and URLs used by
 * {@link GitManagedJob} and its subclasses.
 *
 * <p>All methods are static; this class holds no instance state.
 * It centralizes three concerns that were previously embedded in
 * {@code GitManagedJob}:</p>
 * <ul>
 *   <li>Workspace path resolution with a two-level priority scheme</li>
 *   <li>Extraction of a filesystem-safe repository name from a git URL</li>
 *   <li>Replacement of the {@code 0.0.0.0} placeholder in workstream URLs
 *       with the value of the {@code FLOWTREE_ROOT_HOST} environment variable</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class WorkspaceResolver {

    /** Default fallback directory for workspace checkouts. */
    public static final String FALLBACK_WORKSPACE_DIR = "/tmp/flowtree-workspaces";

    private WorkspaceResolver() {
        // Utility class -- not instantiable
    }

    /**
     * Resolves the workspace path for a repository checkout using a
     * two-level priority scheme.
     *
     * <p>The resolved path is always a repo-specific subdirectory.
     * The parent directory is chosen from:</p>
     * <ol>
     *   <li>{@code configuredPath} if it is non-null and non-empty</li>
     *   <li>{@code /workspace/project} if that directory exists on disk</li>
     *   <li>{@code /tmp/flowtree-workspaces} as a fallback</li>
     * </ol>
     *
     * <p>In all cases, the repository name (derived from {@code repoUrl}
     * via {@link #extractRepoName(String)}) is appended to form the final
     * path, e.g. {@code /workspace/project/owner-repo}.</p>
     *
     * @param configuredPath the explicitly configured workspace parent path,
     *                       or {@code null} / empty to use automatic resolution
     * @param repoUrl        the git remote URL used to derive the
     *                       subdirectory name
     * @return the resolved absolute path for the workspace
     */
    public static String resolve(String configuredPath, String repoUrl) {
        String repoName = extractRepoName(repoUrl);

        // 1. Use explicitly configured path as parent
        if (configuredPath != null && !configuredPath.isEmpty()) {
            return configuredPath + "/" + repoName;
        }

        // 2. Check if /workspace/project exists as parent
        File defaultDir = new File("/workspace/project");
        if (defaultDir.exists() && defaultDir.isDirectory()) {
            return "/workspace/project/" + repoName;
        }

        // 3. Fall back to /tmp with a repo-derived name
        return FALLBACK_WORKSPACE_DIR + "/" + repoName;
    }

    /**
     * Extracts a filesystem-safe repository name from a git URL.
     *
     * <p>Handles both SSH ({@code git@github.com:owner/repo.git}) and
     * HTTPS ({@code https://github.com/owner/repo.git}) formats.
     * The {@code .git} suffix is removed and path separators are replaced
     * with dashes, producing names like {@code owner-repo}.</p>
     *
     * <p>Returns {@code "unknown"} for null or empty input.</p>
     *
     * @param url the git remote URL
     * @return a filesystem-safe name derived from the repository URL
     */
    public static String extractRepoName(String url) {
        if (url == null || url.isEmpty()) {
            return "unknown";
        }

        String path = url;

        // SSH format: git@github.com:owner/repo.git
        if (path.contains(":") && path.contains("@")) {
            int colonIdx = path.lastIndexOf(':');
            path = path.substring(colonIdx + 1);
        } else {
            // HTTPS format: strip protocol and host
            int slashSlash = path.indexOf("//");
            if (slashSlash >= 0) {
                path = path.substring(slashSlash + 2);
                int firstSlash = path.indexOf('/');
                if (firstSlash >= 0) {
                    path = path.substring(firstSlash + 1);
                }
            }
        }

        // Remove .git suffix
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }

        // Replace slashes with dashes for a flat directory name
        return path.replace('/', '-');
    }

    /**
     * Resolves a workstream URL by replacing the {@code 0.0.0.0} placeholder
     * with the value of the {@code FLOWTREE_ROOT_HOST} environment variable.
     *
     * <p>When jobs run inside containers, the controller address is
     * typically configured as {@code 0.0.0.0} in the job descriptor.
     * This method substitutes the real host address so that HTTP
     * callbacks (status events, Slack messages) reach the controller.</p>
     *
     * <p>If {@code FLOWTREE_ROOT_HOST} is not set, or the URL does not
     * contain {@code 0.0.0.0}, the original URL is returned unchanged.</p>
     *
     * @param workstreamUrl the workstream URL, possibly containing
     *                      a {@code 0.0.0.0} placeholder
     * @return the resolved URL with the placeholder replaced, or the
     *         original URL if no replacement is needed
     */
    public static String resolveWorkstreamUrl(String workstreamUrl) {
        String url = workstreamUrl;

        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
        if (rootHost != null && !rootHost.isEmpty() && url.contains("0.0.0.0")) {
            url = url.replace("0.0.0.0", rootHost);
        }

        return url;
    }
}
