/*
 * Copyright 2022 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.profile;

import java.util.ArrayList;
import java.util.List;

public class ProfileData {
	private List<RunData> runs;

	public ProfileData() {
		this.runs = new ArrayList<>();
	}

	public void addRun(RunData run) { runs.add(run); }

	public int getTotalRuns() { return runs.size(); }

	public double getAverageRuntimeNanos() {
		return getTotalRuntimeNanos() / runs.size();
	}

	public double getTotalRuntimeNanos() {
		return runs.stream().mapToDouble(RunData::getDurationNanos).sum();
	}

	public String getSummaryString() {
		int count = runs.size();
		double totalSeconds = getTotalRuntimeNanos() * Math.pow(10, -6);
		double averageNanos = getAverageRuntimeNanos();
		return averageNanos + " nanoseconds average - " + count + " executions for " + totalSeconds + " seconds total";
	}
}
