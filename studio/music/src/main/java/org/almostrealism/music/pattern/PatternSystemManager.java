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

package org.almostrealism.music.pattern;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.music.notes.NoteSourceProvider;
import org.almostrealism.music.notes.TreeNoteSource;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.heredity.ProjectedChromosome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.almostrealism.music.notes.NoteAudioContext;
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
 * <p>The key method {@link #sum(java.util.function.Supplier, ChannelInfo, java.util.function.IntSupplier, int)}
 * renders patterns for a channel to a destination buffer. The rendering process:</p>
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
 * Supplier<Runnable> render = patterns.sum(contextSupplier, channel, () -> 0, totalFrames);
 * render.get().run();
 * }</pre>
 *
 * @see PatternLayerManager
 * @see NoteAudioChoice
 * @see PatternElement
 *
 * @author Michael Murray
 */
public class PatternSystemManager implements NoteSourceProvider, CodeFeatures, Destroyable {
	/** Whether automatic volume adjustment is enabled. */
	public static final boolean enableAutoVolume = false;

	/** Whether lazy destination computation is enabled. */
	public static final boolean enableLazyDestination = false;

	/** Whether verbose logging is enabled. */
	public static boolean enableVerbose = false;

	/** Whether warning logging is enabled. */
	public static boolean enableWarnings = true;

	/** The list of available note audio choices. */
	private final List<NoteAudioChoice> choices;

	/** The list of pattern layer managers, one per pattern. */
	private final List<PatternLayerManager> patterns;

	/** The chromosomes providing genetic parameters, one per pattern. */
	private final List<ProjectedChromosome> chromosomes;

	/** The volume scaling collection. */
	private PackedCollection volume;

	/**
	 * Host-side shadow of the single value in {@link #volume}, used to skip the per-cell
	 * volume adjustment when it would multiply by 1.0. Valid only while
	 * {@link #enableAutoVolume} is off: auto-volume writes {@link #volume} device-side
	 * (per render), where this shadow cannot see it, so the skip is never taken when
	 * auto-volume is enabled.
	 */
	private double volumeValue = 1.0;

	/** The destination collection. */
	private PackedCollection destination;

	/**
	 * Creates a {@code PatternSystemManager} with no initial choices.
	 *
	 * @param chromosomes the list of chromosomes for pattern parameterization
	 */
	public PatternSystemManager( List<ProjectedChromosome> chromosomes) {
		this(new ArrayList<>(), chromosomes);
	}

	/**
	 * Creates a {@code PatternSystemManager} with the given choices and chromosomes.
	 *
	 * @param choices     the available note audio choices
	 * @param chromosomes the list of chromosomes for pattern parameterization
	 */
	public PatternSystemManager(List<NoteAudioChoice> choices,  List<ProjectedChromosome> chromosomes) {
		this.choices = choices;
		this.patterns = new ArrayList<>();
		this.chromosomes = chromosomes;
	}

	/**
	 * Incremented by {@link #clear()} whenever the current pattern managers are destroyed,
	 * and by {@link #addPattern} whenever a pattern is added.
	 * Render operations built by {@link #sum} capture the generation they were built
	 * against and refuse to run once it has moved on, because the managers they reference
	 * have released their native memory.
	 */
	private volatile int patternGeneration;

	/**
	 * Initializes the volume to 1.0. Releases any previously allocated volume
	 * collection first, so re-initializing a live manager does not leak the native
	 * memory backing the old one.
	 *
	 * <p>The volume is allocated independently (via {@code new PackedCollection})
	 * rather than through {@link PackedCollection#factory()}, which {@code pack} uses.
	 * It is owned by this manager for its lifetime and outlives any render stage, so
	 * it must not be an alias into an active {@link Heap}: a heap alias could not be
	 * released by {@link #destroy()} and would be invalidated when the heap stage that
	 * backs it is popped.</p>
	 */
	public void init() {
		if (volume != null) volume.destroy();
		volume = new PackedCollection(1).fill(1.0);
		volumeValue = 1.0;
	}

	@Override
	public List<NoteAudioSource> getSource(String id) {
		return choices.stream()
				.filter(f -> Objects.equals(id, f.getId()))
				.map(NoteAudioChoice::getSources)
				.findFirst().orElse(null);
	}

