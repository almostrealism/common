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

import java.util.List;

/**
 * The result of listing an organization's self-hosted runners.
 *
 * <p>Either the list was read ({@link #ok} is {@code true} and {@link #runners}
 * holds them, possibly empty) or it was not ({@link #ok} is {@code false} and
 * {@link #error} says why — a missing permission, or GitHub unreachable). The
 * failure is kept rather than collapsed to an empty list so that a caller can
 * tell "the token cannot see the runners" from "there are no runners", and
 * report the reason instead of silently answering as though every fleet were
 * busy.</p>
 *
 * @param ok      whether the runner list was read successfully
 * @param runners the runners, empty when {@link #ok} is {@code false}
 * @param error   why the list could not be read, or {@code null} when {@link #ok}
 *
 * @author  Michael Murray
 */
public record RunnerListing(boolean ok, List<GitHubRunner> runners, String error) {

    /**
     * A successful listing.
     *
     * @param runners the runners read
     * @return the listing
     */
    public static RunnerListing of(List<GitHubRunner> runners) {
        return new RunnerListing(true, List.copyOf(runners), null);
    }

    /**
     * A failed listing.
     *
     * @param reason why the list could not be read
     * @return the listing
     */
    public static RunnerListing failed(String reason) {
        return new RunnerListing(false, List.of(), reason);
    }
}
