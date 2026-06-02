/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.profile.OperationProfile;

/**
 * Holds timing measurements for build, optimize, compile, and run phases
 * of a test operation. Used by performance and gradient experiment tests
 * to record and format timing data.
 */
class TimingResult {
	/** Build phase duration in nanoseconds. */
	final long buildTimeNs;
	/** Optimize phase duration in nanoseconds. */
	final long optimizeTimeNs;
	/** Compile phase duration in nanoseconds. */
	final long compileTimeNs;
	/** Run phase duration in nanoseconds. */
	final long runTimeNs;
	/** Profile capturing operation timing details for the measured run. */
	final OperationProfile profile;

	/**
	 * Records the timing measurements for a single test operation.
	 *
	 * @param build    build phase duration in nanoseconds
	 * @param optimize optimize phase duration in nanoseconds
	 * @param compile  compile phase duration in nanoseconds
	 * @param run      run phase duration in nanoseconds
	 * @param profile  operation profile captured for the run
	 */
	TimingResult(long build, long optimize, long compile, long run, OperationProfile profile) {
		this.buildTimeNs = build;
		this.optimizeTimeNs = optimize;
		this.compileTimeNs = compile;
		this.runTimeNs = run;
		this.profile = profile;
	}

	/** Returns build time in milliseconds. */
	double buildTimeMs() { return buildTimeNs / 1_000_000.0; }

	/** Returns optimize time in milliseconds. */
	double optimizeTimeMs() { return optimizeTimeNs / 1_000_000.0; }

	/** Returns compile time in milliseconds. */
	double compileTimeMs() { return compileTimeNs / 1_000_000.0; }

	/** Returns run time in milliseconds. */
	double runTimeMs() { return runTimeNs / 1_000_000.0; }

	/** Returns total time across all phases in milliseconds. */
	double totalTimeMs() {
		return buildTimeMs() + optimizeTimeMs() + compileTimeMs() + runTimeMs();
	}
}