	/** Returns the list of note audio choices. */
	public List<NoteAudioChoice> getChoices() {
		return choices;
	}

	/**
	 * Returns a flat list of all audio sources from all choices.
	 *
	 * @return all audio sources
	 */
	public List<NoteAudioSource> getAllSources() {
		return getChoices()
				.stream()
				.flatMap(c -> c.getSources().stream()).toList();
	}

	/**
	 * Returns a read-only view of the pattern layer managers.
	 *
	 * <p>The view reflects later changes, but cannot be used to mutate the pattern set:
	 * every structural mutation must go through {@link #addPattern}, {@link #clear()} or
	 * {@link #setSettings(Settings)}, which advance the pattern generation that stale
	 * {@link #sum} operations are checked against and destroy removed managers.</p>
	 *
	 * @return an unmodifiable view of the pattern layer managers
	 */
	public List<PatternLayerManager> getPatterns() { return Collections.unmodifiableList(patterns); }

	/**
	 * Returns all pattern elements in {@code [start, end)}, grouped by choice.
	 *
	 * @param start the inclusive start position
	 * @param end   the exclusive end position
	 * @return a map from choice to list of elements
	 */
	public Map<NoteAudioChoice, List<PatternElement>> getPatternElements(double start, double end) {
		Map<NoteAudioChoice, List<PatternElement>> elements = new HashMap<>();

		patterns.forEach(layer -> {
			layer.getAllElementsByChoice(start, end).forEach((k, v) ->
					elements.computeIfAbsent(k, key -> new ArrayList<>()).addAll(v));
		});

		return elements;
	}

	/**
	 * Returns this manager's volume scaling collection, or {@code null} before
	 * {@link #init()} or after {@link #destroy()}. Package-private: it exposes the
	 * collection so tests in this package can verify that {@link #destroy()} releases it,
	 * without widening the public surface.
	 *
	 * @return the volume collection, or {@code null}
	 */
	PackedCollection getVolume() {
		return volume;
	}

	/**
	 * Sets the output volume scaling factor.
	 *
	 * @param volume the new volume value
	 */
	public void setVolume(double volume) {
		this.volume.fill(volume);
		this.volumeValue = volume;
	}

	/**
	 * Returns a {@link Settings} snapshot of the current configuration.
	 *
	 * @return the current settings
	 */
	public Settings getSettings() {
		Settings settings = new Settings();
		settings.getPatterns().addAll(patterns.stream().map(PatternLayerManager::getSettings).toList());
		return settings;
	}

	/**
	 * Applies a {@link Settings} snapshot to this manager.
	 *
	 * @param settings the settings to apply
	 */
	public void setSettings(Settings settings) {
		clear();
		settings.getPatterns().forEach(s -> addPattern(s.getChannel(), s.getDuration(), s.isMelodic()).setSettings(s));
	}

	/**
	 * Refreshes the parameters for all patterns, adjusting seed bias based on active layer count.
	 */
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

	/**
	 * Propagates the keyboard tuning to all choices.
	 *
	 * @param tuning the tuning to apply
	 */
	public void setTuning(KeyboardTuning tuning) {
		choices.forEach(c -> c.setTuning(tuning));
	}

	/**
	 * Sets the file wave data provider tree for all sources, with no progress reporting.
	 *
	 * @param root the file tree to set
	 */
	public void setTree(FileWaveDataProviderTree<?> root) {
		setTree(root, null);
	}

	/**
	 * Sets the file wave data provider tree for all sources with optional progress reporting.
	 *
	 * @param root     the file tree to set
	 * @param progress an optional consumer that receives progress values in {@code [0, 1]}
	 */
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

	/**
	 * Adds a new pattern and returns its manager.
	 *
	 * <p>Adding a pattern is a structural mutation of the pattern set, so it advances the
	 * pattern generation: any render operation previously returned by {@link #sum} becomes
	 * stale and throws {@link IllegalStateException} when run, as it does after
	 * {@link #clear()}.</p>
	 *
	 * @param channel  the channel index
	 * @param measures the duration in measures
	 * @param melodic  whether the pattern is melodic
	 * @return the new {@link PatternLayerManager}
	 */
	public PatternLayerManager addPattern(int channel, double measures, boolean melodic) {
		PatternLayerManager pattern =
				new PatternLayerManager(choices,
						chromosomes.get(patterns.size()),
						channel, measures, melodic);
		patterns.add(pattern);
		patternGeneration++;
		return pattern;
	}

