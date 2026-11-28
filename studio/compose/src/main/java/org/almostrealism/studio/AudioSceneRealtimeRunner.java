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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.Cells;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.TimingMetric;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.pattern.PatternAudioBuffer;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.studio.dsl.audio.AudioDspPrimitives;
import org.almostrealism.studio.health.MultiChannelAudioOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
	 * Number of buffers the pattern render-ahead ring holds, and the number rendered before playback
	 * begins. The producer thread renders up to this many buffers ahead of the mixdown hot path,
	 * smoothing per-buffer render bursts so the mixdown consumer never waits on a render.
	 *
	 * <p>The render producer and mixdown consumer share the single Metal command runner, so the
	 * producer's per-buffer render time varies (GPU contention, occasional kernel/cache misses). When
	 * the ring is shallow those transient stalls drain it and the consumer blocks (measured: ~21&nbsp;ms/tick
	 * of wait at 8192 with depth&nbsp;8). A deeper ring absorbs the variance — the producer keeps up on
	 * average, so the consumer stops waiting (wait fell to ~0, raising sustained throughput from ~3.2x
	 * to ~4.6x at 8192). The cost is render latency for pattern/genome changes
	 * (≈ {@code depth × bufferSize / sampleRate}; automation/efx is unaffected — it is applied
	 * just-in-time in the mixdown) and ring memory
	 * ({@code depth × inputChannels × bufferSize}). Tune down if pattern-swap latency matters more
	 * than render headroom.
	 */
	public static int renderAheadSlots = 24;

	/**
	 * Kernel pre-warm (see {@link #createPdsl} setup): when set above zero, the runner
	 * render-sweeps the arrangement once before the clock starts (pattern render only —
	 * clock-neutral), forcing the batched renderer's lazily-compiled
	 * {@code (bucket, sourceLength, targetLength)} kernel shapes to compile off the
	 * real-time clock, bounded by this cap in seconds of arrangement.
	 *
	 * <p><b>Disabled by default ({@code 0}).</b> Render-sweeping the whole arrangement in setup is
	 * front-loaded real-time rendering, not a compile step: it made "setup" cost several times the
	 * playback duration and masked the true real-time performance. It also existed to hide a
	 * mid-stream first-evaluation stall that was later found to be a failed compile-time analysis
	 * rather than kernel compilation, and was fixed at the source
	 * ({@code BatchedPatternRenderer.sumNoteAxis}); the remaining mid-stream cost of a
	 * first-encountered shape is ordinary lazy compilation on the render-ahead producer thread,
	 * off the consumer's clock. If compile stalls ever prove problematic again, the correct
	 * replacement is a bounded compile-only warm that renders just enough buffers to reach each
	 * distinct kernel shape — never the whole arrangement.
	 */
	public static double preWarmMaxSeconds = 0.0;

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
	 * Wall time of each construction step of the Block-forward PDSL runner, keyed by step:
	 * {@link #BUILD_PREPARE_RENDER_BUFFERS}, {@link #BUILD_RENDER_OPS},
	 * {@link #BUILD_PARSE_PDSL}, {@link #BUILD_ARGS}, {@link #BUILD_LAYER},
	 * {@link #BUILD_MODEL_COMPILE} and {@link #BUILD_FIRST_FORWARD}. Each runner build adds
	 * {@link #buildStepCount(String)} entries per step, so a caller attributes one build's cost by differencing
	 * {@link org.almostrealism.io.MetricBase#getEntries()} before and after it. Durations are
	 * measured in nanoseconds and stored (and so read back) in seconds.
	 */
	public static final TimingMetric buildTime = AudioScene.console.timing("pdslRunnerBuild");

	/** {@link #buildTime} key for {@link AudioScene#prepareRenderBuffers}. */
	public static final String BUILD_PREPARE_RENDER_BUFFERS = "prepareRenderBuffers";

	/** {@link #buildTime} key for building the pattern render-ahead operation. */
	public static final String BUILD_RENDER_OPS = "renderOps";

	/** {@link #buildTime} key for parsing the mixdown PDSL program. */
	public static final String BUILD_PARSE_PDSL = "parsePdsl";

	/** {@link #buildTime} key for allocating the PDSL argument collections. */
	public static final String BUILD_ARGS = "buildArgs";

	/** {@link #buildTime} key for interpreting the PDSL layer into a {@link Block} graph. */
	public static final String BUILD_LAYER = "buildLayer";

	/** {@link #buildTime} key for {@link Model#compile(boolean)} of the mixdown block. */
	public static final String BUILD_MODEL_COMPILE = "modelCompile";

	/** {@link #buildTime} key for the throwaway forward pass that captures the output handle. */
	public static final String BUILD_FIRST_FORWARD = "firstForward";

	/**
	 * The {@link #buildTime} steps performed once per stereo side rather than once per build:
	 * each side has its own adapter arguments, layer, compiled model and handle-capturing forward.
	 */
	private static final List<String> BUILD_PER_SIDE_STEPS =
			List.of(BUILD_ARGS, BUILD_LAYER, BUILD_MODEL_COMPILE, BUILD_FIRST_FORWARD);

	/**
	 * Returns how many {@link #buildTime} entries one PDSL runner build records for {@code step}:
	 * one for the shared steps, and one per {@link ChannelInfo.StereoChannel stereo side} for
	 * the steps each side repeats.
	 *
	 * @param step a {@code BUILD_*} step key
	 * @return the number of entries a single build adds for that step
	 */
	public static int buildStepCount(String step) {
		return BUILD_PER_SIDE_STEPS.contains(step) ? ChannelInfo.StereoChannel.values().length : 1;
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
		return create(output, channels, bufferSize, null);
	}

	/**
	 * Builds a real-time runner, as {@link #create(MultiChannelAudioOutput, List, int)} does,
	 * and returns it with its live control handle.
	 *
	 * <p>When {@code options} is {@code null} the runner behaves exactly as the three-argument
	 * form: pattern positions follow the raw render cursor, the clock runs freely, and the
	 * ring depth is the global {@link #renderAheadSlots}. When options are supplied the render
	 * position is mapped through a {@link RealtimePositionWindow} (wrapping at the loop length),
	 * the automation clock is set to the pattern position of each buffer as it is mixed, and
	 * the ring depth may be overridden for this runner alone.</p>
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render, or {@code null} for all channels
	 * @param bufferSize frames per buffer
	 * @param options    the position window and ring depth, or {@code null} for the defaults
	 * @return the runner and its control handle
	 */
	public RealtimeRunner create(MultiChannelAudioOutput output, List<Integer> channels,
								 int bufferSize, RealtimeRunnerOptions options) {
		List<Integer> resolved = channels != null ? channels :
				IntStream.range(0, scene.getChannelCount()).boxed().collect(Collectors.toList());

		if (MixdownManager.enablePdslMixdown) {
			if (MixdownManager.supportsPdslMixdown(resolved)) {
				return createPdsl(output, resolved, bufferSize, options);
			}

			log("channels=" + resolved + " is outside the PDSL mixdown's supported"
					+ " configurations; using the CellList runner for this build");
		}

		return createCellList(output, resolved, bufferSize, options);
	}

	/**
	 * Returns the operation that, at the start of a tick, moves the automation clock to the
	 * pattern position of the buffer about to be mixed, so automation wraps with the position
	 * window. Returns a no-op when no options were supplied, which keeps the clock free-running
	 * as it always has been.
	 *
	 * @param window        the runner's position window
	 * @param options       the runner options, or {@code null}
	 * @param consumerFrame frames mixed since the last (re)start
	 * @return the clock synchronization operation
	 */
	private Supplier<Runnable> clockFollowsWindow(RealtimePositionWindow window,
												  RealtimeRunnerOptions options, int[] consumerFrame) {
		if (options == null) return new OperationList("No Clock Window");
		return () -> () -> syncClockToWindow(window, consumerFrame[0]);
	}

	/**
	 * Moves the automation clock to the pattern position the window maps the given consumer
	 * frame to. A {@link RealtimeRunner#retarget(long)} calls this directly so the clock is at
	 * the new position the moment it returns — before the next tick's {@link #clockFollowsWindow}
	 * synchronization runs — satisfying the retarget contract that the automation clock moves
	 * with the render position even when no options (and so no per-tick synchronization) are set.
	 *
	 * @param window        the runner's position window
	 * @param consumerFrame frames mixed since the last (re)start
	 */
	private void syncClockToWindow(RealtimePositionWindow window, int consumerFrame) {
		scene.getTimeManager().getClock().setFrame(
				scene.getTimeManager().positionForFrame(window.position(consumerFrame)));
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
	 * <p><b>Construction rollback.</b> The returned runner owns and destroys the per-buffer
	 * frame index allocated here. Because that ownership transfers only when the runner is
	 * successfully returned, the allocation is registered in a rollback list as it is made
	 * and released if a later construction step — {@link AudioScene#getCells}, the
	 * per-frame operation, or loop-body construction — throws, so a failed build strands no
	 * native memory until garbage collection. The {@link CellList} itself is the scene's
	 * active cell list (released by {@link AudioScene#destroy()}), so it is not part of the
	 * rollback.</p>
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render (already resolved, non-null)
	 * @param bufferSize frames per buffer
	 * @param options    the position window and ring depth, or {@code null} for the defaults
	 * @return a {@link RealtimeRunner} for real-time playback
	 */
	private RealtimeRunner createCellList(MultiChannelAudioOutput output,
										  List<Integer> channels, int bufferSize,
										  RealtimeRunnerOptions options) {
		final int[] currentFrame = {0};
		final long[] ticks = {0};
		RealtimePositionWindow window = options == null ? new RealtimePositionWindow() : options.createWindow();

		// Per-buffer frame index for WaveCell external frame control; tracks position
		// 0 to bufferSize-1 within each buffer.
		PackedCollection bufferFrameIndex = new PackedCollection(1);

		List<Runnable> rollback = new ArrayList<>();
		rollback.add(bufferFrameIndex::destroy);

		try {
		Producer<PackedCollection> bufferFrameProducer = cp(bufferFrameIndex);

		// Pattern position follows the arrangement timeline (wrapped at breaks),
		// matching the PDSL path's render-ahead position source.
		Cells cells = scene.getCells(output, channels, bufferSize,
				() -> (int) scene.getTimeManager().positionForFrame(window.position(currentFrame[0])),
				bufferFrameProducer);
		Supplier<Runnable> clockSync = clockFollowsWindow(window, options, currentFrame);

		// Per-frame operation (must be compilable)
		Supplier<Runnable> frameOp = cells.tick();

		// Create loop body: tick + increment buffer frame index
		OperationList loopBody = new OperationList("RealTime Per-Frame Body");
		loopBody.add(frameOp);
		// Increment buffer frame index: bufferFrameIndex = bufferFrameIndex + 1
		loopBody.add(a(1, cp(bufferFrameIndex), c(1.0).add(cp(bufferFrameIndex))));

		return new RealtimeRunner() {
			@Override
			public Supplier<Runnable> setup() {
				return cells.setup();
			}

			/**
			 * Moves the render position and the automation clock with it. The CellList path
			 * renders inline in each tick, so there is no ring to empty: the next tick renders
			 * at the new position.
			 */
			@Override
			public void retarget(long startFrame, Runnable whileStopped) {
				if (whileStopped != null) whileStopped.run();
				window.setStartFrame(startFrame);
				currentFrame[0] = 0;
				ticks[0] = 0;
				syncClockToWindow(window, currentFrame[0]);
			}

			/** Always {@code true}: this path renders inside the tick and never waits on a producer. */
			@Override
			public boolean slotReady() { return true; }

			/** One buffer ahead of {@link #buffersConsumed()}, since the next tick renders its own. */
			@Override
			public long buffersRendered() { return ticks[0] + 1; }

			@Override
			public long buffersConsumed() { return ticks[0]; }

			/**
			 * Always {@code 1}: the CellList path renders inline within each tick and owns no
			 * render-ahead ring, so a per-runner {@link RealtimeRunnerOptions#renderAheadSlots(int)}
			 * depth does not apply here and is deliberately ignored (the inline render is
			 * equivalent to a one-slot ring). The depth takes effect only on the PDSL path; see
			 * {@link RealtimeRunner#renderAheadSlots()}.
			 */
			@Override
			public int renderAheadSlots() { return 1; }

			@Override
			public RealtimePositionWindow getWindow() { return window; }

			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("AudioScene RealTime Runner Tick");

				// OUTSIDE LOOP: Reset buffer frame index and prepare pattern data
				tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));
				tick.add(clockSync);
				for (PatternAudioBuffer renderCell : scene.getRenderCells()) {
					tick.add(renderCell.prepareBatch());
				}

				// INSIDE LOOP: Compilable per-frame processing with frame index increment
				tick.add(loop(loopBody, bufferSize));

				// AFTER LOOP: Advance global frame position
				tick.add(() -> () -> {
					currentFrame[0] += bufferSize;
					ticks[0]++;
				});
				return tick;
			}

			/**
			 * Rewinds the frame counter, the cells, and the global clock so time-driven
			 * envelopes (volume, filter, AutomationManager outputs) start fresh on the
			 * next genome. Without the clock rewind, every genome after the first runs
			 * with the clock parked in the post-decay region of the volume envelope
			 * (gene 4 has scale = -1, a fade-out), so pattern channels come out
			 * near-silent.
			 */
			@Override
			public void reset() {
				currentFrame[0] = 0;
				ticks[0] = 0;
				cells.reset();
				scene.getTimeManager().getClock().setFrame(0);
			}

			/**
			 * Releases the per-buffer frame index this runner allocated and captured
			 * in its loop operations. The {@link CellList} this runner drives is the
			 * scene's active cell list (retained by {@link AudioScene#getCells} and
			 * released by {@link AudioScene#destroy()}), so it is left untouched here.
			 */
			@Override
			public void destroy() {
				bufferFrameIndex.destroy();
			}
		};
		} catch (RuntimeException | Error constructionFailure) {
			Collections.reverse(rollback);
			try {
				Destroyable.releaseAll(rollback);
			} catch (RuntimeException | Error cleanupFailure) {
				constructionFailure.addSuppressed(cleanupFailure);
			}
			throw constructionFailure;
		}
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
	 *       supported (see {@link MixdownManager#supportsPdslMixdown}); the adapter maps per-channel genome
	 *       reads to the selected scene channels.</li>
	 * </ul>
	 *
	 * <p><b>True stereo.</b> The path renders each stereo side independently: a LEFT and a
	 * RIGHT {@link MixdownManagerPdslAdapter} (built with {@link ChannelInfo.StereoChannel})
	 * each fold their side's per-channel balance-law pan gain into the channel's dry volume
	 * and sends, so one forward over the side's voicing rows produces that side's panned
	 * master. Row {@code 0}'s side (LEFT) is streamed to the LEFT writer and the other side
	 * to the RIGHT writer, so a hard-panned channel reaches only its side and stereo samples
	 * stay in stereo — exactly what the legacy {@code CellList} path does with a complete
	 * mixdown graph per side, plus pan. The two forwards cost about 2x a single-side forward;
	 * that is the price the earlier dual-mono shortcut deferred until pan existed, which it
	 * now does. The per-buffer automation granularity (one clock value per forward pass) is
	 * the documented trade-off versus the CellList path's per-frame automation.</p>
	 *
	 * <p><b>Wiring.</b> {@link AudioScene#prepareRenderBuffers} creates the render cells and
	 * consolidated buffer without building the Java mixdown {@link CellList} (which the PDSL
	 * path would only compile and discard). With efx enabled the consolidated buffer is
	 * filled in the order {@code [LEFT-MAIN(N), LEFT-WET(N), RIGHT-MAIN(N), RIGHT-WET(N)]},
	 * so the LEFT forward reads the first {@code 2*N} rows and the RIGHT forward the next
	 * {@code 2*N}; with efx off the regions are {@code [LEFT-MAIN(N), RIGHT-MAIN(N)]} and each
	 * forward reads its {@code N}. The render-ahead ring carries the whole region, so each
	 * forward reads its side as a zero-copy sub-view of one slot. The adapter receives the actual
	 * selected channel indices (not just a count) so per-channel genome reads resolve to the
	 * rendered channels: a single-channel {@code renderChannel(c)} selection maps bank
	 * position 0 to channel {@code c}'s genes, and the contiguous multi-channel selection is
	 * the identity mapping.</p>
	 *
	 * <p><b>Render-ahead.</b> A dedicated producer thread ({@link PatternRenderStream})
	 * renders successive buffers into a ring, driven by its own {@code renderFrame} cursor
	 * (distinct from the playback position), so the hot path only ever mixes
	 * already-rendered audio and never triggers a render. Each render round zeroes the whole
	 * consolidated buffer once — a single dispatch on a real (non-delegate) buffer — instead
	 * of clearing every cell's delegate region, which measured as the dominant per-round
	 * host-wait commits; the cells then sum into their already-zeroed regions
	 * ({@code prepareBatch(false)}). Producer-thread rendering is safe because the Metal
	 * command runner serializes GPU encoding, and it overlaps the render's Java
	 * orchestration with the consumer's GPU mixdown.</p>
	 *
	 * <p><b>Per-buffer automation.</b> The time-varying gene/clock-driven values (filter
	 * cutoffs, volume, efx automation, reverb send) live in collection slots the compiled
	 * graph reads every forward pass. Producer-valued model args are frozen at build time,
	 * so each tick re-evaluates those slots for the buffer's clock position — without this
	 * the filter sweeps would never engage.</p>
	 *
	 * <p><b>Output streaming.</b> Each side's {@code forward()} writes into a stable output
	 * buffer (the same instance every pass), so one build-time pass per side captures the
	 * handle the streaming loop reads. {@code WaveOutput}'s Writer contract is one frame per
	 * push (advancing the cursor) and {@code WaveOutput.write} gates on the minimum frame
	 * count across channels, so each frame the LEFT side's master is pushed to the LEFT
	 * writer and the RIGHT side's to the RIGHT writer.</p>
	 *
	 * <p><b>Construction rollback.</b> The returned runner owns and destroys every native
	 * resource allocated here (the per-buffer frame index, the compiled mixdown model, and
	 * the optional effects-sum scratch buffer). Because that ownership transfers only when
	 * the runner is successfully returned, each allocation is registered in a rollback list
	 * as it is made and the whole list is released if any later construction step — pattern
	 * buffer preparation, model compilation, output wiring, or the {@link PatternRenderStream}
	 * constructor — throws, so a failed build strands no native memory until garbage
	 * collection.</p>
	 *
	 * @param output     the audio output to write to
	 * @param channels   channel indices to render (already resolved, non-null)
	 * @param bufferSize frames per buffer
	 * @param options    the position window and ring depth, or {@code null} for the defaults
	 * @return a {@link RealtimeRunner} for real-time playback
	 */
	private RealtimeRunner createPdsl(MultiChannelAudioOutput output,
									  List<Integer> channels, int bufferSize,
									  RealtimeRunnerOptions options) {
		final int[] currentFrame = {0};
		RealtimePositionWindow window = options == null ? new RealtimePositionWindow() : options.createWindow();
		int ringDepth = options == null ? renderAheadSlots : options.resolveRenderAheadSlots();
		final long[] renderFrame = {0};
		int channelCount = channels.size();

		// Frame index within the current buffer, driven by the output-streaming loop
		PackedCollection bufferFrameIndex = new PackedCollection(1);

		List<Runnable> rollback = new ArrayList<>();
		rollback.add(bufferFrameIndex::destroy);

		try {
		// Pattern position follows the arrangement timeline, not the raw render
		// cursor: the reset schedule wraps it to zero at each break, ahead of playback.
		Supplier<Runnable> patternSetup = buildTime.measure(BUILD_PREPARE_RENDER_BUFFERS,
				() -> scene.prepareRenderBuffers(channels, bufferSize,
						() -> (int) scene.getTimeManager().positionForFrame(window.position(renderFrame[0]))));
		Supplier<Runnable> clockSync = clockFollowsWindow(window, options, currentFrame);

		boolean wetVoicing = MixdownManager.enableEfx;
		// Rows one side's forward reads (MAIN+WET when efx is on, MAIN only when off); the
		// render region holds both sides, so twice that.
		int perSideRows = wetVoicing ? channelCount * 2 : channelCount;
		int fullRegionRows = perSideRows * 2;
		String layerName = wetVoicing ? "mixdown_master_wet" : "mixdown_master";

		MixdownManager mixdown = scene.getMixdownManager();
		EfxManager efx = wetVoicing ? scene.getEfxManager() : null;
		int[] channelIndices = channels.stream().mapToInt(Integer::intValue).toArray();
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				channelIndices, bufferSize, scene.getSampleRate(),
				pdslFilterOrder, pdslWetLevel, pdslDelaySamples);

		PackedCollection consolidated = scene.getConsolidatedRenderBuffer();
		TraversalPolicy perSideShape = new TraversalPolicy(perSideRows, bufferSize);
		PackedCollection pdslInput =
				consolidated.range(new TraversalPolicy(fullRegionRows, bufferSize), 0);

		OperationList renderOps = new OperationList("AudioScene Pattern Render Ahead");
		renderOps.add(() -> () -> consolidated.clear());
		for (PatternAudioBuffer renderCell : scene.getRenderCells()) {
			renderOps.add(renderCell.prepareBatch(false));
		}
		Runnable renderOp = buildTime.measure(BUILD_RENDER_OPS, renderOps);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = buildTime.measure(BUILD_PARSE_PDSL,
				() -> loader.parseResource(MIXDOWN_PDSL_RESOURCE));

		// One complete mixdown per stereo side (see buildSide): LEFT reads the region's
		// first rows, RIGHT the next, each with its own state and pan gain.
		PdslSide left = buildSide(ChannelInfo.StereoChannel.LEFT, mixdown, efx, config,
				loader, program, layerName, perSideShape,
				pdslInput.range(perSideShape, 0), bufferSize, rollback);
		PdslSide right = buildSide(ChannelInfo.StereoChannel.RIGHT, mixdown, efx, config,
				loader, program, layerName, perSideShape,
				pdslInput.range(perSideShape, perSideRows * bufferSize), bufferSize, rollback);
		PdslSide[] sides = { left, right };

		// Each stereo channel's frame comes from that side's own forward output, so a
		// hard-panned channel reaches only the side its gain kept.
		Function<ChannelInfo.StereoChannel, Producer<PackedCollection>> masterFrame =
				stereo -> sides[stereo.getIndex()].masterFrame(bufferFrameIndex);

		OperationList outputLoopBody = new OperationList("PDSL Output Stream Body");
		outputLoopBody.add(output.pushMaster(masterFrame));

		// The health computation ends a render early from what these meters see
		// (clipping, sustained silence); without them every genome runs full length
		outputLoopBody.add(output.pushMeasures(ChannelInfo.Voicing.MAIN, masterFrame));
		if (left.hasEfxStem()) {
			outputLoopBody.add(output.pushMeasures(ChannelInfo.Voicing.WET,
					stereo -> sides[stereo.getIndex()].efxStemFrame(bufferFrameIndex)));
		}

		outputLoopBody.add(a(1, cp(bufferFrameIndex), c(1.0).add(cp(bufferFrameIndex))));

		// Whole buffers per tick, not per-frame pushes in the output loop, which
		// multiplied the loop's operation count enough to break the realtime budget
		OperationList stemAppends = new OperationList("PDSL Stem Appends");
		// Per-tick effects-sum scratch buffers (one per side) released on teardown.
		final List<PackedCollection> fxStemSum = new ArrayList<>();
		if (output.isStemsActive()) {
			if (left.hasStemChannels()) {
				for (int ch = 0; ch < channelCount; ch++) {
					final int patternChannel = channels.get(ch);
					final int row = ch;
					stemAppends.add(output.pushStem(patternChannel,
							stereo -> sides[stereo.getIndex()].stemChannelFrame(row)));
				}
			}

			if (channelCount == scene.getChannelCount()) {
				Producer<PackedCollection>[] effectsFrame = new Producer[] {
						left.effectsStemFrame(rollback, fxStemSum, stemAppends),
						right.effectsStemFrame(rollback, fxStemSum, stemAppends)
				};
				if (effectsFrame[0] != null) {
					stemAppends.add(output.pushStem(scene.getEffectsStemIndex(),
							stereo -> effectsFrame[stereo.getIndex()]));
				}
			}
		}

		// Created and registered only after every fallible construction step above has
		// succeeded, so a failure never leaves an orphaned stream. The runner owns and
		// destroys it; the scene stops it before releasing the state it renders from.
		PatternRenderStream renderStream = new PatternRenderStream(
				renderOp, renderFrame, pdslInput, ringDepth, fullRegionRows, bufferSize);
		scene.registerRenderStream(renderStream);

		return new RealtimeRunner() {
			/**
			 * Stops the producer cooperatively (an in-flight render ends at its next note and
			 * is never interrupted), moves the window so cursor frame {@code 0} is
			 * {@code startFrame}, moves the automation clock to that position, and restarts the
			 * producer with a one-buffer prefill. Both cursors restart at {@code 0}; the mixdown
			 * sides keep their state. The optional {@code whileStopped} action runs with no
			 * render in progress.
			 */
			@Override
			public void retarget(long startFrame, Runnable whileStopped) {
				renderStream.stop();
				if (whileStopped != null) whileStopped.run();
				window.setStartFrame(startFrame);
				renderFrame[0] = 0;
				currentFrame[0] = 0;
				syncClockToWindow(window, currentFrame[0]);
				renderStream.start(1);
			}

			@Override
			public boolean slotReady() {
				return renderStream.buffersRendered() > renderStream.buffersConsumed();
			}

			@Override
			public long buffersRendered() { return renderStream.buffersRendered(); }

			@Override
			public long buffersConsumed() { return renderStream.buffersConsumed(); }

			@Override
			public int renderAheadSlots() { return ringDepth; }

			@Override
			public RealtimePositionWindow getWindow() { return window; }

			/**
			 * One-time preparation: the render-cell setup from
			 * {@link AudioScene#prepareRenderBuffers} (which also renders the first buffer
			 * into the working input; the producer harmlessly re-renders it), a model
			 * reset, the optional bounded kernel pre-warm (see {@link #preWarmMaxSeconds}
			 * — the sweep advances only {@code renderFrame}, never the playback clock or
			 * mixdown state), and the producer start with a one-buffer prefill. The
			 * minimal prefill keeps setup from front-loading the whole render-ahead ring:
			 * the producer fills the ring during playback, and any early under-run is the
			 * honest cost of pattern rendering rather than work hidden in setup.
			 */
			@Override
			public Supplier<Runnable> setup() {
				OperationList setup = new OperationList("AudioScene PDSL RealTime Runner Setup");
				setup.add(patternSetup);
				setup.add(() -> () -> {
					left.reset();
					right.reset();
				});

				if (preWarmMaxSeconds > 0) {
					setup.add(() -> () -> {
						double arrangementFrames = scene.getTotalMeasures() * 4.0 * 60.0
								* scene.getSampleRate() / scene.getBPM();
						int sweepBuffers = (int) Math.min(
								Math.ceil(arrangementFrames / bufferSize),
								Math.ceil(preWarmMaxSeconds * scene.getSampleRate() / bufferSize));
						for (int b = 0; b < sweepBuffers; b++) {
							renderFrame[0] = b * (long) bufferSize;
							renderOp.run();
						}
						renderFrame[0] = 0;
					});
				}

				setup.add(() -> () -> renderStream.start(1));
				return setup;
			}

			/**
			 * The hot path contains only the mixdown: reset the per-buffer frame index,
			 * refresh the clock-driven automation slots for this buffer's position, run
			 * the compiled forward over the next already-rendered ring slot (the producer
			 * renders ahead, so this never triggers a render), stream each side's master to
			 * its writer, and advance the clock and playback position. The clock advances
			 * here (once per frame via a compiled loop) because this path never ticks the
			 * CellList that would otherwise drive it — without this, time-driven
			 * automation would stay frozen at frame 0.
			 */
			@Override
			public Supplier<Runnable> tick() {
				OperationList tick = new OperationList("AudioScene PDSL RealTime Runner Tick");
				tick.add(() -> () -> bufferFrameIndex.setMem(0, 0));
				tick.add(clockSync);
				tick.add(left.automationRefresh());
				tick.add(right.automationRefresh());

				tick.add(() -> () -> {
					PackedCollection slot = renderStream.awaitSlot();
					left.forward(slot.range(perSideShape, 0));
					right.forward(slot.range(perSideShape, perSideRows * bufferSize));
					renderStream.release();
				});

				if (!stemAppends.isEmpty()) tick.add(stemAppends);
				tick.add(loop(outputLoopBody, bufferSize));
				tick.add(loop(scene.getTimeManager().tick(), bufferSize));
				tick.add(() -> () -> currentFrame[0] += bufferSize);
				return tick;
			}

			/**
			 * Stops the producer, rewinds both frame cursors and the global clock, and
			 * resets the compiled model, so the next genome starts from frame 0 rather
			 * than the post-decay region of the previous run's envelopes.
			 */
			@Override
			public void reset() {
				renderStream.stop();
				currentFrame[0] = 0;
				renderFrame[0] = 0;
				for (PdslSide side : sides) {
					side.resetState();
				}
				scene.getTimeManager().getClock().setFrame(0);
			}

			/**
			 * Releases the native resources this runner created and owns: the
			 * {@link PatternRenderStream} (its render-ahead ring and compiled slot
			 * copies), each side's compiled mixdown {@link CompiledModel}, the per-buffer
			 * frame index captured in the output-streaming loop, and the per-side
			 * effects-sum scratch buffers allocated for the stems path. The consolidated
			 * render buffer is the scene's and is released by {@link AudioScene#destroy()},
			 * so it is left untouched here.
			 *
			 * <p>These releases are independent, so they are aggregated with
			 * {@link Destroyable#releaseAll(Iterable)}: a failure in any one of them still
			 * runs the rest, so a cleanup-path exception cannot strand the remaining native
			 * resources. The first failure is rethrown with any later ones suppressed.</p>
			 */
			@Override
			public void destroy() {
				List<Runnable> releases = new ArrayList<>();
				releases.add(renderStream::destroy);
				for (PdslSide side : sides) releases.add(side::destroy);
				releases.add(bufferFrameIndex::destroy);
				for (PackedCollection fx : fxStemSum) releases.add(fx::destroy);
				Destroyable.releaseAll(releases);
			}
		};
		} catch (RuntimeException | Error constructionFailure) {
			Collections.reverse(rollback);
			try {
				Destroyable.releaseAll(rollback);
			} catch (RuntimeException | Error cleanupFailure) {
				constructionFailure.addSuppressed(cleanupFailure);
			}
			throw constructionFailure;
		}
	}

	/**
	 * One stereo side's compiled mixdown together with the state a single forward reads
	 * and writes: the adapter (its pan gain folded into the side's volume and sends), its
	 * argument map (ring state, stem slots), its compiled model, the per-buffer automation
	 * refresh, and the stable output buffer the streaming loop reads.
	 */
	private static final class PdslSide implements CodeFeatures {
		/** The adapter that built {@link #args} with this side's pan gain folded in. */
		private final MixdownManagerPdslAdapter adapter;

		/** The side's argument map (ring state, automation slots, stem capture slots). */
		private final Map<String, Object> args;

		/** The compiled mixdown model for this side. */
		private final CompiledModel compiled;

		/** The per-buffer automation refresh re-evaluating this side's time-varying slots. */
		private final Supplier<Runnable> automationRefresh;

		/** The stable output buffer this side's {@code forward()} writes each pass. */
		private final PackedCollection output;

		/** Frames per buffer, used to carve per-buffer views out of the stem buffers. */
		private final int bufferSize;

		/**
		 * Holds one side's assembled mixdown state.
		 *
		 * @param adapter           the adapter that built {@code args} with this side's pan gain
		 * @param args              the side's argument map
		 * @param compiled          the compiled mixdown model
		 * @param automationRefresh the per-buffer automation refresh
		 * @param output            the stable output buffer the forward writes
		 * @param bufferSize        frames per buffer
		 */
		private PdslSide(MixdownManagerPdslAdapter adapter, Map<String, Object> args,
						 CompiledModel compiled, Supplier<Runnable> automationRefresh,
						 PackedCollection output, int bufferSize) {
			this.adapter = adapter;
			this.args = args;
			this.compiled = compiled;
			this.automationRefresh = automationRefresh;
			this.output = output;
			this.bufferSize = bufferSize;
		}

		/** Returns the per-buffer automation refresh for this side. */
		private Supplier<Runnable> automationRefresh() {
			return automationRefresh;
		}

		/** Resets the compiled model (setup-time, before the first buffer is streamed). */
		private void reset() {
			compiled.reset();
		}

		/** Runs this side's mixdown forward over its input view for the current buffer. */
		private void forward(PackedCollection input) {
			compiled.forward(input);
		}

		/** Rewinds the compiled model and the adapter's ring state for a fresh run. */
		private void resetState() {
			compiled.reset();
			adapter.resetState(args);
		}

		/** Releases the compiled model this side owns. */
		private void destroy() {
			compiled.destroy();
		}

		/**
		 * Frame producer reading this side's master output at {@code frameIndex}.
		 *
		 * @param frameIndex the per-buffer frame cursor the streaming loop advances
		 * @return the single-frame producer
		 */
		private Producer<PackedCollection> masterFrame(PackedCollection frameIndex) {
			return c(shape(1), p(output), p(frameIndex));
		}

		/** Returns {@code true} when this side captured an efx-bus stem. */
		private boolean hasEfxStem() {
			return args.get("stem_efx") != null;
		}

		/**
		 * Frame producer reading this side's efx-bus stem at {@code frameIndex}.
		 *
		 * @param frameIndex the per-buffer frame cursor the streaming loop advances
		 * @return the single-frame producer
		 */
		private Producer<PackedCollection> efxStemFrame(PackedCollection frameIndex) {
			return c(shape(1), p((PackedCollection) args.get("stem_efx")), p(frameIndex));
		}

		/** Returns {@code true} when this side captured per-channel stem buffers. */
		private boolean hasStemChannels() {
			return args.get("stem_channels") != null;
		}

		/**
		 * Buffer-length view of {@code row}'s per-channel stem for this side.
		 *
		 * @param row the stem row (bank position) to view
		 * @return the whole-buffer producer for that row
		 */
		private Producer<PackedCollection> stemChannelFrame(int row) {
			return p(((PackedCollection) args.get("stem_channels"))
					.range(new TraversalPolicy(bufferSize), row * bufferSize));
		}

		/**
		 * Producer of this side's combined effects stem — the efx-bus and reverb-tail
		 * stems summed into a fresh per-side scratch buffer (whose sum op is added to
		 * {@code stemAppends} and whose buffer is registered for release), or one stem
		 * directly when only one is present. Returns {@code null} when the side captured
		 * no effects stem.
		 *
		 * @param rollback    the construction rollback list (receives any scratch allocated)
		 * @param scratch     collects the per-side scratch buffers so the runner can release them
		 * @param stemAppends the per-tick stem operation list the sum is added to
		 * @return the effects-stem frame producer, or {@code null} when there is no effects stem
		 */
		private Producer<PackedCollection> effectsStemFrame(List<Runnable> rollback,
				List<PackedCollection> scratch, OperationList stemAppends) {
			PackedCollection stemEfx = (PackedCollection) args.get("stem_efx");
			PackedCollection stemReverb = (PackedCollection) args.get("stem_reverb");
			if (stemEfx != null && stemReverb != null) {
				PackedCollection fx = new PackedCollection(bufferSize);
				scratch.add(fx);
				rollback.add(fx::destroy);
				stemAppends.add(a("PDSL FX Stem Sum", cp(fx).each(),
						cp(stemEfx.range(new TraversalPolicy(bufferSize), 0))
								.add(cp(stemReverb.range(new TraversalPolicy(bufferSize), 0)))
								.each()));
				return p(fx);
			} else if (stemEfx != null) {
				return p(stemEfx.range(new TraversalPolicy(bufferSize), 0));
			} else if (stemReverb != null) {
				return p(stemReverb.range(new TraversalPolicy(bufferSize), 0));
			}
			return null;
		}
	}

	/**
	 * Builds one stereo side's mixdown: an adapter carrying the side's pan gain, its
	 * argument map, the compiled model, the automation refresh, and the stable output
	 * handle captured by a throwaway build-time forward over {@code sideInput}. The
	 * compiled model is registered in {@code rollback} so a later construction failure
	 * releases it.
	 *
	 * @param side       the stereo side this mixdown renders
	 * @param mixdown    the scene mixdown manager
	 * @param efx        the effects manager for the wet layer, or {@code null} for the base layer
	 * @param config     the structural configuration
	 * @param loader     the PDSL loader
	 * @param program    the parsed PDSL program
	 * @param layerName  the mixdown layer to build
	 * @param shape      the per-side model input shape
	 * @param sideInput  the side's input view for the handle-capturing forward
	 * @param bufferSize frames per buffer
	 * @param rollback   the construction rollback list
	 * @return the assembled per-side state
	 */
	private PdslSide buildSide(ChannelInfo.StereoChannel side, MixdownManager mixdown,
							   EfxManager efx, MixdownManagerPdslAdapter.Config config,
							   PdslLoader loader, PdslNode.Program program, String layerName,
							   TraversalPolicy shape, PackedCollection sideInput,
							   int bufferSize, List<Runnable> rollback) {
		MixdownManagerPdslAdapter adapter =
				new MixdownManagerPdslAdapter(mixdown, efx, config, side);
		Map<String, Object> args = buildTime.measure(BUILD_ARGS, adapter::buildArgsMap);
		CompiledModel compiled = compileMixdownModel(loader, program, layerName, shape, args);
		rollback.add(compiled::destroy);
		Supplier<Runnable> automationRefresh = adapter.automationRefresh(args);
		PackedCollection output = buildTime.measure(BUILD_FIRST_FORWARD,
				() -> compiled.forward(sideInput));
		return new PdslSide(adapter, args, compiled, automationRefresh, output, bufferSize);
	}

	/**
	 * Compiles a mixdown layer into a {@link CompiledModel} for the given input shape and args.
	 *
	 * <p>The mixdown model is inference-only — every tick calls {@code forward()} over the
	 * whole buffer and no backward pass ever runs — so it is compiled with
	 * {@code backprop=false}. That disables per-layer input tracking (each stage's entry
	 * cell passes input straight through, dropping one input-record copy dispatch per
	 * layer, which measurably dominated the forward cost) and skips building the unused
	 * backward graph. Output tracking stays on: branch/merge blocks ({@code accum_blocks},
	 * {@code route}) and the model-output capture read each stage's materialized output
	 * buffer.</p>
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
		Block block = buildTime.measure(BUILD_LAYER,
				() -> loader.buildLayer(program, layerName, inputShape, args));
		Model model = new Model(inputShape);
		model.add(block);
		return buildTime.measure(BUILD_MODEL_COMPILE, () -> model.compile(false));
	}
}
