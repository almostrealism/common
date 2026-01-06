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

package org.almostrealism.audio.health;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The {@link StableDurationHealthComputation} is a {@link HealthComputationAdapter} which
 * computes a health score based on the duration that a {@link Temporal} can be used before
 * a min or max clip value is reached.
 *
 * @author  Michael Murray
 */
public class StableDurationHealthComputation extends SilenceDurationHealthComputation implements CellFeatures {
	public static boolean enableOutput = true;
	public static boolean enableTimeout = false;
	public static boolean enableProfileAutosave = false;

	public static OperationProfile profile;
	private static long totalGeneratedFrames, totalGenerationTime;

	private static final long timeout = 40 * 60 * 1000L;
	private static final long timeoutInterval = 5000;

	private long max = standardDurationFrames;
	private int iter;

	private boolean encounteredSilence;

	private TemporalRunner runner;

	private Thread timeoutTrigger;
	private boolean endTimeoutTrigger;
	private long startTime, iterStart;
	private PackedCollection abortFlag;

	public StableDurationHealthComputation(int channels) {
		this(channels, true);
	}

	public StableDurationHealthComputation(int channels, boolean stereo) {
		super(channels, stereo, 6);
		addSilenceListener(() -> encounteredSilence = true);
		setBatchSize(TemporalRunner.enableOptimization ? 1 : (OutputLine.sampleRate / 2));
	}

	public void setBatchSize(int iter) {
		this.iter = iter;
	}

	public void setMaxDuration(long sec) { this.max = (int) (sec * OutputLine.sampleRate); }

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

	protected void startTimeoutTrigger() {
		if (timeoutTrigger != null) {
			try {
				Thread.sleep(timeoutInterval + 100);
			} catch (InterruptedException e) {
				e.printStackTrace();
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
					e.printStackTrace();
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

	protected void endTimeoutTrigger() {
		endTimeoutTrigger = true;
		abortFlag.setMem(0, 0.0);
	}

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

//		TODO  Restore average amplitude computation
//		AverageAmplitude avg = new AverageAmplitude();

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
						e.printStackTrace();
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

	public static void recordGenerationTime(long generatedFrames, long generationTime) {
		totalGeneratedFrames += generatedFrames;
		totalGenerationTime += generationTime;
	}

	public static double getGenerationTimePerSecond() {
		double seconds = totalGeneratedFrames / (double) OutputLine.sampleRate;
		double generationTime = totalGenerationTime / 1000.0;
		return seconds == 0 ? 0 : (generationTime / seconds);
	}


	private class AverageAmplitude implements Consumer<PackedCollection> {
		private final List<PackedCollection> values = new ArrayList<>();

		@Override
		public void accept(PackedCollection s) {
			values.add(s);
		}

		public double average() {
			return values.stream().mapToDouble(v -> v.toDouble(0)).map(Math::abs).average().orElse(0.0);
		}

		public int framesUntilAverage() {
			double avg = average();
			for (int i = 0; i < values.size(); i++) {
				if (Math.abs(values.get(i).toDouble(0)) >= avg) return i;
			}

			return -1; // The mean value theorem states this should never happen
		}
	}
}
