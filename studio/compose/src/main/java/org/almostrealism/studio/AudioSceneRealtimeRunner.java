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
	 * Static wet-bus send level supplied to the PDSL {@code mixdown_master} layer.
	 * Wire-first default; the Java path derives this from genome state per buffer.
	 */
	public static double pdslWetLevel = 0.5;

	/**
	 * Static delay length (samples) supplied to the PDSL {@code mixdown_master} layer's
	 * feedback/delay stage. Wire-first default chosen to make the reverb tail audible.
	 */
	public static int pdslDelaySamples = 6500;

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
			return createPdsl(output, resolved, bufferSize);
		}

		return createCellList(output, resolved, bufferSize);
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
	 * <p>The Java pattern-prepare phase is reused unchanged (via
	 * {@link AudioScene#getCells}, which populates the render cells and the consolidated
	 * render buffer), but the per-frame {@link CellList} it returns is <em>not</em> ticked.
	 * Instead, each tick runs a compiled PDSL mixdown {@link Block} once over the whole
	 * buffer and streams its output to the master line. When effects are enabled the layer
	 * is {@code mixdown_master_wet}, which reads the MAIN voicing (rows {@code [0,channels)}
	 * of the consolidated buffer) for the dry mix and the separately-rendered WET voicing
	 * (rows {@code [channels,2*channels)}) for the efx bus, matching the Java MAIN+WET
	 * routing; with effects off it is the single-input {@code mixdown_master}.</p>
	 *
	 * <p><b>Wire-first scope.</b> This path currently:</p>
	 * <ul>
	 *   <li>writes a mono master (the LEFT writer) duplicated to both stereo channels;</li>
	 *   <li>renders the {@code mixdown_master}/{@code mixdown_master_wet} DSP only — the
	 *       full per-channel {@code EfxManager} chain and the reverb network are not yet in
	 *       PDSL, so it is not bit-parity with the Java path;</li>
	 *   <li>still builds (and compiles) the unused Java mixdown {@link CellList} as a
	 *       side effect of reusing {@code getCells} for pattern preparation.</li>
	 * </ul>
	 * These are deliberate gaps to be closed once the path is validated end-to-end.
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render (already resolved, non-null)
	 * @param bufferSize frames per buffer
	 * @return a {@link TemporalCellular} for real-time playback
	 */
	private TemporalCellular createPdsl(MultiChannelAudioOutput output,
										List<Integer> channels, int bufferSize) {
		final int[] currentFrame = {0};
		int channelCount = channels.size();

		// Frame index within the current buffer (0..bufferSize-1), shared by the pattern
		// WaveCell external frame control and the output-streaming loop below.
		PackedCollection bufferFrameIndex = new PackedCollection(1);
		Producer<PackedCollection> bufferFrameProducer = cp(bufferFrameIndex);

		// Reuse the proven pattern-preparation wiring. The returned Java mixdown CellList
		// is intentionally not ticked on this path — the PDSL block performs all DSP.
		CellList cells = (CellList) scene.getCells(output, channels, bufferSize,
				() -> currentFrame[0], bufferFrameProducer);

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
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				channelCount, bufferSize, scene.getSampleRate(),
				pdslFilterOrder, pdslWetLevel, pdslDelaySamples);

		PackedCollection consolidated = scene.getConsolidatedRenderBuffer();
		TraversalPolicy inputShape = new TraversalPolicy(inputChannels, bufferSize);
		PackedCollection pdslInput = consolidated.range(inputShape, 0);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource(MIXDOWN_PDSL_RESOURCE);

		CompiledModel compiled = compileMixdownModel(loader, program, layerName, inputShape,
				buildMixdownArgs(wetVoicing, mixdown, config));

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
				setup.add(cells.setup());
				setup.add(() -> () -> compiled.reset());
				return setup;
			}

			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("AudioScene PDSL RealTime Runner Tick");

				// OUTSIDE LOOP: reset the per-buffer frame index and prepare pattern data
				tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));
				for (PatternAudioBuffer renderCell : scene.getRenderCells()) {
					tick.add(renderCell.prepareBatch());
				}

				// DSP: run the whole-buffer PDSL forward pass (writes into masterOutput). The
				// automation producers read the global clock, so the value used for this buffer
				// reflects the clock's position at the buffer start — see the clock advance below.
				tick.add(() -> () -> compiled.forward(pdslInput));

				// STREAM: drain masterOutput to both writers frame-by-frame
				tick.add(loop(outputLoopBody, bufferSize));

				// Advance the global clock by one buffer. The CellList path ticks the clock
				// once per frame (via cells' time::tick requirement); this path does not tick
				// the CellList, so it must advance the clock itself, or the time-driven
				// automation (filter sweeps, volume envelopes) would stay frozen at frame 0.
				// Per-buffer granularity (one clock value per forward) is the wire-first
				// trade-off versus the CellList path's per-frame automation.
				tick.add(loop(scene.getTimeManager().tick(), bufferSize));

				// AFTER: advance global frame position
				tick.add(() -> () -> currentFrame[0] += bufferSize);
				return tick;
			}

			@Override
			public void reset() {
				currentFrame[0] = 0;
				cells.reset();
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
