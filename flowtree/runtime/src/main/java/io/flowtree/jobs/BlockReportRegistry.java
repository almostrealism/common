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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The controller's record of the {@link BlockReport}s agents have posted through
 * the {@code job_report_blocked} tool, keyed by job id.
 *
 * <p>An agent runs on a worker but reaches the controller over HTTP; a block it
 * declares lands here. The same worker reads it back (via
 * {@link BlockReport#fetch(String, String, boolean)}) before it decides whether
 * to run enforcement, which is why the claim is held centrally rather than on
 * the live job object. A stored report carries the agent's category and detail
 * only; its {@linkplain BlockReport#isVerified() verified} flag is meaningless
 * here (the controller cannot inspect the session) and the worker recomputes it
 * when it fetches the report.</p>
 *
 * <p>A report is read exactly once, by the worker that will act on it, so
 * {@link #consume(String)} removes it as it returns it. The controller is a
 * long-lived service; holding every report for its lifetime would leak one
 * entry per blocked job, and nothing reads a report a second time.</p>
 *
 * @author Michael Murray
 */
public final class BlockReportRegistry {

    /** Reported blocks keyed by job id. */
    private final Map<String, BlockReport> byJob = new ConcurrentHashMap<>();

    /**
     * Records a block the agent reported for a job, replacing any earlier report
     * for the same job (a later call is the agent's final word).
     *
     * @param jobId  the job the block was reported for
     * @param report the reported block
     */
    public void record(String jobId, BlockReport report) {
        if (jobId == null || jobId.isEmpty() || report == null) {
            return;
        }
        byJob.put(jobId, report);
    }

    /**
     * Returns the block reported for a job, if any, removing it as it does so:
     * a report is delivered once, to the worker that reads it back before
     * deciding whether to run enforcement.
     *
     * @param jobId the job to look up
     * @return the reported block, or empty when the job was not reported blocked
     */
    public Optional<BlockReport> consume(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byJob.remove(jobId));
    }
}
