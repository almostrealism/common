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

import org.almostrealism.studio.AudioMeter;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.heredity.TemporalCellular;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Abstract base class for audio health computations that manages wave output,
 * stem recording, and per-channel audio metering. Subclasses implement
 * {@link #computeHealth()} and optionally override {@link #configureMeasures}.
 */
public abstract class HealthComputationAdapter implements AudioHealthComputation<TemporalCellular> {
	/** Number of measure tracks per stereo pair (left and right). */
	public static final int MEASURE_COUNT = 2;

	/** Standard evaluation duration in seconds used as the default. */
	public static int standardDurationSeconds = 230;

	/** Standard evaluation duration in audio frames, derived from {@link #standardDurationSeconds}. */
	public static int standardDurationFrames = standardDurationSeconds * OutputLine.sampleRate;

	/** The temporal cellular target currently under evaluation. */
	private TemporalCellular target;

	/** Number of pattern channels (stems) for this computation. */
	private final int channels;

	/** Wave output for the master mix. */
	private WaveOutput out;

	/** Supplier providing the output file path on demand. */
	private Supplier<String> outputFileSupplier;

	/** Function producing stem file paths by stem index. */
	private IntFunction<String> stemFileSupplier;

	/** Resolved output file, populated when the supplier is first invoked. */
	private File outputFile;

	/** Resolved stem files indexed by stem channel number. */
	private final Map<Integer, File> stemFiles;

	/** Consumer that receives wave detail metadata after each render. */
	private Consumer<WaveDetails> detailsProcessor;

	/** Per-channel audio meters used for silence and clip analysis. */
	private Map<ChannelInfo, AudioMeter> measures;

	/** Per-stem wave outputs for recording individual instrument stems. */
	private List<WaveOutput> stems;

	/** Aggregated multi-channel output wrapping master, stems, and measures. */
	private MultiChannelAudioOutput output;

	/**
	 * Constructs a health computation adapter.
	 *
	 * @param channels the number of pattern/instrument channels (stems)
	 * @param stereo   {@code true} to configure stereo output
	 */
	public HealthComputationAdapter(int channels, boolean stereo) {
		this.channels = channels;
		this.stemFiles = new HashMap<>();
		initOutput(stereo);
	}

	/**
	 * Initialises the master wave output, per-channel measures, stem outputs, and the
	 * aggregated {@link MultiChannelAudioOutput}. Called from the constructor and may
	 * be called again to reset state.
	 *
	 * @param stereo {@code true} to use stereo wave output
	 */
	protected void initOutput(boolean stereo) {
		out = new WaveOutput(() ->
							Optional.ofNullable(outputFileSupplier).map(s -> {
								outputFile = new File(s.get());
								return outputFile;
							}).orElse(null),
				24, OutputLine.sampleRate, standardDurationFrames, stereo);
		measures = new HashMap<>();
		measures.put(new ChannelInfo(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT), new AudioMeter());
		measures.put(new ChannelInfo(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.RIGHT), new AudioMeter());
		measures.put(new ChannelInfo(ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.LEFT), new AudioMeter());
		measures.put(new ChannelInfo(ChannelInfo.Voicing.WET, ChannelInfo.StereoChannel.RIGHT), new AudioMeter());
		configureMeasures(measures);

		stems = IntStream.range(0, channels).mapToObj(i ->
				new WaveOutput(() ->
						Optional.ofNullable(stemFileSupplier).map(s -> {
							File f = new File(stemFileSupplier.apply(i));
							stemFiles.put(i, f);
							return f;
						}).orElse(null),
						24, OutputLine.sampleRate,
						HealthComputationAdapter.standardDurationFrames, stereo)).collect(Collectors.toList());

		output = new MultiChannelAudioOutput(out, stems, (channelInfo) -> new AudioMeter());
	}

	/** Returns the current evaluation target. */
	public TemporalCellular getTarget() { return target; }

	/** {@inheritDoc} */
	@Override
	public void setTarget(TemporalCellular target) { this.target = target; }

	/** Returns the master wave output for the full mix. */
	public WaveOutput getMaster() { return out; }

	/** Returns the per-channel audio meters used for metering and silence detection. */
	public Map<ChannelInfo, AudioMeter> getMeasures() { return measures; }

	/** Returns the per-stem wave outputs. */
	public List<WaveOutput> getStems() { return stems; }

	/** {@inheritDoc} */
	@Override
	public MultiChannelAudioOutput getOutput() { return output; }

	/**
	 * Hook called during initialisation to configure audio meters in the measures map.
	 * Subclasses may override to set silence thresholds or clip limits.
	 *
	 * @param measures the map of channel info to audio meters to configure
	 */
	protected void configureMeasures(Map<ChannelInfo, AudioMeter> measures) { }

	/**
	 * Sets the function that provides stem file paths by stem index.
	 *
	 * @param file a function mapping stem index to file path
	 */
	public void setStemFile(IntFunction<String> file) { this.stemFileSupplier = file; }

	/**
	 * Sets a fixed output file path for the master mix.
	 *
	 * @param file the file path
	 */
	public void setOutputFile(String file) { setOutputFile(() -> file); }

	/**
	 * Sets a supplier that provides the output file path for the master mix on demand.
	 *
	 * @param file supplier for the file path
	 */
	public void setOutputFile(Supplier<String> file) { this.outputFileSupplier = file; }

	/** Returns the resolved master output file, or {@code null} if not yet resolved. */
	protected File getOutputFile() { return outputFile; }

	/** Returns all resolved stem files. */
	protected Collection<File> getStemFiles() { return stemFiles.values(); }

	/** Returns the current wave-details processor, or {@code null} if not set. */
	public Consumer<WaveDetails> getWaveDetailsProcessor() { return detailsProcessor; }

	/** {@inheritDoc} */
	@Override
	public void setWaveDetailsProcessor(Consumer<WaveDetails> detailsProcessor) {
		this.detailsProcessor = detailsProcessor;
	}

	@Override
	public void reset() {
		AudioHealthComputation.super.reset();
		out.reset(); // TODO  Why is this called twice?
		if (stems != null) stems.forEach(WaveOutput::reset);
		measures.values().forEach(AudioMeter::reset);
		out.reset();
	}

	@Override
	public void destroy() {
		AudioHealthComputation.super.destroy();
		if (out != null) out.destroy();
		if (stems != null) stems.forEach(WaveOutput::destroy);
	}

	/**
	 * Sets the standard evaluation duration in seconds, updating both the seconds
	 * and the derived frame count fields.
	 *
	 * @param sec the standard duration in seconds
	 */
	public static void setStandardDuration(int sec) {
		standardDurationSeconds = sec;
		standardDurationFrames = standardDurationSeconds * OutputLine.sampleRate;
	}

	/**
	 * Returns an auxiliary file derived from the given file by replacing its
	 * {@code .wav} extension with the given suffix.
	 *
	 * @param file   the base file, or {@code null}
	 * @param suffix the replacement suffix (e.g., {@code "features.bin"})
	 * @return the auxiliary file, or {@code null} if {@code file} is {@code null}
	 */
	public static File getAuxFile(File file, String suffix) {
		if (file == null) return null;
		return new File(file.getParentFile(), file.getName().replace(".wav", "." + suffix));
	}
}
