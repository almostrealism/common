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

package org.almostrealism.audio.pattern;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.notes.NoteAudioSource;
import org.almostrealism.audio.notes.NoteSourceProvider;
import org.almostrealism.audio.notes.TreeNoteSource;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedChromosome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Top-level manager for the pattern system in an audio scene.
 *
 * <p>{@code PatternSystemManager} coordinates multiple {@link PatternLayerManager}
 * instances to create a complete musical arrangement. It manages note audio choices,
 * volume control, and renders patterns to destination buffers.</p>
 *
 * <h2>Architecture</h2>
 *
 * <p>The pattern system has a hierarchical structure:</p>
 * <pre>
 * PatternSystemManager
 *     |
 *     +-- NoteAudioChoice[] (available audio samples)
 *     |
 *     +-- PatternLayerManager[] (one per pattern slot)
 *         |
 *         +-- PatternLayer (hierarchical layers)
 *             |
 *             +-- PatternElement[] (musical events)
 * </pre>
 *
 * <h2>Pattern Rendering</h2>
 *
 * <p>The key method {@link #sum(java.util.function.Supplier, ChannelInfo)} renders
 * all patterns for a channel to a destination buffer. The rendering process:</p>
 * <ol>
 *   <li>Updates destination buffers for each pattern manager</li>
 *   <li>Iterates through all patterns assigned to the channel</li>
 *   <li>Calls each pattern's sum method to render elements</li>
 *   <li>Applies auto-volume normalization (if enabled)</li>
 * </ol>
 *
 * <h2>Genetic Algorithm Integration</h2>
 *
 * <p>PatternSystemManager integrates with the heredity module for evolutionary
 * optimization. Each pattern is associated with a {@link ProjectedChromosome}
 * that controls its parameters.</p>
 *
 * <h2>Configuration</h2>
 *
 * <p>Manually configured parameters (not in genome):</p>
 * <ul>
 *   <li>Number of layers per pattern</li>
 *   <li>Melodic vs. percussive mode</li>
 *   <li>Duration of each layer</li>
 * </ul>
 *
 * <h2>Real-Time Considerations</h2>
 *
 * <p><strong>Current Limitation:</strong> The {@link #sum} method renders the entire
 * arrangement at once. For real-time streaming, frame range parameters would need
 * to be added. See {@code REALTIME_PATTERNS.md} for the proposed solution.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create manager with chromosomes
 * PatternSystemManager patterns = new PatternSystemManager(choices, chromosomes);
 * patterns.init();
 *
 * // Add patterns for each channel
 * patterns.addPattern(0, 1.0, false);  // Channel 0, 1 measure, percussive
 * patterns.addPattern(2, 4.0, true);   // Channel 2, 4 measures, melodic
 *
 * // Render patterns
 * Supplier<Runnable> render = patterns.sum(contextSupplier, channel);
 * render.get().run();
 * }</pre>
 *
 * @see PatternLayerManager
 * @see NoteAudioChoice
 * @see PatternElement
 *
 * @author Michael Murray
 */
public class PatternSystemManager implements NoteSourceProvider, CodeFeatures {
	public static final boolean enableAutoVolume = true;
	public static final boolean enableLazyDestination = false;
	public static boolean enableVerbose = false;
	public static boolean enableWarnings = true;

	private final List<NoteAudioChoice> choices;
	private final List<PatternLayerManager> patterns;
	private final List<ProjectedChromosome> chromosomes;

	private PackedCollection volume;
	private PackedCollection destination;

	public PatternSystemManager( List<ProjectedChromosome> chromosomes) {
		this(new ArrayList<>(), chromosomes);
	}

	public PatternSystemManager(List<NoteAudioChoice> choices,  List<ProjectedChromosome> chromosomes) {
		this.choices = choices;
		this.patterns = new ArrayList<>();
		this.chromosomes = chromosomes;
	}

	public void init() {
		volume = new PackedCollection(1);
		volume.setMem(0, 1.0);
	}

	@Override
	public List<NoteAudioSource> getSource(String id) {
		return choices.stream()
				.filter(f -> Objects.equals(id, f.getId()))
				.map(NoteAudioChoice::getSources)
				.findFirst().orElse(null);
	}

	public List<NoteAudioChoice> getChoices() {
		return choices;
	}

	public List<NoteAudioSource> getAllSources() {
		return getChoices()
				.stream()
				.flatMap(c -> c.getSources().stream()).toList();
	}

	public List<PatternLayerManager> getPatterns() { return patterns; }

	public Map<NoteAudioChoice, List<PatternElement>> getPatternElements(double start, double end) {
		Map<NoteAudioChoice, List<PatternElement>> elements = new HashMap<>();

		patterns.forEach(layer -> {
			layer.getAllElementsByChoice(start, end).forEach((k, v) ->
					elements.computeIfAbsent(k, key -> new ArrayList<>()).addAll(v));
		});

		return elements;
	}

	public void setVolume(double volume) {
		this.volume.setMem(0, volume);
	}

	public Settings getSettings() {
		Settings settings = new Settings();
		settings.getPatterns().addAll(patterns.stream().map(PatternLayerManager::getSettings).toList());
		return settings;
	}

	public void setSettings(Settings settings) {
		patterns.clear();
		settings.getPatterns().forEach(s -> addPattern(s.getChannel(), s.getDuration(), s.isMelodic()).setSettings(s));
	}

	public void refreshParameters() {
		patterns.stream().collect(Collectors.groupingBy(PatternLayerManager::getChannel))
				.forEach((c, channelPatterns) -> {
			long activeLayers = channelPatterns.stream()
					.filter(p -> p.getLayerCount() > 0)
					.count();
			double adj = Math.exp(-0.25 * (activeLayers - 4));
			channelPatterns.forEach(p -> p.setSeedBiasAdjustment(adj));
		});
		patterns.forEach(PatternLayerManager::refresh);
	}

	public void setTuning(KeyboardTuning tuning) {
		choices.forEach(c -> c.setTuning(tuning));
	}

	public void setTree(FileWaveDataProviderTree<?> root) {
		setTree(root, null);
	}

	public void setTree(FileWaveDataProviderTree<?> root, DoubleConsumer progress) {
		List<NoteAudioSource> sources = getAllSources();

		if (progress != null && !sources.isEmpty())
			progress.accept(0.0);

		IntStream.range(0, sources.size()).forEach(i -> {
			NoteAudioSource s = sources.get(i);

			if (s instanceof TreeNoteSource)
				((TreeNoteSource) s).setTree(root);

			if (progress != null)
				progress.accept((double) (i + 1) / sources.size());
		});
	}

	public PatternLayerManager addPattern(int channel, double measures, boolean melodic) {
		PatternLayerManager pattern =
				new PatternLayerManager(choices,
						chromosomes.get(patterns.size()),
						channel, measures, melodic);
		patterns.add(pattern);
		return pattern;
	}

	public void clear() {
		patterns.clear();
	}

	/**
	 * Renders all patterns for the specified channel to the destination buffer.
	 *
	 * <p>This is the main entry point for pattern rendering. It creates an operation
	 * that iterates through all patterns assigned to the channel and sums their
	 * audio output to the destination buffer from the context.</p>
	 *
	 * <h3>Rendering Steps</h3>
	 * <ol>
	 *   <li>Update destination buffers from context</li>
	 *   <li>For each pattern assigned to channel:
	 *       <ul>
	 *         <li>Call {@link PatternLayerManager#sum} to render elements</li>
	 *       </ul>
	 *   </li>
	 *   <li>If auto-volume enabled, normalize to target level</li>
	 * </ol>
	 *
	 * <h3>Auto-Volume Normalization</h3>
	 * <p>When {@link #enableAutoVolume} is true, the maximum amplitude of the
	 * destination buffer is computed and volume is adjusted to reach a target
	 * level of 0.8. This requires the full buffer to be available, which is
	 * incompatible with real-time rendering.</p>
	 *
	 * <h3>Real-Time Limitation</h3>
	 * <p>This method processes the entire arrangement duration. For real-time
	 * rendering, a frame-range aware version would be needed:</p>
	 * <pre>{@code
	 * // Proposed signature for real-time
	 * sum(context, channel, startFrame, frameCount)
	 * }</pre>
	 *
	 * @param context Supplier for the AudioSceneContext containing destination buffer
	 * @param channel Target channel (index, voicing, audio channel)
	 * @return Operation that renders all patterns for the channel
	 *
	 * @see PatternLayerManager#sum(Supplier, ChannelInfo.Voicing, ChannelInfo.StereoChannel)
	 */
	public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
								  ChannelInfo channel) {
		OperationList updateDestinations = new OperationList("PatternSystemManager Update Destinations");
		updateDestinations.add(() -> () -> this.destination = context.get().getDestination());
		updateDestinations.add(() -> () ->
				IntStream.range(0, patterns.size()).forEach(i ->
						patterns.get(i).updateDestination(context.get())));

		OperationList op = new OperationList("PatternSystemManager Sum");

		if (enableLazyDestination) {
			op.add(updateDestinations);
		} else {
			updateDestinations.get().run();
		}

		List<Integer> patternsForChannel = IntStream.range(0, patterns.size())
				.filter(i -> channel.getPatternChannel() == patterns.get(i).getChannel())
				.boxed().toList();

		if (patternsForChannel.isEmpty()) {
			if (enableWarnings) warn("No patterns");
			return op;
		}

		patternsForChannel.forEach(i -> {
			op.add(patterns.get(i).sum(context, channel.getVoicing(), channel.getAudioChannel()));
		});

		if (enableAutoVolume) {
			if (enableLazyDestination) {
				throw new UnsupportedOperationException("Lazy destination not compatible with computing max");
			}

			Producer<PackedCollection> max = (Producer) cp(destination).traverse(0).max().isolate();
			CollectionProducer auto = greaterThan(max, c(0.0), c(0.8).divide(max), c(1.0));
			op.add(a(1, p(volume), auto));
		}

		op.add(() -> () -> {
			AudioProcessingUtils.getSum().adjustVolume(context.get().getDestination(), volume);
		});

		return op;
	}

	public static class Settings {
		private List<PatternLayerManager.Settings> patterns = new ArrayList<>();

		public List<PatternLayerManager.Settings> getPatterns() { return patterns; }
		public void setPatterns(List<PatternLayerManager.Settings> patterns) { this.patterns = patterns; }

		public static Settings defaultSettings(int channels, int patternsPerChannel,
											   IntUnaryOperator activePatterns,
											   IntUnaryOperator layersPerPattern,
											   IntToDoubleFunction minLayerScale,
											   IntUnaryOperator duration) {
			Settings settings = new Settings();
			IntStream.range(0, channels).forEach(c -> IntStream.range(0, patternsPerChannel).forEach(p -> {
				PatternLayerManager.Settings pattern = new PatternLayerManager.Settings();
				pattern.setChannel(c);
				pattern.setDuration(duration.applyAsInt(c));
				pattern.setMelodic(c > 1 && c != 5);
				pattern.setScaleTraversalStrategy((c == 2 || c == 4) ?
						ScaleTraversalStrategy.SEQUENCE :
						ScaleTraversalStrategy.CHORD);
				pattern.setScaleTraversalDepth(pattern.isMelodic() ? 5 : 1);
				pattern.setMinLayerScale(minLayerScale.applyAsDouble(c));
				pattern.setFactorySelection(ParameterFunction.random());
				pattern.setActiveSelection(ParameterizedPositionFunction.random());

				if (p < activePatterns.applyAsInt(c)) {
					pattern.setLayerCount(layersPerPattern.applyAsInt(c));
				}

				settings.getPatterns().add(pattern);
			}));
			return settings;
		}
	}
}
