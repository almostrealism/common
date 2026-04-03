/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.music.pattern;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationWithInfo;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.arrange.ChannelSection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioContext;
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
import java.util.function.IntSupplier;
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
	/** Number of genes in the envelope automation chromosome per layer. */
	public static int AUTOMATION_GENE_LENGTH = 6;
	/** Maximum number of layers supported per pattern. */
	public static int MAX_LAYERS = 32;

	/** Whether warning log messages are enabled. */
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_PATTERN_WARNINGS").orElse(false);
	/** Whether verbose log messages are enabled. */
	public static boolean enableLogging = SystemUtils.isEnabled("AR_PATTERN_LOGGING").orElse(false);

	/** The audio channel index for this pattern. */
	private int channel;
	/** The duration of this pattern in measures. */
	private double duration;
	/** The current layer scale (updated during layer generation). */
	private double scale;

	/** Whether this pattern is melodic (true) or percussive (false). */
	private boolean melodic;
	/** The depth of scale traversal for melodic patterns. */
	private int scaleTraversalDepth;
	/** The minimum layer scale in measures. */
	private double minLayerScale;
	/** The scale traversal strategy (CHORD or SEQUENCE). */
	private ScaleTraversalStrategy scaleTraversalStrategy;

	/** Adjustment factor applied to seed bias based on active layer count. */
	private double seedBiasAdjustment;

	/** Supplier of percussive note audio choices. */
	private final Supplier<List<NoteAudioChoice>> percChoices;
	/** Supplier of melodic note audio choices. */
	private final Supplier<List<NoteAudioChoice>> melodicChoices;
	/** Chromosome controlling note selection per layer. */
	private Chromosome<PackedCollection> layerChoiceChromosome;
	/** Chromosome controlling envelope automation per layer. */
	private Chromosome<PackedCollection> envelopeAutomationChromosome;

	/** Parameter function used to select which note choice to use. */
	private ParameterFunction noteSelection;
	/** Position function used to determine section activity. */
	private ParameterizedPositionFunction activeSelection;
	/** Factory used to generate pattern elements. */
	private PatternElementFactory elementFactory;

	/** The root pattern layers forming the first level of the hierarchy. */
	private final List<PatternLayer> roots;
	/** The parameter sets for each active layer. */
	private final List<ParameterSet> layerParams;
	/** The current number of active layers. */
	private int layerCount;

	/** Map from channel info to destination buffer for each channel. */
	private Map<ChannelInfo, PackedCollection> destination;
	/** Cache for note audio across buffer ticks. */
	private final NoteAudioCache noteAudioCache = new NoteAudioCache();

	/**
	 * Creates a {@code PatternLayerManager} from a flat list of choices.
	 *
	 * @param choices    the available note audio choices
	 * @param chromosome the projected chromosome for parameter generation
	 * @param channel    the channel index
	 * @param measures   the pattern duration in measures
	 * @param melodic    whether the pattern is melodic
	 */
	public PatternLayerManager(List<NoteAudioChoice> choices,
							   ProjectedChromosome chromosome,
							   int channel, double measures, boolean melodic) {
		this(NoteAudioChoice.choices(choices, false), NoteAudioChoice.choices(choices, true),
				chromosome, channel, measures, melodic);
	}

	/**
	 * Creates a {@code PatternLayerManager} with separate suppliers for percussive and melodic choices.
	 *
	 * @param percChoices    supplier of percussive note audio choices
	 * @param melodicChoices supplier of melodic note audio choices
	 * @param chromosome     the projected chromosome for parameter generation
	 * @param channel        the channel index
	 * @param measures       the pattern duration in measures
	 * @param melodic        whether the pattern is melodic
	 */
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

	/**
	 * Initializes the chromosomes and element factory from the given projected chromosome.
	 *
	 * @param chromosome the projected chromosome
	 */
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

	/** Returns the destination buffer map, keyed by channel info. */
	public Map<ChannelInfo, PackedCollection> getDestination() { return destination; }

	/**
	 * Updates the destination buffer map from the given audio scene context.
	 *
	 * @param context the audio scene context containing channels and destination buffer
	 */
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

	/** Returns the active note audio choices based on this pattern's melodic mode. */
	public List<NoteAudioChoice> getChoices() {
		return melodic ? melodicChoices.get() : percChoices.get();
	}

	/**
	 * Returns a stream of note audio choices filtered by channel and scale traversal depth.
	 *
	 * @return stream of applicable note audio choices
	 */
	public Stream<NoteAudioChoice> choices() {
		return getChoices().stream()
				.filter(c -> c.getChannels() == null || c.getChannels().contains(channel))
				.filter(c -> scaleTraversalDepth <= c.getMaxScaleTraversalDepth());
	}

	/** Returns the channel index for this pattern. */
	public int getChannel() { return channel; }
	/** Sets the channel index for this pattern. */
	public void setChannel(int channel) { this.channel = channel; }

	/** Sets the pattern duration in measures. */
	public void setDuration(double measures) { duration = measures; }
	/** Returns the pattern duration in measures. */
	public double getDuration() { return duration; }

	/** Returns the scale traversal depth for melodic patterns. */
	public int getScaleTraversalDepth() { return scaleTraversalDepth; }
	/** Sets the scale traversal depth for melodic patterns. */
	public void setScaleTraversalDepth(int scaleTraversalDepth) { this.scaleTraversalDepth = scaleTraversalDepth; }

	/** Returns the minimum layer scale in measures. */
	public double getMinLayerScale() { return minLayerScale; }
	/** Sets the minimum layer scale in measures. */
	public void setMinLayerScale(double minLayerScale) { this.minLayerScale = minLayerScale; }

	/** Sets whether this pattern is melodic. */
	public void setMelodic(boolean melodic) {
		this.melodic = melodic;
	}

	/** Returns whether this pattern is melodic. */
	public boolean isMelodic() { return melodic; }

	/** Returns the scale traversal strategy (CHORD or SEQUENCE). */
	public ScaleTraversalStrategy getScaleTraversalStrategy() {
		return scaleTraversalStrategy;
	}

	/** Sets the scale traversal strategy (CHORD or SEQUENCE). */
	public void setScaleTraversalStrategy(ScaleTraversalStrategy scaleTraversalStrategy) {
		this.scaleTraversalStrategy = scaleTraversalStrategy;
	}

	/** Returns the seed bias adjustment factor. */
	public double getSeedBiasAdjustment() {
		return seedBiasAdjustment;
	}

	/** Sets the seed bias adjustment factor. */
	public void setSeedBiasAdjustment(double seedBiasAdjustment) {
		this.seedBiasAdjustment = seedBiasAdjustment;
	}

	/** Returns the pattern element factory. */
	public PatternElementFactory getElementFactory() {
		return elementFactory;
	}

	/** Sets the pattern element factory. */
	public void setElementFactory(PatternElementFactory elementFactory) {
		this.elementFactory = elementFactory;
	}

	/**
	 * Selects a seed {@link PatternLayerSeeds} for the given parameter set.
	 *
	 * @param params the parameter set
	 * @return the selected seeds, or null if no valid seed choices exist
	 */
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

	/**
	 * Returns all pattern elements in {@code [start, end)}, grouped by note audio choice.
	 *
	 * @param start inclusive start position in measures
	 * @param end   exclusive end position in measures
	 * @return map from choice to elements within the range
	 */
	public Map<NoteAudioChoice, List<PatternElement>> getAllElementsByChoice(double start, double end) {
		Map<NoteAudioChoice, List<PatternElement>> result = new HashMap<>();
		roots.forEach(l -> l.putAllElementsByChoice(result, start, end));
		return result;
	}

	/**
	 * Returns a flat list of all pattern elements in {@code [start, end)}.
	 *
	 * @param start inclusive start position in measures
	 * @param end   exclusive end position in measures
	 * @return list of elements within the range
	 */
	public List<PatternElement> getAllElements(double start, double end) {
		return roots.stream()
				.map(l -> l.getAllElements(start, end))
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}

	/**
	 * Converts all elements in this pattern's layer hierarchy to MIDI events.
	 *
	 * <p>Iterates over all elements grouped by {@link NoteAudioChoice},
	 * delegating to {@link PatternElement#toMidiEvents} for each element.
	 * Melodic patterns produce pitched MIDI events (instrument 0 by default);
	 * percussive patterns produce drum events (instrument 128).</p>
	 *
	 * @param context the audio scene context for timing and scale resolution
	 * @return list of MIDI note events from all elements, sorted by onset
	 */
	public List<MidiNoteEvent> toMidiEvents(AudioSceneContext context) {
		return toMidiEvents(context, 0.0);
	}

	/**
	 * Converts all elements in this pattern's layer hierarchy to MIDI events,
	 * offset by the given number of measures.
	 *
	 * @param context the audio scene context for timing and scale resolution
	 * @param offset  the pattern offset in measures (for repetitions)
	 * @return list of MIDI note events from all elements, sorted by onset
	 */
	public List<MidiNoteEvent> toMidiEvents(AudioSceneContext context, double offset) {
		int instrument = melodic ? 0 : MidiNoteEvent.DRUM_INSTRUMENT;

		List<MidiNoteEvent> events = getAllElements(0.0, duration).stream()
				.flatMap(element -> element.toMidiEvents(context, melodic, offset, instrument).stream())
				.sorted()
				.collect(Collectors.toList());

		return events;
	}

	/**
	 * Returns a {@link Settings} snapshot of this manager's current configuration.
	 *
	 * @return the current settings
	 */
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

	/**
	 * Applies a {@link Settings} snapshot to this manager.
	 *
	 * @param settings the settings to apply
	 */
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

	/** Decrements the scale (doubles it), moving to a coarser granularity. */
	protected void decrement() { scale *= 2; }
	/** Increments the scale (halves it), moving to a finer granularity. */
	protected void increment() {
		scale /= 2;
	}

	/** Returns the number of root pattern layers. */
	public int rootCount() { return roots.size(); }

	/**
	 * Returns the maximum depth of the layer hierarchy across all roots.
	 *
	 * @return the layer hierarchy depth, or 0 if there are no roots
	 */
	public int depth() {
		if (rootCount() <= 0) return 0;
		return roots.stream()
				.map(PatternLayer::depth)
				.max(Integer::compareTo).orElse(0);
	}

	/** Returns the current number of active layers. */
	public int getLayerCount() {
		return layerCount;
	}

	/**
	 * Sets the number of active layers, refreshing the pattern if the count changes.
	 *
	 * @param count the new layer count
	 * @throws IllegalArgumentException if count is negative
	 */
	public void setLayerCount(int count) {
		if (count < 0) throw new IllegalArgumentException(count + " is not a valid number of layers");
		if (count == getLayerCount()) return;

		this.layerCount = count;
		refresh();
	}

	/**
	 * Adds a layer using the given gene for parameter extraction.
	 *
	 * @param gene the gene providing layer parameters
	 */
	public void layer(Gene<PackedCollection> gene) {
		layer(ParameterSet.fromGene(gene));
	}

	/**
	 * Adds a layer using the given parameter set.
	 *
	 * @param params the parameter set for this layer
	 */
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

	/** Removes the most recently added layer from the hierarchy. */
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

	/** Removes all layers from the hierarchy. */
	public void clear() {
		while (depth() > 0) removeLayer();
	}

	/** Refreshes the pattern by clearing and regenerating all layers. */
	public void refresh() {
		clear();
		if (layerParams.size() != depth())
			throw new IllegalStateException("Layer count mismatch (" + layerParams.size() +
											" != " + layerChoiceChromosome.length() + ")");

		IntStream.range(0, layerCount).forEach(i -> layer(layerChoiceChromosome.valueAt(i)));
	}

	/**
	 * Chooses a note audio choice for the given scale and parameters.
	 *
	 * @param scale  the current layer scale in measures
	 * @param params the parameter set
	 * @return the selected choice, or null if no valid choices exist
	 */
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
	 * Renders pattern elements for a frame range into the destination buffer.
	 *
	 * <p>Delegates to the shared {@link #sumInternal} implementation, rendering
	 * only the pattern repetitions that overlap with the specified frame range.
	 * Uses the {@link NoteAudioCache} to avoid re-evaluating notes that span
	 * multiple buffers.</p>
	 *
	 * @param context Supplier for AudioSceneContext with destination buffer
	 * @param voicing Target voicing (MAIN or WET)
	 * @param audioChannel Target stereo channel (LEFT or RIGHT)
	 * @param startFrame Supplier for the starting frame (absolute position)
	 * @param frameCount Number of frames to render
	 * @return Operation that renders elements within the frame range
	 *
	 * @see PatternFeatures#render
	 */
	public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
								  ChannelInfo.Voicing voicing,
								  ChannelInfo.StereoChannel audioChannel,
								  IntSupplier startFrame,
								  int frameCount) {
		return OperationWithInfo.of(
				new OperationMetadata("PatternLayerManager.sum",
						"PatternLayerManager.sum"),
				() -> () -> {
					int frame = startFrame.getAsInt();
					AudioSceneContext ctx = context.get();
					if (frame == 0) {
						noteAudioCache.clear();
					} else {
						noteAudioCache.evictBefore(frame);
					}
					sumInternal(ctx, voicing, audioChannel, frame, frameCount, noteAudioCache);
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

		// Disable caching for full-arrangement renders (offline mode).
		// When frameCount covers the entire arrangement, all notes are rendered
		// in a single call and the cache would hold all evaluated audio
		// simultaneously, causing excessive memory usage. Caching is only
		// beneficial for real-time rendering where notes span multiple small
		// buffers and can be reused across consecutive ticks.
		NoteAudioCache effectiveCache = (frameCount < ctx.getFrames()) ? cache : null;

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
						repStart, startFrame, frameCount, effectiveCache);
			});
		});
	}

	/**
	 * Returns the position of the next note after the given position.
	 *
	 * @param position the current position in measures
	 * @return the position of the next note, or the pattern duration if none found
	 */
	public double nextNotePosition(double position) {
		return getAllElements(position, duration).stream()
				.map(PatternElement::getPositions)
				.flatMap(List::stream)
				.filter(p -> p > position)
				.mapToDouble(p -> p)
				.min().orElse(duration);
	}

	/**
	 * Serializable snapshot of a {@link PatternLayerManager}'s configuration.
	 */
	public static class Settings {
		/** The channel index for this pattern. */
		private int channel;
		/** The pattern duration in measures. */
		private double duration;
		/** The scale traversal strategy. */
		private ScaleTraversalStrategy scaleTraversalStrategy;
		/** The scale traversal depth. */
		private int scaleTraversalDepth;
		/** The minimum layer scale in measures. */
		private double minLayerScale;
		/** Whether this pattern is melodic. */
		private boolean melodic;
		/** The number of active layers. */
		private int layerCount;

		/** The parameter function for note/factory selection. */
		private ParameterFunction factorySelection;
		/** The position function for section activity selection. */
		private ParameterizedPositionFunction activeSelection;
		/** The element factory configuration. */
		private PatternElementFactory elementFactory;

		/** Returns the channel index. */
		public int getChannel() { return channel; }
		/** Sets the channel index. */
		public void setChannel(int channel) { this.channel = channel; }

		/** Returns the pattern duration in measures. */
		public double getDuration() { return duration; }
		/** Sets the pattern duration in measures. */
		public void setDuration(double duration) { this.duration = duration; }

		/** Returns the scale traversal strategy. */
		public ScaleTraversalStrategy getScaleTraversalStrategy() { return scaleTraversalStrategy; }
		/** Sets the scale traversal strategy. */
		public void setScaleTraversalStrategy(ScaleTraversalStrategy scaleTraversalStrategy) { this.scaleTraversalStrategy = scaleTraversalStrategy; }

		/** Returns the scale traversal depth. */
		public int getScaleTraversalDepth() { return scaleTraversalDepth; }
		/** Sets the scale traversal depth. */
		public void setScaleTraversalDepth(int scaleTraversalDepth) { this.scaleTraversalDepth = scaleTraversalDepth; }

		/** Returns the minimum layer scale in measures. */
		public double getMinLayerScale() { return minLayerScale; }
		/** Sets the minimum layer scale in measures. */
		public void setMinLayerScale(double minLayerScale) { this.minLayerScale = minLayerScale; }

		/** Returns whether this pattern is melodic. */
		public boolean isMelodic() { return melodic; }
		/** Sets whether this pattern is melodic. */
		public void setMelodic(boolean melodic) { this.melodic = melodic; }

		/** Returns the factory selection parameter function. */
		public ParameterFunction getFactorySelection() { return factorySelection; }
		/** Sets the factory selection parameter function. */
		public void setFactorySelection(ParameterFunction factorySelection) { this.factorySelection = factorySelection; }

		/** Returns the active selection position function. */
		public ParameterizedPositionFunction getActiveSelection() { return activeSelection; }
		/** Sets the active selection position function. */
		public void setActiveSelection(ParameterizedPositionFunction activeSelection) { this.activeSelection = activeSelection; }

		/** Returns the element factory. */
		public PatternElementFactory getElementFactory() { return elementFactory; }
		/** Sets the element factory. */
		public void setElementFactory(PatternElementFactory elementFactory) { this.elementFactory = elementFactory; }

		/**
		 * Sets the layer count from a list of parameter sets (for legacy deserialization).
		 *
		 * @param layers the list of layer parameter sets
		 */
		public void setLayers(List<ParameterSet> layers) {
			if (layers != null) {
				this.layerCount = layers.size();
			}
		}

		/** Sets the number of active layers. */
		public void setLayerCount(int layerCount) { this.layerCount = layerCount; }
		/** Returns the number of active layers. */
		public int getLayerCount() { return layerCount; }
	}
}