	/**
	 * Exports all patterns as MIDI events for the full arrangement duration.
	 *
	 * @param context the audio scene context for timing and scale resolution
	 * @return list of MIDI note events, sorted by onset
	 */
	public List<MidiNoteEvent> toMidiEvents(AudioSceneContext context) {
		return toMidiEvents(context, 0.0, context.getMeasures());
	}

	/**
	 * Exports all patterns in the time range {@code [start, end)} measures
	 * as MIDI events.
	 *
	 * <p>Each {@link PatternLayerManager} repeats across the arrangement based
	 * on its duration. This method iterates over all repetitions that overlap
	 * with the requested range and collects their MIDI events.</p>
	 *
	 * @param context the audio scene context for timing and scale resolution
	 * @param start   the start of the export range in measures (inclusive)
	 * @param end     the end of the export range in measures (exclusive)
	 * @return list of MIDI note events within the range, sorted by onset
	 */
	public List<MidiNoteEvent> toMidiEvents(AudioSceneContext context,
											 double start, double end) {
		return toMidiEvents(context, start, end, pattern -> true);
	}

	/**
	 * Exports the patterns accepted by {@code filter} in the time range
	 * {@code [start, end)} measures as MIDI events.
	 *
	 * <p>Identical to {@link #toMidiEvents(AudioSceneContext, double, double)}
	 * but restricted to the {@link PatternLayerManager}s for which
	 * {@code filter} returns {@code true}.  This lets callers export a single
	 * channel's content (e.g. {@code p -> p.getChannel() == ch}) without pulling
	 * in the rest of the arrangement — used when assembling a per-role ML prompt.</p>
	 *
	 * @param context the audio scene context for timing and scale resolution
	 * @param start   the start of the export range in measures (inclusive)
	 * @param end     the end of the export range in measures (exclusive)
	 * @param filter  predicate selecting which pattern managers to export
	 * @return list of MIDI note events within the range, sorted by onset
	 */
	public List<MidiNoteEvent> toMidiEvents(AudioSceneContext context,
											 double start, double end,
											 Predicate<PatternLayerManager> filter) {
		List<MidiNoteEvent> events = new ArrayList<>();

		for (PatternLayerManager pattern : patterns) {
			if (!filter.test(pattern)) continue;

			double patternDuration = pattern.getDuration();
			if (patternDuration <= 0) continue;

			int firstRepetition = Math.max(0, (int) Math.floor(start / patternDuration));
			int lastRepetition = (int) Math.ceil(end / patternDuration);

			for (int rep = firstRepetition; rep < lastRepetition; rep++) {
				double repStart = rep * patternDuration;
				if (repStart + patternDuration <= start || repStart >= end) continue;
				events.addAll(pattern.toMidiEvents(context, repStart));
			}
		}

		Collections.sort(events);
		return events;
	}

	/**
	 * Removes all patterns from this manager, destroying each one first so its
	 * note-audio cache is released rather than leaked.
	 *
	 * <p>Each {@link PatternLayerManager} owns a cache of rendered note audio backed
	 * by native memory. Simply dropping the manager references (as a bare
	 * {@code patterns.clear()} would) leaves that memory reachable only through the
	 * garbage collector's reference queue, so {@link #destroy()} on the owning scene
	 * could no longer reach it. Destroying each manager before removing it frees that
	 * memory deterministically, which matters when settings are reloaded into a live
	 * manager (see {@link #setSettings(Settings)}) as well as at teardown.</p>
	 *
	 * <p>Any render operation previously returned by {@link #sum} references the
	 * destroyed managers, so it becomes stale: running it afterwards throws
	 * {@link IllegalStateException} instead of evaluating against released memory.
	 * Callers that replace the patterns of a live manager must rebuild their render
	 * operations.</p>
	 */
	public void clear() {
		patterns.forEach(PatternLayerManager::destroy);
		patterns.clear();
		patternGeneration++;
	}

