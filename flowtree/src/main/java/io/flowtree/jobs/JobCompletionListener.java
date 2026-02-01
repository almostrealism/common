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

/**
 * Listener interface for receiving job completion events.
 *
 * <p>Implementations can be registered with job systems to receive notifications
 * when jobs complete, fail, or are cancelled. This is particularly useful for
 * integrations like Slack that need to post status updates.</p>
 *
 * @author Michael Murray
 * @see JobCompletionEvent
 */
public interface JobCompletionListener {

    /**
     * Called when a job completes (successfully or with failure).
     *
     * @param event the completion event containing job details and results
     */
    void onJobCompleted(JobCompletionEvent event);

    /**
     * Called when a job starts execution.
     * Default implementation does nothing.
     *
     * @param event the event containing job details
     */
    default void onJobStarted(JobCompletionEvent event) {
        // Default: no-op
    }
}
