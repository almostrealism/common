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

import org.almostrealism.audio.AudioMeter;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.ChannelInfo;
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

public abstract class HealthComputationAdapter implements AudioHealthComputation<TemporalCellular> {
	public static final int MEASURE_COUNT = 2;
	public static int standardDurationSeconds = 230;
	public static int standardDurationFrames = standardDurationSeconds * OutputLine.sampleRate;

	private TemporalCellular target;
	private final int channels;

	private WaveOutput out;
	private Supplier<String> outputFileSupplier;
	private IntFunction<String> stemFileSupplier;
	private File outputFile;
	private final Map<Integer, File> stemFiles;
	private Consumer<WaveDetails> detailsProcessor;

	private Map<ChannelInfo, AudioMeter> measures;
	private List<WaveOutput> stems;
	private MultiChannelAudioOutput output;

	public HealthComputationAdapter(int channels, boolean stereo) {
		this.channels = channels;
		this.stemFiles = new HashMap<>();
		initOutput(stereo);
	}

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

	public TemporalCellular getTarget() { return target; }

	@Override
	public void setTarget(TemporalCellular target) { this.target = target; }

	public WaveOutput getMaster() { return out; }
	public Map<ChannelInfo, AudioMeter> getMeasures() { return measures; }
	public List<WaveOutput> getStems() { return stems; }
	public MultiChannelAudioOutput getOutput() { return output; }

	protected void configureMeasures(Map<ChannelInfo, AudioMeter> measures) { }

	public void setStemFile(IntFunction<String> file) { this.stemFileSupplier = file; }

	public void setOutputFile(String file) { setOutputFile(() -> file); }
	public void setOutputFile(Supplier<String> file) { this.outputFileSupplier = file; }

	protected File getOutputFile() { return outputFile; }

	protected Collection<File> getStemFiles() { return stemFiles.values(); }

	public Consumer<WaveDetails> getWaveDetailsProcessor() { return detailsProcessor; }

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

	public static void setStandardDuration(int sec) {
		standardDurationSeconds = sec;
		standardDurationFrames = standardDurationSeconds * OutputLine.sampleRate;
	}

	public static File getAuxFile(File file, String suffix) {
		if (file == null) return null;
		return new File(file.getParentFile(), file.getName().replace(".wav", "." + suffix));
	}
}