	/**
	 * Releases the native memory held by every pattern's note-audio cache and empties
	 * the pattern list. Delegates to {@link #clear()} so teardown and settings reloads
	 * share one code path; the operation is idempotent, so a repeated teardown simply
	 * finds an already-empty list.
	 *
	 * <p>Also releases the manager-owned {@link #volume} collection allocated by
	 * {@link #init()} and nulls the reference, so a repeated teardown is safe and the
	 * native memory backing it is freed deterministically rather than left for the
	 * garbage collector's reference queue.</p>
	 */
	@Override
	public void destroy() {
		Destroyable.super.destroy();
		clear();

		if (volume != null) {
			volume.destroy();
			volume = null;
		}
	}

	/**
	 * Renders patterns for a frame range into the destination buffer.
	 *
	 * <p>This is the single entry point for pattern rendering. It updates
	 * destination buffers, iterates through all patterns assigned to the
	 * channel, sums their audio output, and optionally applies auto-volume
	 * normalization.</p>
	 *
	 * <p>The returned operation is bound to the pattern managers present when it is
	 * built. If they are later replaced or destroyed (see {@link #clear()}) or a pattern
	 * is added (see {@link #addPattern}), running it throws {@link IllegalStateException};
	 * build a new operation instead. The guard also covers an operation built for a
	 * channel that had no patterns at build time, so a channel that gains patterns after
	 * the operation was built does not silently keep rendering as an empty channel.</p>
	 *
	 * @param context Supplier for the AudioSceneContext containing destination buffer
	 * @param channel Target channel (index, voicing, audio channel)
	 * @param startFrame Supplier for the starting frame position
	 * @param frameCount Number of frames to render
	 * @return Operation that renders the specified frame range
	 *
	 * @see PatternLayerManager#sum(Supplier, ChannelInfo.Voicing, ChannelInfo.StereoChannel, IntSupplier, int)
	 */
	public Supplier<Runnable> sum(Supplier<AudioSceneContext> context,
								  ChannelInfo channel,
								  IntSupplier startFrame,
								  int frameCount) {
		OperationList op = new OperationList("PatternSystemManager Sum");

		if (enableLazyDestination) {
			op.add(() -> () -> {
				AudioSceneContext ctx = context.get();
				this.destination = ctx.getDestination();
				IntStream.range(0, patterns.size()).forEach(i ->
						patterns.get(i).updateDestination(ctx));
			});
		} else {
			AudioSceneContext ctx = context.get();
			this.destination = ctx.getDestination();
			IntStream.range(0, patterns.size()).forEach(i ->
					patterns.get(i).updateDestination(ctx));
		}

		// Guard before the empty-channel check so an op built for a channel with no
		// patterns is also rejected once a later mutation gives that channel patterns.
		int generation = patternGeneration;
		op.add(() -> () -> {
			if (generation != patternGeneration) {
				throw new IllegalStateException("Pattern render operation is stale because its"
						+ " patterns were replaced or destroyed after it was built");
			}
		});

		List<Integer> patternsForChannel = IntStream.range(0, patterns.size())
				.filter(i -> channel.getPatternChannel() == patterns.get(i).getChannel())
				.boxed().toList();

		if (patternsForChannel.isEmpty()) {
			if (enableWarnings) warn("No patterns for channel " + channel);
			return op;
		}

		patternsForChannel.forEach(i -> {
			op.add(patterns.get(i).sum(context, channel.getVoicing(),
					channel.getAudioChannel(), startFrame, frameCount));
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
			// A unity volume is a no-op multiply, but dispatching it still costs a
			// synchronous per-cell kernel evaluation (measured as the single largest
			// commit-forcing host wait on the streaming render path), so it is skipped.
			// The host-side shadow is authoritative only while auto-volume is off —
			// auto-volume rewrites the volume collection device-side every render.
			if (enableAutoVolume || volumeValue != 1.0) {
				AudioProcessingUtils.getSum().adjustVolume(context.get().getDestination(), volume);
			}
		});

		return op;
	}

