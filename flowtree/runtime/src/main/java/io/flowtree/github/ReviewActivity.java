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

package io.flowtree.github;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a remediation pass was dispatched against, besides the commit: the kind
 * of pass (the submitter's label, such as "Review branch changes" or "Resolve
 * build failure") and the pull request's unresolved review threads at that
 * moment, as {@link PullRequestEscalation#reviewActivity} reports it. The
 * controller's remediation record keeps the one for the last dispatched pass so
 * that a pass re-triggered on the same commit can be recognised as having
 * nothing new to look at ({@link #hasNothingNewSince}).
 *
 * <p>Threads are identified by their first comment's link, which GitHub never
 * reuses, so a thread opened after the last pass is one whose identifier that
 * pass did not see. A thread resolved since is not new activity: the remaining
 * set is a subset of the earlier one.</p>
 *
 * @author Michael Murray
 * @see PullRequestEscalation#reviewActivity
 */
public final class ReviewActivity {

    /** Separates the label from the thread identifiers in {@link #encode()}. */
    private static final char LABEL_END = '\n';

    /** Separates thread identifiers in {@link #encode()}. */
    private static final String THREAD_SEPARATOR = " ";

    /** The kind of pass, as the submitter labels it; never {@code null}. */
    private final String label;

    /** Identifiers of the pull request's unresolved review threads. */
    private final Set<String> unresolvedThreads;

    /**
     * Creates the review activity a pass ran against.
     *
     * @param label             the submitter's label for the pass, or {@code null} for none
     * @param unresolvedThreads identifiers of the pull request's unresolved review threads
     */
    public ReviewActivity(String label, Set<String> unresolvedThreads) {
        this.label = label == null ? "" : label.replace(LABEL_END, ' ').strip();
        this.unresolvedThreads = Collections.unmodifiableSet(new TreeSet<>(unresolvedThreads));
    }

    /** Returns the submitter's label for the pass; empty when it gave none. */
    public String getLabel() { return label; }

    /** Returns the identifiers of the unresolved review threads, sorted. */
    public Set<String> getUnresolvedThreads() { return unresolvedThreads; }

    /**
     * Returns whether a pass of the same kind would find nothing new here that it
     * did not find in {@code previous}: the label is the same and every
     * unresolved thread was already unresolved then.
     *
     * @param previous the activity the earlier pass ran against, or {@code null}
     * @return {@code true} when there is nothing new; {@code false} when
     *         {@code previous} is unknown
     */
    public boolean hasNothingNewSince(ReviewActivity previous) {
        return previous != null && label.equals(previous.label)
                && previous.unresolvedThreads.containsAll(unresolvedThreads);
    }

    /**
     * Returns the stored form: the label, a line break, and the thread
     * identifiers separated by spaces. Thread links contain no whitespace.
     *
     * @return the encoded activity
     */
    public String encode() {
        return label + LABEL_END + String.join(THREAD_SEPARATOR, unresolvedThreads);
    }

    /**
     * Reads the stored form written by {@link #encode()}.
     *
     * @param encoded the stored value, or {@code null}
     * @return the activity, or {@code null} when nothing was stored or the value
     *         is not in the encoded form
     */
    public static ReviewActivity decode(String encoded) {
        if (encoded == null) return null;
        int end = encoded.indexOf(LABEL_END);
        if (end < 0) return null;
        String threads = encoded.substring(end + 1).strip();
        Set<String> ids = threads.isEmpty() ? Set.of()
                : new TreeSet<>(Arrays.asList(threads.split(THREAD_SEPARATOR)));
        return new ReviewActivity(encoded.substring(0, end), ids);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReviewActivity)) return false;
        ReviewActivity that = (ReviewActivity) other;
        return label.equals(that.label) && unresolvedThreads.equals(that.unresolvedThreads);
    }

    @Override
    public int hashCode() {
        return 31 * label.hashCode() + unresolvedThreads.hashCode();
    }

    @Override
    public String toString() {
        return "ReviewActivity[" + label + ", " + unresolvedThreads.size() + " unresolved threads]";
    }
}
