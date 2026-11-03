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

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One job's private space for <em>installed</em> Maven artifacts inside the
 * node's shared local repository.
 *
 * <p>Every job on a node shares {@code ~/.m2/repository}. Without isolation, a
 * {@code mvn install} run by one branch's job is what the next job's build
 * resolves, so a test can pass or fail against jars built from a different
 * branch's source — and a dependent repo (such as Common checked out beside a
 * downstream project) is masked by whatever copy of it was installed last.</p>
 *
 * <p>Maven 3.9's split local repository separates the two kinds of content a
 * local repository holds: artifacts downloaded from remote repositories go to
 * {@code <repository>/cached/}, shared by every job, and artifacts installed by
 * a build go to {@code <repository>/<localPrefix>/}. This class gives each job
 * the prefix {@value #ROOT}{@code /<task id>}, so what a job installs — its
 * own modules, and any dependent repo it builds — is visible to that job's
 * builds and to no other job's, while downloads are fetched once per node.</p>
 *
 * <p>The setting reaches every Maven invocation of the job through the
 * {@value #MAVEN_ARGS} environment variable, which Maven 3.9 reads before its
 * command-line arguments: {@link #applyTo(Map)} sets it on the agent process,
 * whose children — the agent's shell, and the {@code ar-test-runner} and
 * {@code ar-build-validator} MCP servers — inherit it.</p>
 *
 * <p>The space is a {@link ManagedDirectory} owned by its job: it is deleted
 * when the job ends ({@link #release()}), and spaces left behind by a worker
 * that died mid-job are deleted by the next job on the node once they are
 * older than {@link #maxAge()} ({@link #sweepExpired}).</p>
 *
 * @author Michael Murray
 * @see EnvironmentManagedJob
 * @see MavenInstallation
 */
public class MavenInstallSpace extends ManagedDirectory implements ConsoleFeatures {

    /** Directory under the local repository that holds every job's space. */
    public static final String ROOT = "flowtree-jobs";

    /** Environment variable Maven 3.9 prepends to its command-line arguments. */
    public static final String MAVEN_ARGS = "MAVEN_ARGS";

    /** Resolver property enabling the split local repository. */
    public static final String SPLIT_PROPERTY = "aether.enhancedLocalRepository.split";

    /** Resolver property naming the directory installed artifacts go to. */
    public static final String LOCAL_PREFIX_PROPERTY = "aether.enhancedLocalRepository.localPrefix";

    /**
     * System property overriding the node's local repository location.
     * Defaults to {@code ~/.m2/repository}; set it when the node's Maven
     * {@code settings.xml} relocates the local repository, so cleanup
     * looks where Maven writes.
     */
    public static final String LOCAL_REPOSITORY_PROPERTY = "flowtree.mavenLocalRepository";

    /** System property overriding {@link #DEFAULT_MAX_AGE_HOURS}. */
    public static final String MAX_AGE_PROPERTY = "flowtree.mavenInstallSpaceMaxAgeHours";

    /**
     * Age, in hours, past which another job's space is treated as abandoned.
     * Far longer than any job runs, so a live job's space is never swept.
     */
    public static final long DEFAULT_MAX_AGE_HOURS = 48;

    /** Characters a task id may not keep when it becomes a directory name; dots go too, so no name is {@code ..}. */
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    /** The node's local Maven repository. */
    private final Path localRepository;

    /**
     * Creates the space for {@code taskId} inside {@code localRepository}.
     *
     * @param localRepository the node's local Maven repository
     * @param taskId          the job's task id; must be non-empty
     * @throws IllegalArgumentException when {@code taskId} is null or empty
     */
    public MavenInstallSpace(Path localRepository, String taskId) {
        super(localRepository.resolve(ROOT).resolve(directoryName(taskId)));
        this.localRepository = localRepository;
    }

    /**
     * Creates the space for {@code taskId} inside this node's local repository
     * ({@value #LOCAL_REPOSITORY_PROPERTY}, or {@code ~/.m2/repository}).
     *
     * @param taskId the job's task id
     * @return the job's install space
     */
    public static MavenInstallSpace forJob(String taskId) {
        String override = System.getProperty(LOCAL_REPOSITORY_PROPERTY);
        Path repository = override != null && !override.isEmpty()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
        return new MavenInstallSpace(repository, taskId);
    }

    /**
     * Returns the local-repository-relative prefix installed artifacts go to.
     *
     * @return {@value #ROOT}{@code /<task id>}
     */
    public String localPrefix() {
        return ROOT + "/" + getPath().getFileName();
    }

    /**
     * Returns the Maven arguments that route installs into this space.
     *
     * @return the {@code -D} arguments enabling the split repository with this
     *         job's prefix
     */
    public String mavenArgs() {
        return "-D" + SPLIT_PROPERTY + "=true -D" + LOCAL_PREFIX_PROPERTY + "=" + localPrefix();
    }

    /**
     * Sets {@value #MAVEN_ARGS} in {@code env} so every Maven run by a process
     * launched with it installs into this space. Arguments the environment
     * already carries — in {@code env}, or else inherited from this JVM — are
     * kept ahead of this space's, so a workstream's own {@value #MAVEN_ARGS}
     * still applies.
     *
     * @param env the environment a process will be launched with
     */
    public void applyTo(Map<String, String> env) {
        String existing = env.containsKey(MAVEN_ARGS) ? env.get(MAVEN_ARGS) : System.getenv(MAVEN_ARGS);
        env.put(MAVEN_ARGS, existing == null || existing.isBlank()
                ? mavenArgs() : existing.trim() + " " + mavenArgs());
    }

    /**
     * Prepares the space for a job about to run: deletes spaces of other jobs
     * that are older than {@link #maxAge()}, and warns when the node's Maven
     * is too old to honour the split repository, since isolation would then
     * silently not happen.
     */
    public void prepare() {
        int swept = sweepExpired(Instant.now(), maxAge());
        if (swept > 0) {
            log("Deleted " + swept + " expired Maven install space(s) under "
                    + localRepository.resolve(ROOT));
        }
        if (!MavenInstallation.node().supportsSplitRepository()) {
            warn("The node's mvn is older than 3.9 or could not be run; it ignores "
                    + MAVEN_ARGS + " and the split local repository, so this job's installed"
                    + " artifacts are NOT isolated from other jobs on this node");
        }
        log("Maven installs for this job go to " + getPath());
    }

    /**
     * Deletes this job's space, warning rather than failing the job when it
     * cannot. Called when the job ends.
     */
    public void release() {
        try {
            delete();
        } catch (IOException e) {
            warn("Could not delete Maven install space " + getPath() + ": " + e.getMessage());
        }
    }

    /**
     * Deletes every other job's space last modified before {@code now - maxAge}.
     * This job's own space is never swept.
     *
     * @param now    the current time
     * @param maxAge the age past which a space is abandoned
     * @return the number of spaces deleted
     */
    int sweepExpired(Instant now, Duration maxAge) {
        Path root = localRepository.resolve(ROOT);
        if (!Files.isDirectory(root)) return 0;
        FileTime cutoff = FileTime.from(now.minus(maxAge));
        int swept = 0;
        try (DirectoryStream<Path> spaces = Files.newDirectoryStream(root)) {
            for (Path space : spaces) {
                if (space.getFileName().equals(getPath().getFileName()) || !Files.isDirectory(space)) continue;
                if (Files.getLastModifiedTime(space).compareTo(cutoff) < 0) {
                    new ManagedDirectory(space).delete();
                    swept++;
                }
            }
        } catch (IOException e) {
            warn("Could not sweep expired Maven install spaces under " + root + ": " + e.getMessage());
        }
        return swept;
    }

    /**
     * Returns the age past which another job's space is abandoned:
     * {@value #MAX_AGE_PROPERTY} hours, or {@value #DEFAULT_MAX_AGE_HOURS}.
     *
     * @return the maximum age of a space
     */
    static Duration maxAge() {
        String hours = System.getProperty(MAX_AGE_PROPERTY);
        if (hours != null && !hours.isBlank()) {
            try {
                return Duration.ofHours(Long.parseLong(hours.trim()));
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return Duration.ofHours(DEFAULT_MAX_AGE_HOURS);
    }

    /**
     * Returns the directory name of the space for {@code taskId}: the task id
     * with every character that could escape {@link #ROOT} or inject a further
     * Maven argument replaced.
     *
     * @param taskId the job's task id
     * @return the space's directory name
     * @throws IllegalArgumentException when {@code taskId} is null or empty
     */
    private static String directoryName(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("A Maven install space needs a task id");
        }
        return UNSAFE_NAME_CHARS.matcher(taskId).replaceAll("_");
    }
}
