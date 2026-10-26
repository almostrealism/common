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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link DailyAgentTime}: how job spans are split into local days,
 * how running jobs are kept apart from finished ones, and that a silent
 * running job stops accruing time.
 */
public class DailyAgentTimeTest extends TestSuiteBase {

    /** A zone with daylight saving, and an offset that moves days against UTC. */
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    /** Fifteen minutes, the controller's default silence threshold. */
    private static final Duration SILENT = Duration.ofMinutes(15);
    /** One hour in milliseconds. */
    private static final long HOUR = 3_600_000L;

    /** A finished job between two local times. */
    private static JobInterval finished(String id, String start, String end, double cost) {
        return new JobInterval(id, "ws", "SUCCESS", Instant.parse(start), Instant.parse(end), null, cost);
    }

    /** A job crossing local midnight puts each part on its own day; count and cost go to the start day. */
    @Test(timeout = 10000)
    public void aJobAcrossMidnightIsSplit() {
        // 23:00 Oct 1 to 02:00 Oct 2, Pacific daylight time (UTC-7).
        JobInterval job = finished("a", "2026-10-02T06:00:00Z", "2026-10-02T09:00:00Z", 1.5);
        List<DailyAgentTime.Day> days = DailyAgentTime.compute(List.of(job),
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), LA,
                Instant.parse("2026-10-03T00:00:00Z"), SILENT);

