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

package org.almostrealism.studio.pattern.test;

/**
 * Result of an audio rendering operation.
 *
 * <p>Bundles together the output file path, audio statistics for correctness
 * verification, and timing statistics for performance analysis.</p>
 *
 * <h2>Correctness vs Performance</h2>
 *
 * <p>This class cleanly separates two concerns:</p>
 * <ul>
 *   <li><b>Correctness</b> ({@link #stats()}) - Did the rendering produce
 *       valid audio content? Use {@link AudioStats#assertNonSilent} and
 *       {@link AudioStats#assertValidAudio} to verify.</li>
 *   <li><b>Performance</b> ({@link #timing()}) - Can the rendering sustain
 *       real-time playback? Use {@link TimingStats#meetsRealTime} to check.</li>
 * </ul>
 *
 * <h2>Artifact Generation</h2>
 *
 * <p>Pass this result to {@link RealTimeTestHelper#generateArtifacts} to
 * create visual artifacts (spectrogram, summary) for manual inspection.</p>
 *
 * @param outputFile  path to the rendered WAV file
 * @param stats       audio statistics (null if file not readable)
 * @param timing      timing statistics
 * @param bufferCount number of buffers rendered
 * @param frameCount  total frames rendered
 *
 * @see RealTimeTestHelper#renderRealTime
 */
public record RenderResult(
		String outputFile,
		AudioStats stats,
		TimingStats timing,
		int bufferCount,
		int frameCount
) {

	/**
	 * Returns true if this result has valid audio statistics.
	 */
	public boolean hasStats() {
		return stats != null;
	}

	/**
	 * Returns true if this result has timing statistics.
	 *
	 * <p>Timing is only available for real-time renders, not traditional.</p>
	 */
	public boolean hasTiming() {
		return timing != null;
	}

	/**
	 * Returns true if the render was a real-time render (has timing).
	 */
	public boolean isRealTime() {
		return hasTiming();
	}

	/**
	 * Returns a human-readable summary.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("RenderResult[file=").append(outputFile);
		sb.append(", frames=").append(frameCount);

		if (stats != null) {
			sb.append(", silent=").append(stats.isSilent() ? "YES" : "NO");
			sb.append(", rms=").append(String.format("%.6f", stats.rmsLevel()));
		}

		if (timing != null) {
			sb.append(", buffers=").append(bufferCount);
			sb.append(", realTime=").append(timing.meetsRealTime() ? "YES" : "NO");
		}

		sb.append("]");
		return sb.toString();
	}
}
