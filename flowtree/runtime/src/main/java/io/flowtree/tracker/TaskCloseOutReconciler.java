/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.tracker;

import io.flowtree.github.GitHubProxyHandler;
import io.flowtree.github.PullRequest;
import io.flowtree.workstream.Workstream;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Brings a claimed tracker task to an end when its branch does.
 *
 * <p>A planning round claims a ready task by linking it to the round's
 * workstream; the task then follows that workstream's branch through plan
 * review and implementation to a pull request. When that pull request merges
 * the task is finished, and when it is closed without merging the plan was
 * declined. Nothing in that path touches the tracker again, so without this
 * reconciler a claimed task stays open and linked forever — and every task
 * listing it in {@code blocked_by} waits forever with it.</p>
 *
 * <p>Each pass asks the tracker which workstreams still hold a claimed, open,
 * ready task, looks up each workstream's branch on GitHub, and reports any
 * outcome back:</p>
 * <ul>
 *   <li>a merged pull request on the branch closes the tasks;</li>
 *   <li>otherwise, when no pull request is open and one was closed without
 *       merging, the tasks are declined (and unlinked, so a person can return
 *       them to the queue);</li>
 *   <li>anything else — no pull request yet, one still open, a lookup that
 *       failed — leaves the tasks as they are until a later pass.</li>
 * </ul>
 *
 * <p>The tracker decides which tasks an outcome applies to and changes only
 * those still open and ready, so a task a person has moved in the meantime is
 * left alone and a repeated pass changes nothing. Standing and orchestrator
 * workstreams are skipped: their branch is long-lived, and one of its pull
 * requests merging says nothing about any one task linked to it.</p>
 */
public class TaskCloseOutReconciler implements ConsoleFeatures {

    /**
     * How often the reconciler looks for finished branches. A task closing a
     * few minutes after its pull request merges is soon enough for the next
     * planning round, and each pass costs one GitHub request per claimed task's
     * workstream.
     */
    public static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofMinutes(10);

    /** The tracker holding the claimed tasks. */
    private final TrackerService tracker;

    /** Resolves a workstream id to the controller's workstream, or {@code null} when unknown. */
    private final Function<String, Workstream> workstreams;

    /**
     * Lists the pull requests for {@code (owner/repo, branch)}; {@code null}
     * when GitHub could not be asked.
     */
    private final BiFunction<String, String, List<PullRequest>> pullRequests;

    /** The timer driving {@link #reconcileOnce}; {@code null} until started. */
    private ScheduledExecutorService executor;

    /**
     * Creates a reconciler.
     *
     * @param tracker      the tracker holding the claimed tasks
     * @param workstreams  resolves a workstream id, returning {@code null} for an unknown one
     * @param pullRequests lists a branch's pull requests, as
     *                     {@link GitHubProxyHandler#listPullRequestsForBranch} does
     */
    public TaskCloseOutReconciler(TrackerService tracker, Function<String, Workstream> workstreams,
                                  BiFunction<String, String, List<PullRequest>> pullRequests) {
        this.tracker = tracker;
        this.workstreams = workstreams;
        this.pullRequests = pullRequests;
    }

    /**
     * Runs one pass: applies every branch outcome that is now known.
     *
     * <p>Separated from the timer so the decisions are testable without
     * waiting on a schedule. A failure to reach the tracker ends the pass; a
     * failure for one workstream skips only that workstream.</p>
     *
     * @return the ids of the tasks changed, by the workstream they were linked to
     * @throws IOException when the tracker cannot be asked which tasks are awaiting close-out
     */
    public synchronized Map<String, List<String>> reconcileOnce() throws IOException {
        Map<String, List<String>> changed = new LinkedHashMap<>();
        for (String workstreamId : tracker.workstreamsAwaitingCloseOut()) {
            Workstream ws = workstreams.apply(workstreamId);
            if (ws == null || !"feature".equals(ws.getKind())) continue;
            String branch = ws.getDefaultBranch();
            if (branch == null || branch.equals(ws.getBaseBranch())) continue;

            List<PullRequest> prs = pullRequests.apply(
                    GitHubProxyHandler.extractOwnerRepo(ws.getRepoUrl()), branch);
            TrackerService.CloseOutOutcome outcome = TrackerService.CloseOutOutcome.of(prs);
            if (outcome == null) continue;

            try {
                List<String> ids = tracker.closeOut(workstreamId, outcome);
                if (ids.isEmpty()) continue;
                changed.put(workstreamId, ids);
                log((outcome == TrackerService.CloseOutOutcome.MERGED ? "Closed" : "Declined")
                        + " tracker tasks " + ids + " linked to workstream " + workstreamId
                        + ": the pull request for " + branch + " was "
                        + (outcome == TrackerService.CloseOutOutcome.MERGED
                                ? "merged" : "closed without merging"));
            } catch (IOException e) {
                warn("Could not close out tracker tasks for workstream " + workstreamId
                        + ": " + e.getMessage());
            }
        }
        return changed;
    }

    /**
     * Starts the periodic pass. Does nothing when already started.
     *
     * <p>The scheduled task swallows every failure a pass reports, because
     * {@link ScheduledExecutorService#scheduleAtFixedRate} stops repeating
     * after an uncaught exception: a tracker that is briefly down must not
     * end close-out for the rest of the controller's life.</p>
     */
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-close-out");
            t.setDaemon(true);
            return t;
        });
        long seconds = DEFAULT_SCAN_INTERVAL.getSeconds();
        executor.scheduleAtFixedRate(() -> {
            try {
                reconcileOnce();
            } catch (IOException | RuntimeException e) {
                warn("Task close-out pass failed: " + e.getMessage());
            }
        }, seconds, seconds, TimeUnit.SECONDS);
        log("Task close-out started: checking claimed tasks' branches every " + seconds + "s");
    }

    /** Stops the periodic pass. Safe to call when never started. */
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
    }
}
