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
		return layerChoiceChromosome.length();
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

		IntStream.range(0, getLayerCount()).forEach(i -> layer(layerChoiceChromosome.valueAt(i)));
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

	public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
								  ChannelInfo.Voicing voicing,
								  ChannelInfo.StereoChannel audioChannel) {
		return OperationWithInfo.of(new OperationMetadata("PatternLayerManager.sum", "PatternLayerManager.sum"), () -> () -> {
			Map<NoteAudioChoice, List<PatternElement>> elements = getAllElementsByChoice(0.0, duration);
			if (elements.isEmpty()) {
				if (!roots.isEmpty() && enableWarnings)
					warn("No pattern elements (channel " + channel + ")");
				return;
			}

			AudioSceneContext ctx = context.get();

			// TODO  What about when duration is longer than measures?
			// TODO  This results in count being 0, and nothing being output
			int count = (int) (ctx.getMeasures() / duration);
			if (ctx.getMeasures() / duration - count > 0.0001) {
				warn("Pattern duration does not divide measures; there will be gaps");
			}

			IntStream.range(0, count).forEach(i -> {
				ChannelSection section = ctx.getSection(i * duration);

				if (section == null) {
					warn("No ChannelSection at measure " + i);
				} else {
					double active = activeSelection.apply(layerParams.get(layerParams.size() - 1), section.getPosition()) + ctx.getActivityBias();
					if (active < 0) return;
				}

				double offset = i * duration;
				elements.keySet().forEach(choice -> {
					NoteAudioContext audioContext =
							new NoteAudioContext(voicing, audioChannel,
									choice.getValidPatternNotes(),
									this::nextNotePosition);

					if (destination.get(new ChannelInfo(voicing, audioChannel)) != ctx.getDestination()) {
						throw new IllegalArgumentException();
					}

					render(ctx, audioContext, elements.get(choice), melodic, offset);
				});
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
