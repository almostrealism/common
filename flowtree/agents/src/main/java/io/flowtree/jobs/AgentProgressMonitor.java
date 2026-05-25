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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects an autonomous agent stuck in a non-converging loop by watching the
 * stream of actions it takes, rather than the wall-clock gaps between them.
 *
 * <p>{@link AgentInactivityMonitor} guards against a process that goes
 * <em>silent</em>; it cannot see a process that stays busy while making no
 * progress — repeatedly issuing the same tool call, churning the same edit, or
 * resetting and re-attempting the same change. That failure mode emits output
 * constantly, so a silence timer never fires. This monitor closes that gap.</p>
 *
 * <p>The owning read loop feeds each subprocess output line through a
 * runner-supplied signature function and passes the resulting <em>action
 * signature</em> (e.g. {@code "tool:bash:" + hash(command)}) to
 * {@link #observe(String)}. Lines that do not correspond to an action yield a
 * {@code null} signature and are ignored. The monitor keeps a bounded sliding
 * window of the most recent signatures; once enough actions have been observed,
 * it reports a loop when any single signature occupies at least
 * {@code repeatThreshold} of the last {@code windowSize} actions.</p>
 *
 * <p>The detector is intentionally length-agnostic: long, productive sessions
 * issue a varied stream of signatures and never concentrate one signature in a
 * short window, so a generous turn budget is unaffected. Only genuine
 * repetition trips it.</p>
 *
 * <p>Instances are not thread-safe; {@link #observe(String)} is expected to be
 * called from the single thread that reads the subprocess output stream.</p>
 */
final class AgentProgressMonitor {

    /** Default: a single action signature this many times within the window trips detection. */
    static final int DEFAULT_REPEAT_THRESHOLD = 10;

    /** Default: number of most-recent action signatures retained for frequency counting. */
    static final int DEFAULT_WINDOW_SIZE = 40;

    /** Default: actions observed before the detector arms, so brief startup repetition is tolerated. */
    static final int DEFAULT_MIN_ACTIONS = 30;

    /** A single signature reaching this count within the window reports a loop. */
    private final int repeatThreshold;

    /** Maximum number of recent signatures retained. */
    private final int windowSize;

    /** Number of actions that must be observed before detection can fire. */
    private final int minActions;

    /** The most-recent signatures, oldest first; bounded by {@link #windowSize}. */
    private final Deque<String> window = new ArrayDeque<>();

    /** Occurrence count of each signature currently in {@link #window}. */
    private final Map<String, Integer> counts = new HashMap<>();

    /** Total number of non-null signatures observed over the lifetime of the monitor. */
    private long totalActions;

    /** The signature whose repetition tripped detection, or null until then. */
    private String offendingSignature;

    /** Creates a monitor with the default thresholds. */
    AgentProgressMonitor() {
        this(DEFAULT_REPEAT_THRESHOLD, DEFAULT_WINDOW_SIZE, DEFAULT_MIN_ACTIONS);
    }

    /**
     * Creates a monitor with explicit thresholds.
     *
     * @param repeatThreshold occurrences of one signature within the window that report a loop
     * @param windowSize      number of recent signatures retained for counting
     * @param minActions      actions observed before detection can fire
     */
    AgentProgressMonitor(int repeatThreshold, int windowSize, int minActions) {
        if (repeatThreshold < 2) {
            throw new IllegalArgumentException("repeatThreshold must be at least 2");
        }
        if (windowSize < repeatThreshold) {
            throw new IllegalArgumentException("windowSize must be at least repeatThreshold");
        }
        this.repeatThreshold = repeatThreshold;
        this.windowSize = windowSize;
        this.minActions = minActions;
    }

    /**
     * Records one action signature and reports whether the agent now appears to
     * be looping.
     *
     * @param signature the action signature, or {@code null} for a non-action line (ignored)
     * @return {@code true} when repetition first crosses the threshold; {@code false} otherwise
     */
    boolean observe(String signature) {
        if (signature == null) {
            return false;
        }
        totalActions++;
        window.addLast(signature);
        int count = counts.merge(signature, 1, Integer::sum);
        if (window.size() > windowSize) {
            String evicted = window.removeFirst();
            if (counts.merge(evicted, -1, Integer::sum) == 0) {
                counts.remove(evicted);
            }
        }
        if (totalActions < minActions) {
            return false;
        }
        if (count >= repeatThreshold) {
            offendingSignature = signature;
            return true;
        }
        return false;
    }

    /**
     * Returns the signature whose repetition tripped detection.
     *
     * @return the offending signature, or {@code null} if detection has not fired
     */
    String getOffendingSignature() {
        return offendingSignature;
    }
}
