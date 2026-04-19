/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.health;

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.Console;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalRunner;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@link StableDurationHealthComputation} is a {@link HealthComputationAdapter} which
 * computes a health score based on the duration that a {@link Temporal} can be used before
 * a min or max clip value is reached.
 *
 * @author  Michael Murray
 */
public class StableDurationHealthComputation extends SilenceDurationHealthComputation implements CellFeatures {
	/** When {@code true}, rendered audio is written to disk after each evaluation. */
	public static boolean enableOutput = true;

	/** When {@code true}, evaluations are aborted if the timeout threshold is exceeded. */
	public static boolean enableTimeout = false;

	/** When {@code true}, the operation profile is saved to disk periodically. */
	public static boolean enableProfileAutosave = false;

	/** Optional profile node used to collect kernel execution statistics. */
	public static OperationProfile profile;

	/** Cumulative total audio frames generated across all evaluations. */
	private static long totalGeneratedFrames;

	/** Cumulative total generation time in milliseconds across all evaluations. */
	private static long totalGenerationTime;

	/** Maximum allowed evaluation wall-clock time in milliseconds. */
	private static final long timeout = 40 * 60 * 1000L;

	/** Interval in milliseconds at which the timeout thread checks for expiry. */
	private static final long timeoutInterval = 5000;

	/** Maximum evaluation duration in frames (may be reduced via {@link #setMaxDuration}). */
	private long max = standardDurationFrames;

	/** Number of frames advanced per iteration of the evaluation loop. */
	private int iter;

	/** Flag set by the silence listener when excessive silence is detected. */
	private boolean encounteredSilence;

	/** Temporal runner that drives the evaluation loop efficiently. */
	private TemporalRunner runner;

	/** Background thread that monitors the wall-clock timeout. */
	private Thread timeoutTrigger;

	/** Set to {@code true} to request the timeout thread to stop. */
	private boolean endTimeoutTrigger;

	/** Wall-clock time at which the current evaluation began. */
	private long startTime;

	/** Wall-clock time at the start of the most recent batch iteration. */
	private long iterStart;

	/** GPU-resident abort flag; set to 1.0 to interrupt the evaluation loop. */
	private PackedCollection abortFlag;

	/**
	 * Creates a stereo stable-duration computation with the default 6-second silence limit.
	 *
	 * @param channels the number of pattern channels (stems)
	 */
	public StableDurationHealthComputation(int channels) {
		this(channels, true);
	}

	/**
	 * Creates a stable-duration computation with the default 6-second silence limit.
	 *
	 * @param channels the number of pattern channels (stems)
	 * @param stereo   {@code true} to enable stereo wave output
	 */
	public StableDurationHealthComputation(int channels, boolean stereo) {
		super(channels, stereo, 6);
		addSilenceListener(() -> encounteredSilence = true);
		setBatchSize(TemporalRunner.enableOptimization ? 1 : (OutputLine.sampleRate / 2));
	}

	/**
	 * Sets the number of audio frames advanced per evaluation loop iteration.
	 *
	 * @param iter frames per iteration
	 */
	public void setBatchSize(int iter) {
		this.iter = iter;
	}

	/**
	 * Sets the maximum evaluation duration.
	 *
	 * @param sec maximum duration in seconds
	 */
	public void setMaxDuration(long sec) { this.max = (int) (sec * OutputLine.sampleRate); }

	/** {@inheritDoc} */
	@Override
	public void setTarget(TemporalCellular target) {
		if (getTarget() == null) {
			super.setTarget(target);
			this.abortFlag = new PackedCollection(1);
			this.abortFlag.setMem(0, 0.0);
			this.runner = new TemporalRunner(target, iter);
			this.runner.setProfile(profile);
		} else if (getTarget() != target) {
			throw new IllegalArgumentException("Health computation cannot be reused");
		}
	}

