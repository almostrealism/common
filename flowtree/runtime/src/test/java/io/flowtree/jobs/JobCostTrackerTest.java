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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JobCostTracker}: per-runner and per-model cost
 * accumulation, snapshot independence from the live map, and the
 * {@link JobCostTracker#costForModel(String)} lookup used by
 * {@link RetrospectivePhase} to isolate the retrospective session's cost.
 */
public class JobCostTrackerTest extends TestSuiteBase {

    /** A new tracker reports zero cost for any model and empty snapshot maps. */
    @Test(timeout = 30000)
    public void freshTrackerHasZeroCostAndEmptySnapshots() {
        JobCostTracker tracker = new JobCostTracker();
        assertEquals(0.0, tracker.costForModel("anthropic/claude-sonnet"), 0.0);
        assertTrue(tracker.snapshotByRunner().isEmpty());
        assertTrue(tracker.snapshotByModel().isEmpty());
    }

    /** {@link JobCostTracker#record} accumulates cost into both runner and model maps. */
    @Test(timeout = 30000)
    public void recordAccumulatesCostIntoRunnerAndModelMaps() {
        JobCostTracker tracker = new JobCostTracker();
        tracker.record("claude", "anthropic/claude-sonnet", 0.50);
        tracker.record("claude", "anthropic/claude-sonnet", 0.25);
        tracker.record("opencode", "openrouter/qwen3-coder", 0.10);

        assertEquals(0.75, tracker.snapshotByRunner().get("claude"), 1.0e-9);
        assertEquals(0.10, tracker.snapshotByRunner().get("opencode"), 1.0e-9);
        assertEquals(0.75, tracker.snapshotByModel().get("anthropic/claude-sonnet"), 1.0e-9);
        assertEquals(0.10, tracker.snapshotByModel().get("openrouter/qwen3-coder"), 1.0e-9);
    }

    /** {@link JobCostTracker#costForModel} returns the running total or zero when absent. */
    @Test(timeout = 30000)
    public void costForModelReturnsRunningTotalOrZero() {
        JobCostTracker tracker = new JobCostTracker();
        tracker.record("claude", "anthropic/claude-sonnet", 0.30);
        tracker.record("claude", "anthropic/claude-sonnet", 0.20);
        assertEquals(0.50, tracker.costForModel("anthropic/claude-sonnet"), 1.0e-9);
        assertEquals(0.0, tracker.costForModel("missing/model"), 0.0);
    }

    /** Snapshots are independent copies — mutating the tracker after snapshot does not change the snapshot. */
    @Test(timeout = 30000)
    public void snapshotIsIndependentOfLiveMap() {
        JobCostTracker tracker = new JobCostTracker();
        tracker.record("claude", "model-a", 1.00);
        Map<String, Double> snapshotByRunner = tracker.snapshotByRunner();
        Map<String, Double> snapshotByModel = tracker.snapshotByModel();

        tracker.record("claude", "model-a", 0.50);

        assertEquals(1.00, snapshotByRunner.get("claude"), 1.0e-9);
        assertEquals(1.00, snapshotByModel.get("model-a"), 1.0e-9);
        assertEquals(1.50, tracker.snapshotByRunner().get("claude"), 1.0e-9);
    }

    /** Snapshots are unmodifiable so callers cannot accidentally mutate the underlying state. */
    @Test(timeout = 30000, expected = UnsupportedOperationException.class)
    public void snapshotByRunnerIsUnmodifiable() {
        JobCostTracker tracker = new JobCostTracker();
        tracker.record("claude", "model-a", 1.00);
        tracker.snapshotByRunner().put("anything", 0.0);
    }

    /** {@link JobCostTracker#snapshotByModel} is unmodifiable so callers cannot accidentally mutate the underlying state. */
    @Test(timeout = 30000, expected = UnsupportedOperationException.class)
    public void snapshotByModelIsUnmodifiable() {
        JobCostTracker tracker = new JobCostTracker();
        tracker.record("claude", "model-a", 1.00);
        tracker.snapshotByModel().put("anything", 0.0);
    }

    /** The live map returned by {@link JobCostTracker#liveByRunner} is not the same reference as a snapshot. */
    @Test(timeout = 30000)
    public void liveMapIsDistinctFromSnapshot() {
        JobCostTracker tracker = new JobCostTracker();
        tracker.record("claude", "model-a", 0.10);
        assertNotSame("Snapshot must be a separate copy",
                tracker.liveByRunner(), tracker.snapshotByRunner());
        assertNotSame("Snapshot must be a separate copy",
                tracker.liveByModel(), tracker.snapshotByModel());
    }
}
