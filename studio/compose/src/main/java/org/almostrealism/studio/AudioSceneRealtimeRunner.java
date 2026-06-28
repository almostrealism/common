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

package org.almostrealism.studio;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.pattern.PatternAudioBuffer;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.studio.dsl.audio.AudioDspPrimitives;
import org.almostrealism.studio.health.MultiChannelAudioOutput;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builds the {@link TemporalCellular} that drives an {@link AudioScene} in real time.
 *
 * <p>This collaborator owns the two interchangeable real-time execution strategies
 * for a scene, selected at build time by {@link MixdownManager#enablePdslMixdown}:</p>
 *
 * <ul>
 *   <li><b>CellList path</b> ({@link #createCellList}) — the established pipeline:
 *       pattern audio is prepared per buffer via {@link PatternAudioBuffer#prepareBatch()},
 *       then a compiled per-frame {@link CellList} loop applies the hand-wired Java
 *       effects and mixdown and pushes each frame to the output.</li>
 *   <li><b>Block-forward PDSL path</b> ({@link #createPdsl}) — the cutover target:
 *       the same Java pattern-prepare phase fills the consolidated render buffer, then a
 *       single compiled PDSL {@code mixdown_master} {@link Block} performs <em>all</em>
 *       DSP for the whole buffer in one {@link CompiledModel#forward} call, whose output
 *       is streamed frame-by-frame to the output line. No {@link CellList} drives the
 *       DSP — the integration contract is purely {@link TemporalCellular}.</li>
 * </ul>
 *
 * <p>The Block-forward path is the direction the redesign is moving: once pattern
 * rendering and all DSP are expressed declaratively, the per-sample {@code CellList}
 * push pipeline is no longer needed. This class keeps both available behind the A/B
 * flag so the two can be compared by ear on real material while parity gaps are
 * closed.</p>
 *
 * <p>Extracted from {@link AudioScene} (which delegates {@code runnerRealTime} here) to
 * keep the scene model focused on state and to give the two runner strategies a single,
 * coherent home.</p>
 *
 * @see AudioScene#runnerRealTime(MultiChannelAudioOutput, java.util.List, int)
 * @see MixdownManagerPdslAdapter
 */
public class AudioSceneRealtimeRunner implements CellFeatures {

	/** Classpath resource holding the PDSL program that declares {@code mixdown_master}. */
	private static final String MIXDOWN_PDSL_RESOURCE = "/pdsl/audio/mixdown_manager.pdsl";

	/**
	 * FIR filter order used when building the PDSL {@code mixdown_master} layer.
	 * Wire-first default mirroring {@code MixdownManagerPdslVerificationTest}; the Java
	 * path's filters are IIR, so this is an approximation pending parity work.
	 */
	public static int pdslFilterOrder = 40;

	/**
	 * Number of buffers the a2 render-ahead ring holds, and the number rendered before playback
	 * begins. The producer thread renders up to this many buffers ahead of the mixdown hot path,
	 * smoothing per-buffer render bursts so the a3 consumer never waits on a render.
	 */
	public static int renderAheadSlots = 8;

	/**
	 * Static wet-bus send level supplied to the PDSL {@code mixdown_master} layer.
	 * Wire-first default; the Java path derives this from genome state per buffer.
	 */
	public static double pdslWetLevel = 0.5;

	/**
	 * Static delay length (samples) supplied to the PDSL {@code mixdown_master} layer's
	 * feedback/delay stage. Wire-first default chosen to make the reverb tail audible.
	 */
	public static int pdslDelaySamples = 6500;

	/**
	 * Diagnostic: cumulative nanoseconds the hot-path tick spends in
	 * {@code renderStream.awaitSlot()} (blocking until the a2 producer has a buffer ready).
	 * A large value means a2 cannot stay ahead of a3; a small value means a3 is not waiting.
	 */
	public static final AtomicLong hotAwaitNanos = new AtomicLong();

	/**
	 * Diagnostic: cumulative nanoseconds the hot-path tick spends in {@code compiled.forward(slot)}
	 * (the a3 PDSL mixdown forward pass itself), isolated from the a2 wait.
	 */
	public static final AtomicLong hotForwardNanos = new AtomicLong();

	/** Resets the hot-path diagnostic timers ({@link #hotAwaitNanos}, {@link #hotForwardNanos}). */
	public static void resetHotPathTimers() {
		hotAwaitNanos.set(0);
		hotForwardNanos.set(0);
	}

	/** The scene this runner drives. */
	private final AudioScene<?> scene;

	/**
	 * Creates a runner for the given scene.
	 *
	 * @param scene the scene to drive in real time
	 */
	public AudioSceneRealtimeRunner(AudioScene<?> scene) {
		this.scene = scene;
	}

	/**
	 * Builds a real-time runner using whichever DSP path is currently selected by
	 * {@link MixdownManager#enablePdslMixdown}.
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render, or {@code null} for all channels
	 * @param bufferSize frames per buffer
	 * @return a {@link TemporalCellular} for real-time playback
	 */
	public TemporalCellular create(MultiChannelAudioOutput output,
								   List<Integer> channels, int bufferSize) {
		List<Integer> resolved = channels != null ? channels :
				IntStream.range(0, scene.getChannelCount()).boxed().collect(Collectors.toList());

		if (MixdownManager.enablePdslMixdown) {
			if (supportsPdsl(resolved)) {
				return createPdsl(output, resolved, bufferSize);
			}

			log("channels=" + resolved + " is outside the PDSL mixdown's supported"
					+ " configurations; using the CellList runner for this build");
		}

		return createCellList(output, resolved, bufferSize);
	}

	/**
	 * Returns whether the PDSL mixdown path supports the given channel selection.
	 *
	 * <p>Two selection shapes are supported:</p>
	 * <ul>
	 *   <li><b>Any single channel</b> {@code [c]} — including the non-zero selection from
	 *       {@link AudioScene#renderChannel}. The adapter maps bank position 0 to channel
	 *       {@code c}'s genome (via {@link MixdownManagerPdslAdapter.Config#channel(int)}),
	 *       so the PDSL render reads the same genes the CellList path reads for that
	 *       channel.</li>
	 *   <li><b>The zero-based contiguous multi-channel prefix</b> {@code [0, 1, ..., n-1]}
	 *       — the full-scene render. Here the channel mapping is the identity, so the
	 *       per-channel genome reads land on the matching pattern channels.</li>
	 * </ul>
	 *
	 * <p>A non-contiguous multi-channel selection (e.g. {@code [0, 2]}) is not yet
	 * validated and renders through the CellList runner instead: the cross-channel
	 * transmission feedback grid is indexed by bank position rather than scene channel,
	 * so an arbitrary multi-channel subset would not faithfully reproduce the routing.</p>
	 *
	 * @param channels the resolved channel indices
	 * @return true when the PDSL path can faithfully render this selection
	 */
	private boolean supportsPdsl(List<Integer> channels) {
		if (channels.isEmpty() || channels.stream().anyMatch(c -> c == null)) return false;

		// Any single channel is supported — the adapter maps its genome reads to the
		// selected channel index, so a non-zero [c] renders channel c's genes.
		if (channels.size() == 1) return true;

		// Multi-channel: still require the zero-based contiguous prefix [0, 1, ..., n-1].
		return IntStream.range(0, channels.size())
				.allMatch(i -> channels.get(i) == i);
	}

	/**
	 * Builds the established {@link CellList}-based real-time runner.
	 *
	 * <p>Three phases per tick: prepare (Java) renders pattern audio via
	 * {@link PatternAudioBuffer#prepareBatch()}; tick (compiled) applies effects per frame
	 * through the {@link CellList}; advance increments the frame counter. The compiled
	 * kernel is structurally independent of the genome — only the {@link PackedCollection}
	 * contents change on {@link AudioScene#assignGenome}, so the runner can be reused
	 * without recompilation.</p>
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render (already resolved, non-null)
	 * @param bufferSize frames per buffer
	 * @return a {@link TemporalCellular} for real-time playback
	 */
	private TemporalCellular createCellList(MultiChannelAudioOutput output,
											List<Integer> channels, int bufferSize) {
		final int[] currentFrame = {0};

		// Per-buffer frame index for WaveCell external frame control; tracks position
		// 0 to bufferSize-1 within each buffer.
		PackedCollection bufferFrameIndex = new PackedCollection(1);
		Producer<PackedCollection> bufferFrameProducer = cp(bufferFrameIndex);

		CellList cells = (CellList) scene.getCells(output, channels, bufferSize,
				() -> currentFrame[0], bufferFrameProducer);

		// Per-frame operation (must be compilable)
		Supplier<Runnable> frameOp = cells.tick();

		// Create loop body: tick + increment buffer frame index
		OperationList loopBody = new OperationList("RealTime Per-Frame Body");
		loopBody.add(frameOp);
		// Increment buffer frame index: bufferFrameIndex = bufferFrameIndex + 1
		loopBody.add(a(1, cp(bufferFrameIndex), c(1.0).add(cp(bufferFrameIndex))));

		return new TemporalCellular() {
			@Override
			public Supplier<Runnable> setup() {
				return cells.setup();
			}

			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("AudioScene RealTime Runner Tick");

				// OUTSIDE LOOP: Reset buffer frame index and prepare pattern data
				tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));
				for (PatternAudioBuffer renderCell : scene.getRenderCells()) {
					tick.add(renderCell.prepareBatch());
				}

				// INSIDE LOOP: Compilable per-frame processing with frame index increment
				tick.add(loop(loopBody, bufferSize));

				// AFTER LOOP: Advance global frame position
				tick.add(() -> () -> currentFrame[0] += bufferSize);
				return tick;
			}

			@Override
			public void reset() {
				currentFrame[0] = 0;
				cells.reset();
				// Rewind the global clock so time-driven envelopes (volume,
				// filter, AutomationManager outputs) start fresh on the next
				// genome. Without this, evaluating multiple genomes in
				// sequence makes every genome after the first run with the
				// clock parked in the post-decay region of the volume
				// envelope (gene 4 has scale = -1, a fade-out), so pattern
				// channels come out near-silent.
				scene.getTimeManager().getClock().setFrame(0);
			}
		};
	}

	/**
	 * Builds the Block-forward PDSL real-time runner.
	 *
	 * <p>The Java pattern-prepare phase populates the render cells and the consolidated
	 * render buffer (via {@link AudioScene#prepareRenderBuffers}); no Java mixdown
	 * {@link CellList} is built — all DSP runs in the compiled PDSL block. Each tick runs a
	 * compiled PDSL mixdown {@link Block} once over the whole buffer and streams its output
	 * to the master line. When effects are enabled the layer is {@code mixdown_master_wet},
	 * which reads the MAIN voicing (rows {@code [0,channels)} of the consolidated buffer)
	 * for the dry mix and the separately-rendered WET voicing (rows
	 * {@code [channels,2*channels)}) for the efx bus, matching the Java MAIN+WET routing;
	 * with effects off it is the single-input {@code mixdown_master}.</p>
	 *
	 * <p><b>Parity with the CellList path.</b> The compiled mixdown reproduces the full
	 * Java DSP chain and is parity-validated (by ear and windowed RMS) on the real-scene
	 * A/B in {@code AudioScenePdslCutoverTest}:</p>
	 * <ul>
	 *   <li>the per-channel {@code EfxManager} chain (gene-chosen filter, wet level,
	 *       automation, cross-channel transmission feedback grid) — <b>migrated</b>;</li>
	 *   <li>the reverb network ({@code delay_network}) — <b>migrated</b>;</li>
	 *   <li>single-channel and zero-based contiguous multi-channel selections are both
	 *       supported (see {@link #supportsPdsl}); the adapter maps per-channel genome
	 *       reads to the selected scene channels.</li>
	 * </ul>
	 *
	 * <p><b>Remaining wire-first gap.</b> The path writes a mono master (the LEFT writer)
	 * duplicated to both stereo channels; true stereo (per-channel PAN in the PDSL mixdown)
	 * is outstanding. The per-buffer automation granularity (one clock value per forward
	 * pass) is the documented trade-off versus the CellList path's per-frame automation.</p>
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render (already resolved, non-null)
	 * @param bufferSize frames per buffer
	 * @return a {@link TemporalCellular} for real-time playback
	 */
	private TemporalCellular createPdsl(MultiChannelAudioOutput output,
										List<Integer> channels, int bufferSize) {
		final int[] currentFrame = {0};
		// Render-ahead position (frames) for the a2 producer thread, distinct from the a3
		// playback position above. The producer renders buffers ahead of playback into a ring;
		// the hot path only consumes already-rendered buffers and never triggers a render.
		final long[] renderFrame = {0};
		int channelCount = channels.size();

		// Frame index within the current buffer (0..bufferSize-1), driven by the
		// output-streaming loop below.
		PackedCollection bufferFrameIndex = new PackedCollection(1);

		// Prepare the pattern-render wiring (render cells + consolidated buffer) WITHOUT
		// building the Java mixdown CellList: the PDSL block performs all DSP, so the
		// CellList would only be compiled and discarded. prepareRenderBuffers reproduces the
		// pattern-prepare side effects getCells relies on and returns just the setup.
		Supplier<Runnable> patternSetup = scene.prepareRenderBuffers(channels, bufferSize,
				() -> (int) renderFrame[0]);

		// Choose the DSP layer based on whether a separate WET voicing was rendered.
		// AudioScene fills the consolidated buffer in the order
		// [LEFT-MAIN(N), LEFT-WET(N), RIGHT-MAIN(N), RIGHT-WET(N)] when efx is enabled, so:
		//   - efx on:  the first 2*N ranges (LEFT-MAIN then LEFT-WET) are contiguous, and
		//              mixdown_master_wet reads MAIN from rows [0,N) and WET from rows [N,2N).
		//   - efx off: there are no WET ranges; mixdown_master reads the single MAIN region
		//              and derives wet internally.
		// Either way a single zero-copy view over offset 0 is the model input.
		boolean wetVoicing = MixdownManager.enableEfx;
		int inputChannels = wetVoicing ? channelCount * 2 : channelCount;
		String layerName = wetVoicing ? "mixdown_master_wet" : "mixdown_master";

		// Dual-mono master: the consolidated buffer also holds the RIGHT-side regions, but for
		// this content the per-stereo-side pattern audio is (near-)identical — both the Java
		// CellList path and a true per-side PDSL render produce essentially mono output — so
		// processing the two sides separately costs 2x for no audible benefit. A genuine stereo
		// image would require per-channel PAN in the PDSL mixdown (not separate-region
		// processing); until that exists, render one master and stream it to both writers.
		MixdownManager mixdown = scene.getMixdownManager();
		// Pass the actual selected channel indices (not just the count) so the adapter's
		// per-channel genome reads resolve to the rendered channels. For a single-channel
		// renderChannel(c) selection this maps bank position 0 to channel c's genes; for the
		// multi-channel zero-based contiguous selection it is the identity mapping.
		int[] channelIndices = channels.stream().mapToInt(Integer::intValue).toArray();
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				channelIndices, bufferSize, scene.getSampleRate(),
				pdslFilterOrder, pdslWetLevel, pdslDelaySamples);

		PackedCollection consolidated = scene.getConsolidatedRenderBuffer();
		TraversalPolicy inputShape = new TraversalPolicy(inputChannels, bufferSize);
		PackedCollection pdslInput = consolidated.range(inputShape, 0);

		// a2 render-ahead layer: a dedicated producer thread renders successive buffers into a
		// ring so the a3 hot path (the tick below) only ever mixes already-rendered audio. The
		// render cells created by prepareRenderBuffers fill pdslInput for the current renderFrame;
		// renderOp runs that pattern-prepare once per buffer, off the hot path. Driving the render
		// from the producer thread is safe and overlaps its Java orchestration with the consumer's
		// GPU mixdown (the Metal command runner serializes the actual GPU dispatch).
		// Render every cell (both stereo sides). Stereo is in scope: true stereo mixes both
		// sides' pattern audio in a single forward, so the RIGHT-side render is a required input
		// for that path — it must not be skipped as an a2 shortcut. a2's cost is reduced by
		// making the render itself faster, not by rendering fewer channels.
		OperationList renderOps = new OperationList("AudioScene a2 Pattern Render Ahead");
		for (PatternAudioBuffer renderCell : scene.getRenderCells()) {
			renderOps.add(renderCell.prepareBatch());
		}
		Runnable renderOp = renderOps.get();
		PatternRenderStream renderStream = new PatternRenderStream(
				renderOp, renderFrame, pdslInput, renderAheadSlots, inputChannels, bufferSize);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource(MIXDOWN_PDSL_RESOURCE);

		Map<String, Object> args = buildMixdownArgs(wetVoicing, mixdown, config);
		CompiledModel compiled = compileMixdownModel(loader, program, layerName, inputShape, args);

		// Per-buffer automation refresh: the time-varying gene/clock-driven values
		// (filter cutoffs, volume, efx automation, reverb send) live in collection slots
		// the compiled graph reads every forward pass; this re-evaluates them for the
		// buffer's clock position. Producer-valued model args are frozen at build time,
		// so without this the filter sweeps would never engage.
		Supplier<Runnable> automationRefresh = MixdownManagerPdslAdapter.automationRefresh(
				mixdown, wetVoicing ? scene.getEfxManager() : null, config, args);

		// forward() copies into a stable output buffer (CompiledModel returns the same instance
		// every pass), so a single throwaway pass captures the handle the streaming loop reads.
		PackedCollection masterOutput = compiled.forward(pdslInput);

		// Stream the mono master to both writers one frame at a time (WaveOutput's Writer
		// contract is one frame per push, advancing the cursor; WaveOutput.write gates on the
		// minimum frame count across channels, so both writers must be fed).
		Receptor<PackedCollection> masterLeft = output.getMaster(ChannelInfo.StereoChannel.LEFT);
		Receptor<PackedCollection> masterRight = output.getMaster(ChannelInfo.StereoChannel.RIGHT);
		OperationList outputLoopBody = new OperationList("PDSL Output Stream Body");
		outputLoopBody.add(masterLeft.push(c(shape(1), p(masterOutput), p(bufferFrameIndex))));
		outputLoopBody.add(masterRight.push(c(shape(1), p(masterOutput), p(bufferFrameIndex))));
		outputLoopBody.add(a(1, cp(bufferFrameIndex), c(1.0).add(cp(bufferFrameIndex))));

		return new TemporalCellular() {
			@Override
			public Supplier<Runnable> setup() {
				OperationList setup = new OperationList("AudioScene PDSL RealTime Runner Setup");
				// One-time render-cell setup (prepareRenderBuffers also renders the first buffer
				// into the working input, which the producer harmlessly re-renders below).
				setup.add(patternSetup);
				setup.add(() -> () -> compiled.reset());
				// Start the a2 producer thread and fill the ring ahead of playback. After this
				// returns, the hot path is guaranteed to find each buffer already rendered.
				setup.add(() -> () -> renderStream.start(renderAheadSlots));
				return setup;
			}

			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("AudioScene PDSL RealTime Runner Tick");

				// HOT PATH — MIXDOWN ONLY. No pattern preparation happens here: the a2 producer
				// thread (started in setup) renders every buffer ahead of time into the ring, so
				// this tick never triggers a render. Reset the per-buffer frame index for output.
				tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));

				// AUTOMATION: evaluate the clock-driven values (cutoffs, volume, sends) into
				// their argument slots for this buffer's clock position.
				tick.add(automationRefresh);

				// DSP: take the next already-rendered buffer from the a2 ring and run the
				// whole-buffer PDSL mixdown forward pass over it (writes into masterOutput). This
				// is the only per-buffer compute on the hot path.
				tick.add(() -> () -> {
					long awaitStart = System.nanoTime();
					PackedCollection slot = renderStream.awaitSlot();
					long forwardStart = System.nanoTime();
					hotAwaitNanos.addAndGet(forwardStart - awaitStart);
					compiled.forward(slot);
					hotForwardNanos.addAndGet(System.nanoTime() - forwardStart);
					renderStream.release();
				});

				// STREAM: drain masterOutput to both writers frame-by-frame
				tick.add(loop(outputLoopBody, bufferSize));

				// Advance the global clock by one buffer. The CellList path ticks the clock
				// once per frame (via cells' time::tick requirement); this path does not tick
				// the CellList, so it must advance the clock itself, or the time-driven
				// automation (filter sweeps, volume envelopes) would stay frozen at frame 0.
				// Per-buffer granularity (one clock value per forward) is the wire-first
				// trade-off versus the CellList path's per-frame automation.
				tick.add(loop(scene.getTimeManager().tick(), bufferSize));

				// AFTER: advance global playback frame position
				tick.add(() -> () -> currentFrame[0] += bufferSize);
				return tick;
			}

			@Override
			public void reset() {
				renderStream.stop();
				currentFrame[0] = 0;
				renderFrame[0] = 0;
				compiled.reset();
				scene.getTimeManager().getClock().setFrame(0);
			}
		};
	}

	/**
	 * Builds the argument map for the mixdown model, selecting the wet or single-input
	 * argument set.
	 *
	 * @param wetVoicing whether a separate WET voicing was rendered (selects the layer/args)
	 * @param mixdown    the mixdown manager whose genome drives the args
	 * @param config     structural configuration
	 * @return a populated argument map
	 */
	private Map<String, Object> buildMixdownArgs(boolean wetVoicing, MixdownManager mixdown,
												 MixdownManagerPdslAdapter.Config config) {
		return wetVoicing
				? MixdownManagerPdslAdapter.buildArgsMap(mixdown, scene.getEfxManager(), config)
				: MixdownManagerPdslAdapter.buildArgsMap(mixdown, config);
	}

	/**
	 * Compiles a mixdown layer into a {@link CompiledModel} for the given input shape and args.
	 *
	 * @param loader     the PDSL loader
	 * @param program    the parsed PDSL program
	 * @param layerName  the layer to build
	 * @param inputShape the model input shape
	 * @param args       the argument map
	 * @return the compiled model
	 */
	private CompiledModel compileMixdownModel(PdslLoader loader, PdslNode.Program program,
											  String layerName, TraversalPolicy inputShape,
											  Map<String, Object> args) {
		Block block = loader.buildLayer(program, layerName, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model.compile();
	}
}
