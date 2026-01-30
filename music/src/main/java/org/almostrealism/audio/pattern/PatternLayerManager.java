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

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationWithInfo;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.arrange.ChannelSection;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.HeredityFeatures;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Manages a single pattern with hierarchical layer structure.
 *
 * <p>{@code PatternLayerManager} handles the creation and rendering of a multi-layer
 * musical pattern. Each pattern can have up to 32 layers that build upon each other,
 * with each successive layer operating at half the granularity of the previous.</p>
 *
 * <h2>Layer Hierarchy</h2>
 *
 * <p>Patterns are built as a tree of layers:</p>
 * <pre>
 * Layer 0 (Root)  - scale = 1.0 (whole measures)
 *     |
 *     +-- Layer 1 - scale = 0.5 (half measures)
 *         |
 *         +-- Layer 2 - scale = 0.25 (quarter measures)
 *             |
 *             +-- Layer 3 - scale = 0.125 (eighth measures)
 *                 ...
 * </pre>
 *
 * <p>Each layer contains {@link PatternElement}s that define musical events at
 * specific positions within the pattern duration.</p>
 *
 * <h2>Melodic vs. Percussive Modes</h2>
 *
 * <p>Patterns operate in one of two modes:</p>
 * <ul>
 *   <li><strong>Percussive:</strong> Uses percussive note choices, no scale traversal</li>
 *   <li><strong>Melodic:</strong> Uses melodic note choices with scale traversal</li>
 * </ul>
 *
 * <h2>Scale Traversal</h2>
 *
 * <p>Melodic patterns use a {@link ScaleTraversalStrategy} to navigate through scales:</p>
 * <ul>
 *   <li>{@code CHORD} - Stays on chord tones</li>
 *   <li>{@code SEQUENCE} - Follows sequential scale patterns</li>
 * </ul>
 *
 * <h2>Pattern Rendering</h2>
 *
 * <p>The {@link #sum} method renders all pattern elements to the destination buffer.
 * For each pattern repetition within the arrangement:</p>
 * <ol>
 *   <li>Check section activity (skip if section inactive)</li>
 *   <li>Get all elements by note choice</li>
 *   <li>Render each element's audio to the destination</li>
 * </ol>
 *
 * <h2>Genetic Algorithm Integration</h2>
 *
 * <p>Pattern parameters are controlled by chromosomes:</p>
 * <ul>
 *   <li>{@code layerChoiceChromosome} - Controls note selection per layer</li>
 *   <li>{@code envelopeAutomationChromosome} - Controls automation per layer</li>
 * </ul>
 *
 * <h2>Real-Time Considerations</h2>
 *
 * <p><strong>Current Limitation:</strong> The {@link #sum} method renders all pattern
 * repetitions at once. For real-time rendering, frame range parameters would need
 * to be added to render only elements within the current buffer window.</p>
 *
 * @see PatternSystemManager
 * @see PatternLayer
 * @see PatternElement
 * @see PatternFeatures
 *
 * @author Michael Murray
 */
public class PatternLayerManager implements PatternFeatures, HeredityFeatures {
	public static int AUTOMATION_GENE_LENGTH = 6;
	public static int MAX_LAYERS = 32;

	public static boolean enableWarnings = SystemUtils.isEnabled("AR_PATTERN_WARNINGS").orElse(true);
	public static boolean enableLogging = SystemUtils.isEnabled("AR_PATTERN_LOGGING").orElse(false);

	private int channel;
	private double duration;
	private double scale;

	private boolean melodic;
	private int scaleTraversalDepth;
	private double minLayerScale;
	private ScaleTraversalStrategy scaleTraversalStrategy;

	private double seedBiasAdjustment;

	private final Supplier<List<NoteAudioChoice>> percChoices;
	private final Supplier<List<NoteAudioChoice>> melodicChoices;
	private Chromosome<PackedCollection> layerChoiceChromosome;
	private Chromosome<PackedCollection> envelopeAutomationChromosome;

	private ParameterFunction noteSelection;
	private ParameterizedPositionFunction activeSelection;
	private PatternElementFactory elementFactory;

	private final List<PatternLayer> roots;
	private final List<ParameterSet> layerParams;
	private int layerCount;

	private Map<ChannelInfo, PackedCollection> destination;
	private final NoteAudioCache noteAudioCache = new NoteAudioCache();

	public PatternLayerManager(List<NoteAudioChoice> choices,
							   ProjectedChromosome chromosome,
							   int channel, double measures, boolean melodic) {
		this(NoteAudioChoice.choices(choices, false), NoteAudioChoice.choices(choices, true),
				chromosome, channel, measures, melodic);
	}

	public PatternLayerManager(Supplier<List<NoteAudioChoice>> percChoices,
							   Supplier<List<NoteAudioChoice>> melodicChoices,
							   ProjectedChromosome chromosome,
							   int channel, double measures, boolean melodic) {
		this.channel = channel;
		this.duration = measures;
		this.scale = 1.0;
		this.scaleTraversalStrategy = ScaleTraversalStrategy.CHORD;
		this.scaleTraversalDepth = 1;
		this.minLayerScale = 0.0625;
		this.seedBiasAdjustment = 1.0;
		setMelodic(melodic);

		this.percChoices = percChoices;
		this.melodicChoices = melodicChoices;
		this.roots = new ArrayList<>();
		this.layerParams = new ArrayList<>();
		init(chromosome);
	}

	public void init(ProjectedChromosome chromosome) {
		noteSelection = ParameterFunction.random();
		activeSelection = ParameterizedPositionFunction.random();
		elementFactory = new PatternElementFactory();

		layerChoiceChromosome = chromosome(IntStream.range(0, MAX_LAYERS)
				.mapToObj(i -> chromosome.addGene(3))
				.collect(Collectors.toList()));
		envelopeAutomationChromosome = chromosome(IntStream.range(0, MAX_LAYERS)
				.mapToObj(i -> chromosome.addGene(AUTOMATION_GENE_LENGTH))
				.collect(Collectors.toList()));
	}

	public Map<ChannelInfo, PackedCollection> getDestination() { return destination; }

	public void updateDestination(AudioSceneContext context) {
		if (context.getChannels() == null) return;

		if (destination == null) {
			destination = new HashMap<>();
		}

		context.getChannels().forEach(c -> {
			if (c.getPatternChannel() == channel) {
				destination.put(
						new ChannelInfo(c.getVoicing(), c.getAudioChannel()),
						context.getDestination());
			}
		});
	}

	public List<NoteAudioChoice> getChoices() {
		return melodic ? melodicChoices.get() : percChoices.get();
	}

	public Stream<NoteAudioChoice> choices() {
		return getChoices().stream()
				.filter(c -> c.getChannels() == null || c.getChannels().contains(channel))
				.filter(c -> scaleTraversalDepth <= c.getMaxScaleTraversalDepth());
	}

	public int getChannel() { return channel; }
	public void setChannel(int channel) { this.channel = channel; }

	public void setDuration(double measures) { duration = measures; }
	public double getDuration() { return duration; }

	public int getScaleTraversalDepth() { return scaleTraversalDepth; }
	public void setScaleTraversalDepth(int scaleTraversalDepth) { this.scaleTraversalDepth = scaleTraversalDepth; }

	public double getMinLayerScale() { return minLayerScale; }
	public void setMinLayerScale(double minLayerScale) { this.minLayerScale = minLayerScale; }

	public void setMelodic(boolean melodic) {
		this.melodic = melodic;
	}

	public boolean isMelodic() { return melodic; }

	public ScaleTraversalStrategy getScaleTraversalStrategy() {
		return scaleTraversalStrategy;
	}

	public void setScaleTraversalStrategy(ScaleTraversalStrategy scaleTraversalStrategy) {
		this.scaleTraversalStrategy = scaleTraversalStrategy;
	}

	public double getSeedBiasAdjustment() {
		return seedBiasAdjustment;
	}

	public void setSeedBiasAdjustment(double seedBiasAdjustment) {
		this.seedBiasAdjustment = seedBiasAdjustment;
	}

	public PatternElementFactory getElementFactory() {
		return elementFactory;
	}

	public void setElementFactory(PatternElementFactory elementFactory) {
		this.elementFactory = elementFactory;
	}

	public PatternLayerSeeds getSeeds(ParameterSet params) {
		List<PatternLayerSeeds> options = choices()
				.filter(NoteAudioChoice::isSeed)
				.filter(NoteAudioChoice::hasValidNotes)
				.map(choice -> choice.seeds(params, seedBiasAdjustment))
				.toList();

		if (options.isEmpty()) return null;

		double c = noteSelection.apply(params);
		if (c < 0) c = c + 1.0;
		return options.get((int) (options.size() * c));
	}

	public Map<NoteAudioChoice, List<PatternElement>> getAllElementsByChoice(double start, double end) {
		Map<NoteAudioChoice, List<PatternElement>> result = new HashMap<>();
		roots.forEach(l -> l.putAllElementsByChoice(result, start, end));
		return result;
	}

	public List<PatternElement> getAllElements(double start, double end) {
		return roots.stream()
				.map(l -> l.getAllElements(start, end))
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}

	public Settings getSettings() {
		Settings settings = new Settings();
		settings.setChannel(channel);
		settings.setDuration(duration);
		settings.setScaleTraversalStrategy(scaleTraversalStrategy);
		settings.setScaleTraversalDepth(scaleTraversalDepth);
		settings.setMinLayerScale(minLayerScale);
		settings.setMelodic(melodic);
		settings.setFactorySelection(noteSelection);
		settings.setActiveSelection(activeSelection);
		settings.setElementFactory(elementFactory);
		settings.setLayerCount(layerCount);
		return settings;
	}

	public void setSettings(Settings settings) {
		channel = settings.getChannel();
		duration = settings.getDuration();
		scaleTraversalStrategy = settings.getScaleTraversalStrategy();
		scaleTraversalDepth = settings.getScaleTraversalDepth();
		minLayerScale = settings.getMinLayerScale();
		melodic = settings.isMelodic();

		if (settings.getFactorySelection() != null)
			noteSelection = settings.getFactorySelection();

		if (settings.getActiveSelection() != null)
			activeSelection = settings.getActiveSelection();

		if (settings.getElementFactory() != null)
			elementFactory = settings.getElementFactory();

		setLayerCount(settings.getLayerCount());
	}

	protected void decrement() { scale *= 2; }
	protected void increment() {
		scale /= 2;
	}

	public int rootCount() { return roots.size(); }

	public int depth() {
		if (rootCount() <= 0) return 0;
		return roots.stream()
				.map(PatternLayer::depth)
				.max(Integer::compareTo).orElse(0);
	}

	public int getLayerCount() {
		return layerCount;
	}

	public void setLayerCount(int count) {
		if (count < 0) throw new IllegalArgumentException(count + " is not a valid number of layers");
		if (count == getLayerCount()) return;

		this.layerCount = count;
		refresh();
	}

	public void layer(Gene<PackedCollection> gene) {
		layer(ParameterSet.fromGene(gene));
	}

	protected void layer(ParameterSet params) {
		Gene<PackedCollection> automationGene = envelopeAutomationChromosome.valueAt(depth());
		PackedCollection automationParams =
				PackedCollection.factory().apply(AUTOMATION_GENE_LENGTH).fill(pos ->
						automationGene.valueAt(pos[0]).getResultant(null).evaluate().toDouble());

		if (rootCount() <= 0) {
			PatternLayerSeeds seeds = getSeeds(params);

			if (seeds != null) {
				seeds.generator(getElementFactory(), 0, duration,
							scaleTraversalStrategy, scaleTraversalDepth, minLayerScale)
						.forEach(roots::add);

				scale = seeds.getScale(duration, minLayerScale);
			}

			if (rootCount() <= 0) {
				roots.add(new PatternLayer());
			}

			roots.forEach(layer -> layer.setAutomationParameters(automationParams));
		} else {
			if (enableLogging) {
				System.out.println();
				log(roots.size() +
						" roots (scale = " + scale + ", duration = " + duration + ")");
			}

			roots.forEach(layer -> {
				NoteAudioChoice choice = scale >= minLayerScale ? choose(scale, params) : null;
				PatternLayer next;

				if (choice != null) {
					next = choice.apply(getElementFactory(), layer.getAllElements(0, 2 * duration), scale,
								scaleTraversalStrategy, scaleTraversalDepth, params);
					next.trim(2 * duration);
				} else {
					next = new PatternLayer();
				}

				next.setAutomationParameters(automationParams);

				if (enableLogging) {
					log(layer.getAllElements(0, duration).size() +
										" elements --> " + next.getElements().size() + " elements");
				}

				layer.getTail().setChild(next);
			});
		}

		layerParams.add(params);
		increment();
	}

	public void removeLayer() {
		layerParams.remove(layerParams.size() - 1);
		decrement();

		if (depth() <= 0) return;
		if (depth() <= 1) {
			roots.clear();
			return;
		}

		roots.forEach(layer -> layer.getLastParent().setChild(null));
	}

	public void clear() {
		while (depth() > 0) removeLayer();
	}

	public void refresh() {
		clear();
		if (layerParams.size() != depth())
			throw new IllegalStateException("Layer count mismatch (" + layerParams.size() +
											" != " + layerChoiceChromosome.length() + ")");

		IntStream.range(0, layerCount).forEach(i -> layer(layerChoiceChromosome.valueAt(i)));
	}

	public NoteAudioChoice choose(double scale, ParameterSet params) {
		List<NoteAudioChoice> options = choices()
				.filter(c -> scale >= c.getMinScale())
				.filter(c -> scale <= c.getMaxScale())
				.filter(NoteAudioChoice::hasValidNotes)
				.collect(Collectors.toList());

		if (options.isEmpty()) return null;

		double c = noteSelection.apply(params);
		if (c < 0) c = c + 1.0;
		return options.get((int) (options.size() * c));
	}

	/**
	 * Renders all pattern elements to the destination buffer (full arrangement).
	 *
	 * <p>Delegates to the shared {@link #sumInternal} implementation with
	 * {@code startFrame=0} and {@code frameCount=totalFrames}, rendering all
	 * pattern repetitions across the entire arrangement.</p>
	 *
	 * @param context Supplier for AudioSceneContext with destination buffer
	 * @param voicing Target voicing (MAIN or WET)
	 * @param audioChannel Target stereo channel (LEFT or RIGHT)
	 * @return Operation that renders all elements for this pattern
	 *
	 * @see PatternFeatures#render
	 */
	public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
								  ChannelInfo.Voicing voicing,
								  ChannelInfo.StereoChannel audioChannel) {
		return OperationWithInfo.of(
				new OperationMetadata("PatternLayerManager.sum", "PatternLayerManager.sum"),
				() -> () -> {
					AudioSceneContext ctx = context.get();
					sumInternal(ctx, voicing, audioChannel, 0, ctx.getFrames(), null);
				});
	}

	/**
	 * Renders pattern elements for a specific frame range.
	 *
	 * <p>Delegates to the shared {@link #sumInternal} implementation, rendering
	 * only the pattern repetitions that overlap with the specified frame range.
	 * Uses the {@link NoteAudioCache} to avoid re-evaluating notes that span
	 * multiple buffers.</p>
	 *
	 * @param context Supplier for AudioSceneContext with destination buffer
	 * @param voicing Target voicing (MAIN or WET)
	 * @param audioChannel Target stereo channel (LEFT or RIGHT)
	 * @param startFrame Starting frame (absolute position)
	 * @param frameCount Number of frames to render
	 * @return Operation that renders elements within the frame range
	 *
	 * @see PatternFeatures#render
	 */
	public Supplier<Runnable> sum(Supplier<? extends AudioSceneContext> context,
								  ChannelInfo.Voicing voicing,
								  ChannelInfo.StereoChannel audioChannel,
								  int startFrame,
								  int frameCount) {
		return OperationWithInfo.of(
				new OperationMetadata("PatternLayerManager.sum",
						String.format("PatternLayerManager.sum [%d:%d]", startFrame, startFrame + frameCount)),
				() -> () -> {
					AudioSceneContext ctx = context.get();
					noteAudioCache.evictBefore(startFrame);
					sumInternal(ctx, voicing, audioChannel, startFrame, frameCount, noteAudioCache);
				});
	}

	/**
	 * Shared rendering implementation for both full and frame-range modes.
	 *
	 * <p>Converts the frame range to a measure range, determines which pattern
	 * repetitions overlap, checks section activity for each, and calls the unified
	 * {@link PatternFeatures#render} for each choice's elements.</p>
	 *
	 * @param ctx the audio scene context
	 * @param voicing target voicing
	 * @param audioChannel target stereo channel
	 * @param startFrame starting frame (0 for full render)
	 * @param frameCount number of frames (total frames for full render)
	 * @param cache optional note audio cache (null for full render)
	 */
	private void sumInternal(AudioSceneContext ctx,
							 ChannelInfo.Voicing voicing,
							 ChannelInfo.StereoChannel audioChannel,
							 int startFrame, int frameCount,
							 NoteAudioCache cache) {
		Map<NoteAudioChoice, List<PatternElement>> elements = getAllElementsByChoice(0.0, duration);
		if (elements.isEmpty()) {
			if (!roots.isEmpty() && enableWarnings)
				warn("No pattern elements (channel " + channel + ")");
			return;
		}

		int totalRepetitions = (int) (ctx.getMeasures() / duration);
		if (totalRepetitions == 0) {
			if (enableWarnings) warn("Pattern duration longer than arrangement");
			return;
		}

		if (ctx.getMeasures() / duration - totalRepetitions > 0.0001) {
			warn("Pattern duration does not divide measures; there will be gaps");
		}

		// Convert frame range to measure range to determine overlapping repetitions
		double framesPerMeasure = (double) ctx.getFrames() / ctx.getMeasures();
		double startMeasure = startFrame / framesPerMeasure;
		double endMeasure = (startFrame + frameCount) / framesPerMeasure;

		int firstRepetition = Math.max(0, (int) Math.floor(startMeasure / duration));
		int lastRepetition = Math.min(totalRepetitions, (int) Math.ceil(endMeasure / duration));

		IntStream.range(firstRepetition, lastRepetition).forEach(rep -> {
			double repStart = rep * duration;

			// Check if this repetition overlaps with frame range
			int repStartFrame = ctx.frameForPosition(repStart);
			int repEndFrame = ctx.frameForPosition(repStart + duration);
			if (repEndFrame <= startFrame || repStartFrame >= startFrame + frameCount) return;

			// Check section activity
			ChannelSection section = ctx.getSection(repStart);
			if (section == null) {
				if (enableWarnings) warn("No ChannelSection at measure " + repStart);
			} else {
				double active = activeSelection.apply(
						layerParams.get(layerParams.size() - 1),
						section.getPosition()) + ctx.getActivityBias();
				if (active < 0) return;
			}

			// Render each choice's elements for this repetition
			elements.keySet().forEach(choice -> {
				NoteAudioContext audioContext = new NoteAudioContext(
						voicing, audioChannel,
						choice.getValidPatternNotes(),
						this::nextNotePosition);

				if (destination.get(new ChannelInfo(voicing, audioChannel)) != ctx.getDestination()) {
					throw new IllegalArgumentException("Destination buffer mismatch");
				}

				render(ctx, audioContext, elements.get(choice), melodic,
						repStart, startFrame, frameCount, cache);
			});
		});
	}

	public double nextNotePosition(double position) {
		return getAllElements(position, duration).stream()
				.map(PatternElement::getPositions)
				.flatMap(List::stream)
				.filter(p -> p > position)
				.mapToDouble(p -> p)
				.min().orElse(duration);
	}

	public static class Settings {
		private int channel;
		private double duration;
		private ScaleTraversalStrategy scaleTraversalStrategy;
		private int scaleTraversalDepth;
		private double minLayerScale;
		private boolean melodic;
		private int layerCount;

		private ParameterFunction factorySelection;
		private ParameterizedPositionFunction activeSelection;
		private PatternElementFactory elementFactory;

		public int getChannel() { return channel; }
		public void setChannel(int channel) { this.channel = channel; }

		public double getDuration() { return duration; }
		public void setDuration(double duration) { this.duration = duration; }

		public ScaleTraversalStrategy getScaleTraversalStrategy() { return scaleTraversalStrategy; }
		public void setScaleTraversalStrategy(ScaleTraversalStrategy scaleTraversalStrategy) { this.scaleTraversalStrategy = scaleTraversalStrategy; }

		public int getScaleTraversalDepth() { return scaleTraversalDepth; }
		public void setScaleTraversalDepth(int scaleTraversalDepth) { this.scaleTraversalDepth = scaleTraversalDepth; }

		public double getMinLayerScale() { return minLayerScale; }
		public void setMinLayerScale(double minLayerScale) { this.minLayerScale = minLayerScale; }

		public boolean isMelodic() { return melodic; }
		public void setMelodic(boolean melodic) { this.melodic = melodic; }

		public ParameterFunction getFactorySelection() { return factorySelection; }
		public void setFactorySelection(ParameterFunction factorySelection) { this.factorySelection = factorySelection; }

		public ParameterizedPositionFunction getActiveSelection() { return activeSelection; }
		public void setActiveSelection(ParameterizedPositionFunction activeSelection) { this.activeSelection = activeSelection; }

		public PatternElementFactory getElementFactory() { return elementFactory; }
		public void setElementFactory(PatternElementFactory elementFactory) { this.elementFactory = elementFactory; }

		public void setLayers(List<ParameterSet> layers) {
			if (layers != null) {
				this.layerCount = layers.size();
			}
		}

		public void setLayerCount(int layerCount) { this.layerCount = layerCount; }
		public int getLayerCount() { return layerCount; }
	}
}