        assertEquals(2, days.size());
        assertEquals(HOUR, days.get(0).wallClockMs());
        assertEquals(2 * HOUR, days.get(1).wallClockMs());
        assertEquals(1, days.get(0).jobCount());
        assertEquals(0, days.get(1).jobCount());
        assertEquals(1.5, days.get(0).costUsd(), 1e-9);
        assertEquals(0.0, days.get(1).costUsd(), 1e-9);
    }

    /** Jobs in parallel each count in full, so a day can exceed 24 hours. */
    @Test(timeout = 10000)
    public void parallelJobsAddUp() {
        List<JobInterval> jobs = List.of(
                finished("a", "2026-10-05T07:00:00Z", "2026-10-06T07:00:00Z", 0),
                finished("b", "2026-10-05T07:00:00Z", "2026-10-06T07:00:00Z", 0));
        List<DailyAgentTime.Day> days = DailyAgentTime.compute(jobs,
                LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-05"), LA,
                Instant.parse("2026-10-07T00:00:00Z"), SILENT);
        assertEquals(48 * HOUR, days.get(0).wallClockMs());
    }

    /** A job still running counts its time so far as running time, not finished time. */
    @Test(timeout = 10000)
    public void aRunningJobCountsUpToNow() {
        Instant now = Instant.parse("2026-10-10T20:00:00Z");
        JobInterval job = new JobInterval("r", "ws", "STARTED", Instant.parse("2026-10-10T18:00:00Z"),
                null, now.minusSeconds(60), 0);
        DailyAgentTime.Day day = DailyAgentTime.compute(List.of(job),
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"), LA, now, SILENT).get(0);
        assertEquals(0, day.wallClockMs());
        assertEquals(2 * HOUR, day.runningMs());
        assertEquals(1, day.jobCount());
    }

    /** A running job that went silent stops at its last heartbeat. */
    @Test(timeout = 10000)
    public void aSilentJobStopsAtItsLastHeartbeat() {
        Instant now = Instant.parse("2026-10-10T23:00:00Z");
        JobInterval job = new JobInterval("dead", "ws", "STARTED", Instant.parse("2026-10-10T18:00:00Z"),
                null, Instant.parse("2026-10-10T19:00:00Z"), 0);
        DailyAgentTime.Day day = DailyAgentTime.compute(List.of(job),
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"), LA, now, SILENT).get(0);
        assertEquals(HOUR, day.runningMs());
    }

    /** Time outside the range is dropped; a job that started before it still counts its share inside. */
    @Test(timeout = 10000)
    public void onlyTimeInsideTheRangeCounts() {
        // Oct 3 22:00 to Oct 5 02:00 local; range is Oct 4 only.
        JobInterval job = finished("long", "2026-10-04T05:00:00Z", "2026-10-05T09:00:00Z", 2.0);
        List<DailyAgentTime.Day> days = DailyAgentTime.compute(List.of(job),
                LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-04"), LA,
                Instant.parse("2026-10-06T00:00:00Z"), SILENT);
        assertEquals(1, days.size());
        assertEquals(24 * HOUR, days.get(0).wallClockMs());
        assertEquals("started the day before the range", 0, days.get(0).jobCount());
    }

    /** The day clocks fall back is 25 hours long, and a job spanning it gets all 25. */
    @Test(timeout = 10000)
    public void theLongDayAtTheEndOfDaylightSaving() {
        LocalDate fallBack = LocalDate.parse("2026-11-01");
        JobInterval job = new JobInterval("all", "ws", "SUCCESS",
                DailyAgentTime.rangeStart(fallBack, LA), DailyAgentTime.rangeEnd(fallBack, LA), null, 0);
        DailyAgentTime.Day day = DailyAgentTime.compute(List.of(job), fallBack, fallBack, LA,
                Instant.parse("2026-11-03T00:00:00Z"), SILENT).get(0);
        assertEquals(25 * HOUR, day.wallClockMs());
    }

    /** A completion recorded before the start yields an empty span rather than negative time. */
    @Test(timeout = 10000)
    public void aCompletionBeforeTheStartCountsNothing() {
        JobInterval job = finished("skewed", "2026-10-10T18:00:00Z", "2026-10-10T17:00:00Z", 0.25);
        assertEquals(job.startedAt(), job.end(Instant.parse("2026-10-11T00:00:00Z"), SILENT));
        DailyAgentTime.Day day = DailyAgentTime.compute(List.of(job),
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"), LA,
                Instant.parse("2026-10-11T00:00:00Z"), SILENT).get(0);
        assertEquals(0, day.wallClockMs());
        assertEquals(1, day.jobCount());
        assertEquals(0.25, day.costUsd(), 1e-9);
    }

    /** A running job that never sent a heartbeat and went silent stops at its start. */
    @Test(timeout = 10000)
    public void aSilentJobWithoutHeartbeatStopsAtItsStart() {
        Instant start = Instant.parse("2026-10-10T18:00:00Z");
        JobInterval job = new JobInterval("mute", "ws", "STARTED", start, null, null, 0);
        assertEquals(start, job.end(Instant.parse("2026-10-10T23:00:00Z"), SILENT));
    }

    /** A non-positive silence threshold never stops a running job: it accrues up to now. */
    @Test(timeout = 10000)
    public void aNonPositiveThresholdNeverStopsARunningJob() {
        Instant now = Instant.parse("2026-10-10T23:00:00Z");
        JobInterval job = new JobInterval("old", "ws", "STARTED", Instant.parse("2026-10-10T18:00:00Z"),
                null, Instant.parse("2026-10-10T19:00:00Z"), 0);
        assertTrue(job.isRunning());
        assertEquals(now, job.end(now, Duration.ZERO));
        assertEquals(now, job.end(now, Duration.ofMinutes(-1)));
    }

    /** A job with no recorded start is skipped entirely. */
    @Test(timeout = 10000)
    public void aJobWithoutAStartIsSkipped() {
        JobInterval job = new JobInterval("x", "ws", "SUCCESS", null, Instant.parse("2026-10-10T19:00:00Z"), null, 3.0);
        DailyAgentTime.Day day = DailyAgentTime.compute(List.of(job),
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"), LA,
                Instant.parse("2026-10-11T00:00:00Z"), SILENT).get(0);
        assertEquals(0, day.jobCount());
        assertEquals(0.0, day.costUsd(), 1e-9);
        assertEquals(0, day.wallClockMs());
    }

    /** The JSON carries the zone and one object per day with every field. */
    @Test(timeout = 10000)
    public void rendersJson() {
        List<DailyAgentTime.Day> days = List.of(
                new DailyAgentTime.Day(LocalDate.parse("2026-10-01"), 5, 7, 2, 1.5),
                new DailyAgentTime.Day(LocalDate.parse("2026-10-02"), 0, 0, 0, 0.0));
        assertEquals("{\"tz\":\"America/Los_Angeles\",\"days\":["
                + "{\"date\":\"2026-10-01\",\"wallClockMs\":5,\"runningMs\":7,\"jobCount\":2,\"costUsd\":1.5},"
                + "{\"date\":\"2026-10-02\",\"wallClockMs\":0,\"runningMs\":0,\"jobCount\":0,\"costUsd\":0.0}]}",
                DailyAgentTime.toJson(LA, days));
        assertEquals("{\"tz\":\"UTC\",\"days\":[]}", DailyAgentTime.toJson(ZoneId.of("UTC"), List.of()));
    }

    /** Every day in the range is present, with zeros where nothing ran. */
    @Test(timeout = 10000)
    public void emptyDaysArePresent() {
        List<DailyAgentTime.Day> days = DailyAgentTime.compute(List.of(),
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"), LA,
                Instant.parse("2026-10-08T00:00:00Z"), SILENT);
        assertEquals(7, days.size());
        assertEquals(LocalDate.parse("2026-10-07"), days.get(6).date());
        assertTrue(days.stream().allMatch(d -> d.wallClockMs() == 0 && d.jobCount() == 0));
    }
}