	/**
	 * Starts the background timeout-trigger thread. If a previous thread has not yet
	 * terminated, this method waits briefly for it before proceeding.
	 */
	protected void startTimeoutTrigger() {
		if (timeoutTrigger != null) {
			try {
				Thread.sleep(timeoutInterval + 100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				warn(e.getMessage(), e);
			}
		}

		if (timeoutTrigger != null) {
			throw new IllegalArgumentException();
		}

		timeoutTrigger = new Thread(() -> {
			w: while (true) {
				try {
					Thread.sleep(timeoutInterval);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					warn(e.getMessage(), e);
					endTimeoutTrigger = true;
				}

				if (!endTimeoutTrigger && isTimeout()) {
					if (enableVerbose) log("Trigger timeout");

					abortFlag.setMem(0, 1.0);

					if (enableVerbose) {
						log("Timeout flag set");
					} else {
						console().print("T");
					}

					endTimeoutTrigger = true;
				}

				if (endTimeoutTrigger) {
					timeoutTrigger = null;
					break;
				}
			}
		});

		endTimeoutTrigger = false;
		timeoutTrigger.start();
	}

	/** Signals the timeout trigger thread to stop and clears the abort flag. */
	protected void endTimeoutTrigger() {
		endTimeoutTrigger = true;
		abortFlag.setMem(0, 0.0);
	}

	/**
	 * Returns {@code true} if the wall-clock timeout has been exceeded.
	 */
	protected boolean isTimeout() {
		return enableTimeout && System.currentTimeMillis() - startTime > timeout;
	}

	@Override
	public void reset() {
		if (abortFlag != null)
			abortFlag.setMem(0, 0.0);

		super.reset();
		getTarget().reset();
	}

	@Override
	public AudioHealthScore computeHealth() {
		if (WaveOutput.defaultTimelineFrames < standardDurationFrames) {
			throw new IllegalArgumentException("WaveOutput timeline is too short (" +
					WaveOutput.defaultTimelineFrames + " < " + standardDurationFrames + ")");
		}

		encounteredSilence = false;
		OperationList.setAbortFlag(abortFlag);

//		TODO  Restore average amplitude computation using an on-device
//		TODO  Producer-graph reduction (accumulate samples into a flat
//		TODO  PackedCollection and compute abs().sum()/n at the boundary).

		double score = 0.0;
		double errorMultiplier = 1.0;

		Runnable start;
		Runnable iterate;

		long l = 0;
		long generationTime = 0;

		WaveDetails details = null;

		try {
			start = runner.get();
			iterate = runner.getContinue();

			startTime = System.currentTimeMillis();
			iterStart = startTime;
			if (enableTimeout) startTimeoutTrigger();

			l:
			for (l = 0; l < max && !isTimeout(); l = l + iter) {
				(l == 0 ? start : iterate).run();

				if (enableProfileAutosave && profile instanceof OperationProfileNode
						&& (l + iter) % (OutputLine.sampleRate * 60L) == 0) {
					try {
						String name = "results/optimizer-" + l + ".xml";
						log("Saving profile to " + name);
						((OperationProfileNode) profile).save(name);
						log("Saved profile to " + name);
					} catch (IOException e) {
						warn(e.getMessage(), e);
					}
				}

				long fc = l + iter - 1;
				if (getMaster().getFrameCount() != fc) {
					log("Cursor out of sync (" +
							getMaster().getFrameCount() + " != " + fc + ")");
					throw new RuntimeException();
				}

				getMeasures().values().forEach(m -> {
					checkForSilence(m);

					if (m.getClipCount() > 0) {
						console().print("C");
						if (enableVerbose) console().println();
					}

					if (encounteredSilence) {
						console().print("S");
						if (enableVerbose) console().println();
					}
				});

				// If clipping or silence occurs, report the health score
				if (getMeasures().values().stream().anyMatch(m -> m.getClipCount() > 0) || encounteredSilence) break;

				if (enableVerbose && (l + iter) % (OutputLine.sampleRate / 10) == 0) {
					double v = l + iter;
					console().println("StableDurationHealthComputation: " + v / OutputLine.sampleRate + " seconds (rendered in " +
							(System.currentTimeMillis() - iterStart) + " msec)");
					iterStart = System.currentTimeMillis();
				} else if (!enableVerbose && (l + iter) % (OutputLine.sampleRate * 20L) == 0) {
					console().print(">");
				}
			}

			if (isTimeout())
				errorMultiplier *= 0.75;

			// Report the health score as a combination of
			// the percentage of the expected duration
			// elapsed and the time it takes to reach the
			// average value
//			return ((double) l) / standardDuration -
//					((double) avg.framesUntilAverage()) / standardDuration;
			score = (double) (l + iter) * errorMultiplier / (standardDurationFrames + iter);

			if (enableVerbose) {
				console().println();
				log("Score computed after " + (System.currentTimeMillis() - startTime) + " msec");
			}
		} finally {
			endTimeoutTrigger();

			if (score > 0) {
				if (enableOutput) {
					if (enableVerbose)
						log("Cursor = " + getMaster().getFrameCount());

					getMaster().write().get().run();
					if (getStems() != null) getStems().forEach(s -> s.write().get().run());

					if (getWaveDetailsProcessor() != null) {
						details = WaveDetailsFactory.getDefault()
								.forFile(getOutputFile().getPath());
						getWaveDetailsProcessor().accept(details);
					}
				}
			}

			if (l > 0) {
				generationTime = System.currentTimeMillis() - startTime;
				recordGenerationTime(l, generationTime);
			}

			getMaster().reset();
			if (getStems() != null) getStems().forEach(WaveOutput::reset);
 			reset();
		}

		AudioHealthScore result = new AudioHealthScore(l, score,
				Optional.ofNullable(getOutputFile()).map(File::getPath).orElse(null),
				Optional.ofNullable(getStemFiles()).map(s -> s.stream().map(File::getPath)
						.sorted().collect(Collectors.toList())).orElse(null),
				details == null ? null : List.of(details.getIdentifier()));
		result.setGenerationTime(generationTime);
		return result;
	}

	@Override
	public Console console() { return console; }

	/**
	 * Accumulates generation statistics for throughput reporting.
	 *
	 * @param generatedFrames number of audio frames rendered in this evaluation
	 * @param generationTime  wall-clock time in milliseconds for this evaluation
	 */
	public static void recordGenerationTime(long generatedFrames, long generationTime) {
		totalGeneratedFrames += generatedFrames;
		totalGenerationTime += generationTime;
	}

	/**
	 * Returns the average generation time per second of audio, computed across all
	 * evaluations recorded via {@link #recordGenerationTime}.
	 *
	 * @return seconds of generation time per second of audio, or 0 if no data
	 */
	public static double getGenerationTimePerSecond() {
		double seconds = totalGeneratedFrames / (double) OutputLine.sampleRate;
		double generationTime = totalGenerationTime / 1000.0;
		return seconds == 0 ? 0 : (generationTime / seconds);
	}

}