	/**
	 * Pre-evaluates all note audio for each pattern layer to warm the kernel compilation cache.
	 *
	 * <p>Iterates every {@link PatternLayerManager}, evaluates each note's audio producer,
	 * and discards the result. Call this after construction and genome assignment, before
	 * starting the real-time loop, to populate the {@code FrequencyCache} upfront.</p>
	 *
	 * <p>The notes gathered for each element are transient to this warm-up: each owns a
	 * single-element offset-argument {@link PackedCollection} nothing else references, so
	 * they are {@link RenderedNoteAudio#destroy() destroyed} in a {@code finally} after
	 * evaluation. The evaluated audio is discarded: when a {@link Heap} is active the
	 * evaluation runs in a heap stage that frees it on exit, and otherwise the
	 * evaluation's output allocation is destroyed directly, so repeated scene warm-ups
	 * do not accumulate native allocations until garbage collection. The scratch
	 * destination allocated for each pattern is likewise released in a {@code finally}; it
	 * exists only to satisfy {@link PatternLayerManager#updateDestination} during warm-up
	 * and is replaced by the real destination on the first render.</p>
	 *
	 * @param contextProvider a function that creates an {@link AudioSceneContext} for a channel
	 * @return the number of notes successfully evaluated during warmup
	 */
	public int warmNoteCache(Function<ChannelInfo, AudioSceneContext> contextProvider) {
		init();
		int notesEvaluated = 0;

		for (PatternLayerManager plm : patterns) {
			boolean melodic = plm.isMelodic();
			ChannelInfo channel = new ChannelInfo(plm.getChannel(),
					ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
			AudioSceneContext ctx = contextProvider.apply(channel);
			PackedCollection warmDest = new PackedCollection(4096);
			ctx.setDestination(warmDest);
			plm.updateDestination(ctx);

			try {
				Map<NoteAudioChoice, List<PatternElement>> elementsByChoice =
						plm.getAllElementsByChoice(0.0, plm.getDuration());

				for (Map.Entry<NoteAudioChoice, List<PatternElement>> entry :
						elementsByChoice.entrySet()) {
					NoteAudioChoice choice = entry.getKey();
					List<PatternElement> elements = entry.getValue();

					NoteAudioContext audioContext =
							new NoteAudioContext(
									ChannelInfo.Voicing.MAIN,
									ChannelInfo.StereoChannel.LEFT,
									choice.getValidPatternNotes(),
									pos -> pos + 1.0);

					for (PatternElement element : elements) {
						List<RenderedNoteAudio> notes =
								element.getNoteDestinations(melodic, 0.0, ctx, audioContext);

						try {
							for (RenderedNoteAudio note : notes) {
								if (note.getExpectedFrameCount() <= 0) continue;

								try {
									boolean[] rendered = {false};
									Heap.stage(() -> {
										Producer<PackedCollection> producer =
												note.getProducer(note.getExpectedFrameCount());
										if (producer == null) return;
										PackedCollection audio = traverse(1, producer).get().evaluate();
										if (audio == null) return;
										rendered[0] = true;
										if (Heap.getDefault() == null) audio.getRootDelegate().destroy();
									});
									if (rendered[0]) {
										notesEvaluated++;
									}
								} catch (Exception e) {
									// Skip notes that fail evaluation during warmup
								}
							}
						} finally {
							notes.forEach(RenderedNoteAudio::destroy);
						}
					}
				}
			} finally {
				warmDest.destroy();
			}
		}

		return notesEvaluated;
	}

	/**
	 * Serializable snapshot of all pattern configurations in a {@link PatternSystemManager}.
	 */
	public static class Settings {
		/** The list of pattern layer manager settings. */
		private List<PatternLayerManager.Settings> patterns = new ArrayList<>();

		/** Returns the list of pattern layer manager settings. */
		public List<PatternLayerManager.Settings> getPatterns() { return patterns; }

		/** Sets the list of pattern layer manager settings. */
		public void setPatterns(List<PatternLayerManager.Settings> patterns) { this.patterns = patterns; }

		/**
		 * Creates a default {@code Settings} for the given channel and pattern configuration.
		 *
		 * @param channels           the number of channels
		 * @param patternsPerChannel the number of patterns per channel
		 * @param activePatterns     function returning active pattern count for a channel
		 * @param layersPerPattern   function returning layer count for a channel
		 * @param minLayerScale      function returning minimum layer scale for a channel
		 * @param duration           function returning duration for a channel
		 * @return the default settings
		 */
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
