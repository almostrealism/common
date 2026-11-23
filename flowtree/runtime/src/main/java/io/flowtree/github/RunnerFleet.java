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

import java.util.ArrayList;
import java.util.List;

/**
 * A fleet of self-hosted runners, named by the capability {@code label} every
 * runner in it carries, with the {@code runs-on} label list a job should use to
 * land on one of them.
 *
 * <p>The CI fleets are the CUDA fleet ({@code ar-ci-cuda}), the OpenCL/ROCm
 * fleet ({@code ar-ci-cl}), the Linux CPU fleet ({@code ar-ci}) and the macOS
 * fleet (whose capability label is the {@code macos} OS label). Runner selection
 * walks a preference order of fleets and chooses the first that has an online,
 * idle runner carrying both this fleet's {@link #label} and the job's required
 * label; the answer is this fleet's {@link #runsOn} list with the required label
 * appended (see {@link #runsOnWith(String)}).</p>
 *
 * @param label  the capability label every runner in this fleet carries
 * @param runsOn the {@code runs-on} label list that targets this fleet, in order
 *
 * @author  Michael Murray
 */
public record RunnerFleet(String label, List<String> runsOn) {

    /**
     * Returns this fleet's {@code runs-on} list with {@code requiredLabel}
     * appended, so a job both lands on this fleet and lands only on a runner
     * that also carries the required label. When the list already carries the
     * required label it is returned unchanged.
     *
     * @param requiredLabel the label the job also requires
     * @return the {@code runs-on} label list to use
     */
    public List<String> runsOnWith(String requiredLabel) {
        if (requiredLabel == null || requiredLabel.isBlank() || runsOn.contains(requiredLabel)) {
            return List.copyOf(runsOn);
        }
        List<String> combined = new ArrayList<>(runsOn);
        combined.add(requiredLabel);
        return List.copyOf(combined);
    }
}
