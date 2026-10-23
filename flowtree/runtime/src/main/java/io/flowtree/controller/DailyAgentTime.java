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

import io.flowtree.JsonFieldExtractor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent time per calendar day, in a time zone the caller chooses.
 *
 * <p>Each job's span ({@link JobInterval#end(Instant, Duration)}) is split at
 * local midnight, so a job that runs from 23:00 to 02:00 puts one hour on the
 * first day and two on the next. A finished job's time is
 * {@link Day#wallClockMs()}; a running job's time so far is
 * {@link Day#runningMs()}, kept apart so a caller can tell settled time from
 * time that is still growing. Jobs running in parallel each count in full, so
 * a day can hold more than 24 hours of agent time.</p>
 *
 * <p>{@link Day#jobCount()} and {@link Day#costUsd()} belong to the day a job
 * started, as in the weekly statistics. Days are computed in the requested
 * zone, including the short and long days at daylight-saving changes.</p>
 */
public final class DailyAgentTime {

    /** The longest range a single request may cover, in days. */
    public static final int MAX_DAYS = 366;

    /**
     * One calendar day's agent time.
     *
     * @param date        the day, in the requested zone
     * @param wallClockMs time finished jobs spent on this day
     * @param runningMs   time jobs still running have spent on this day so far
     * @param jobCount    jobs that started on this day
     * @param costUsd     the recorded cost of jobs that started on this day
     */
    public record Day(LocalDate date, long wallClockMs, long runningMs,
                      int jobCount, double costUsd) {
    }

    /** Not instantiable; see {@link #compute}. */
    private DailyAgentTime() {
    }

    /**
     * Returns the instant {@code from} starts at in {@code zone}: the first
     * moment of the range a store query must cover.
     *
     * @param from the first day
     * @param zone the zone days are counted in
     * @return the start of {@code from}
     */
    public static Instant rangeStart(LocalDate from, ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    /**
     * Returns the instant the day after {@code to} starts at in {@code zone}:
     * the end, exclusive, of the range a store query must cover.
     *
     * @param to   the last day, inclusive
     * @param zone the zone days are counted in
     * @return the start of the day after {@code to}
     */
    public static Instant rangeEnd(LocalDate to, ZoneId zone) {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /**
     * Totals the given jobs by day, for every day from {@code from} to
     * {@code to} inclusive. Days with no agent time are included, as zeros, so
     * the result always has one entry per day.
     *
     * @param jobs            the jobs overlapping the range
     * @param from            the first day
     * @param to              the last day, inclusive
     * @param zone            the zone days are counted in
     * @param now             the reference instant for running jobs
     * @param silentThreshold the silence after which a running job stops
     *                        accruing time
     * @return one entry per day, oldest first
     */
    public static List<Day> compute(List<JobInterval> jobs, LocalDate from, LocalDate to,
                                    ZoneId zone, Instant now, Duration silentThreshold) {
        int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        long[] finished = new long[days];
        long[] running = new long[days];
        int[] counts = new int[days];
        double[] costs = new double[days];

        for (JobInterval job : jobs) {
            if (job.startedAt() == null) continue;
            Instant start = job.startedAt();
            Instant end = job.end(now, silentThreshold);

            int startIndex = index(start.atZone(zone).toLocalDate(), from, days);
            if (startIndex >= 0) {
                counts[startIndex]++;
                costs[startIndex] += job.costUsd();
            }

            long[] target = job.isRunning() ? running : finished;
            LocalDate day = start.atZone(zone).toLocalDate();
            Instant cursor = start;
            while (cursor.isBefore(end)) {
                Instant nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant();
                Instant segmentEnd = end.isBefore(nextMidnight) ? end : nextMidnight;
                int i = index(day, from, days);
                if (i >= 0) {
                    target[i] += Duration.between(cursor, segmentEnd).toMillis();
                }
                if (day.isAfter(to)) break;
                cursor = segmentEnd;
                day = day.plusDays(1);
            }
        }

        List<Day> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            result.add(new Day(from.plusDays(i), finished[i], running[i], counts[i], costs[i]));
        }
        return result;
    }

    /**
     * Renders a computed range as the {@code /api/stats/daily} response.
     *
     * @param zone the zone the days were counted in
     * @param days the days, as returned by {@link #compute}
     * @return the JSON object
     */
    public static String toJson(ZoneId zone, List<Day> days) {
        StringBuilder json = new StringBuilder();
        json.append("{\"tz\":\"").append(JsonFieldExtractor.escapeJson(zone.getId())).append("\"");
        json.append(",\"days\":[");
        for (int i = 0; i < days.size(); i++) {
            Day d = days.get(i);
            if (i > 0) json.append(",");
            json.append("{\"date\":\"").append(d.date()).append("\"");
            json.append(",\"wallClockMs\":").append(d.wallClockMs());
            json.append(",\"runningMs\":").append(d.runningMs());
            json.append(",\"jobCount\":").append(d.jobCount());
            json.append(",\"costUsd\":").append(d.costUsd());
            json.append("}");
        }
        json.append("]}");
        return json.toString();
    }

    /**
     * Returns {@code day}'s position in the range, or -1 when it falls outside.
     *
     * @param day  the day
     * @param from the first day of the range
     * @param days the number of days in the range
     * @return the index, or -1
     */
    private static int index(LocalDate day, LocalDate from, int days) {
        long i = day.toEpochDay() - from.toEpochDay();
        return (i >= 0 && i < days) ? (int) i : -1;
    }
}
