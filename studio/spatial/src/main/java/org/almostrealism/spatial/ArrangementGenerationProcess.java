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

package org.almostrealism.spatial;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.io.Console;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.notes.SceneAudioNode;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.studio.persistence.MigrationClassLoader;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The arrangement generation process: runs {@link AudioSceneOptimizer} cycles over a
 * scene, attaches each delivered {@link AudioHealthScore} to its {@link GenomicNetwork}
 * record, and persists the records between runs. The process depends on no UI toolkit,
 * so an interactive client and a headless harness or test execute the identical flow,
 * observing score delivery, record attachment, and the persistence round trip.
 *
 * <p>Presentation concerns remain with the caller through the listener surface:
 * score attachment, per-cycle progress, completion, and errors are reported via the
 * configured callbacks, and record display names are produced by the configured
 * naming function.</p>
 */
public class ArrangementGenerationProcess implements ConsoleFeatures, Destroyable {

	/**
	 * How far the current optimization cycle has got: how many health
	 * evaluations it has delivered, out of how many it will deliver.
	 *
	 * <p>Both numbers describe the cycle in progress, not the run: a cycle
	 * evaluates the whole population, so a new cycle starts over at zero.</p>
	 *
	 * @param completed evaluations delivered so far this cycle
	 * @param total     evaluations the cycle will deliver in all
	 */
	public record Progress(int completed, int total) {
		/** Returns the evaluations this cycle has yet to deliver. */
		public int remaining() { return Math.max(0, total - completed); }

		/**
		 * Returns progress as a fraction of the cycle, or {@code 0.0} before
		 * the size of the cycle is known.
		 *
		 * <p>Capped at one. {@link #completed()} and {@link #total()} are left
		 * as they are, so a count that has outrun its total stays visible to
		 * anyone reading the numbers even though the fraction cannot show
		 * it.</p>
		 */
		public double fraction() {
			return total <= 0 ? 0.0 : Math.min(1.0, completed / (double) total);
		}

		/** Returns whether the size of the cycle is known yet. */
		public boolean isKnown() { return total > 0; }
	}

	/** Destination for persisted population records. */
	private final String networksFile;

	/**
	 * The population records, one per genome, carrying scores once delivered.
	 *
	 * <p>Guarded by {@link #recordLock}. The list is rebuilt on the thread
	 * running the evaluations and read by whatever thread is displaying it, so
	 * it is never handed out: {@link #getNetworks()} returns a snapshot. Every
	 * rebuild is a single action under the lock, so a reader sees the records
	 * as they were before it or as they are after it, never midway through.</p>
	 */
	private final List<GenomicNetwork> networks;

	/** Guards {@link #networks} against the reader and the writer overlapping. */
	private final Object recordLock = new Object();

	/**
	 * The record whose audio is being rendered right now, or {@code null}
	 * between evaluations.
	 */
	private volatile GenomicNetwork rendering;

	/** The cloned scene the current run renders with. */
	private AudioScene<?> localScene;

	/** The optimizer for the current run. */
	private AudioSceneOptimizer optimizer;

	/** Number of completed optimization cycles. */
	private int iterationCount;

	/** Health evaluations delivered so far in the current cycle. */
	private volatile int completedThisCycle;

	/** Produces display names for records (used for their audio nodes). */
	private Function<GenomicNetwork, String> networkNaming;

	/** Receives wave detail metadata from each health evaluation, or {@code null}. */
	private Consumer<WaveDetails> waveDetailsProcessor;

	/** Notified after a delivered score is attached to its record, or {@code null}. */
	private BiConsumer<GenomicNetwork, AudioHealthScore> scoreListener;

	/** Receives evaluation errors, or {@code null} to log them here. */
	private Consumer<Exception> errorListener;

	/** Invoked at the start of each optimization cycle, or {@code null}. */
	private Runnable cycleListener;

	/** Invoked when each optimization cycle completes, or {@code null}. */
	private Runnable completionListener;

	/** Notified when rendering moves to another record, or {@code null}. */
	private Consumer<GenomicNetwork> renderListener;

	/**
	 * Creates a process persisting population records to the given file.
	 *
	 * @param networksFile destination for persisted records
	 */
	public ArrangementGenerationProcess(String networksFile) {
		this.networksFile = networksFile;
		this.networks = new ArrayList<>();
		this.networkNaming = n -> "Network " + n.getIndex();
	}

	/** Sets the display-name function used when creating record audio nodes. */
	public void setNetworkNaming(Function<GenomicNetwork, String> networkNaming) {
		this.networkNaming = networkNaming;
	}

	/** Sets the consumer of wave detail metadata produced during health evaluation. */
	public void setWaveDetailsProcessor(Consumer<WaveDetails> waveDetailsProcessor) {
		this.waveDetailsProcessor = waveDetailsProcessor;
	}

	/** Sets the listener notified after each delivered score attaches to its record. */
	public void setScoreListener(BiConsumer<GenomicNetwork, AudioHealthScore> scoreListener) {
		this.scoreListener = scoreListener;
	}

	/** Sets the receiver of evaluation errors. */
	public void setErrorListener(Consumer<Exception> errorListener) {
		this.errorListener = errorListener;
	}

	/** Sets the callback invoked at the start of each optimization cycle. */
	public void setCycleListener(Runnable cycleListener) {
		this.cycleListener = cycleListener;
	}

	/** Sets the callback invoked when each optimization cycle completes. */
	public void setCompletionListener(Runnable completionListener) {
		this.completionListener = completionListener;
	}

	/**
	 * Sets the listener notified when rendering moves to another record, with
	 * the record now being rendered, or {@code null} when a cycle has finished
	 * and nothing is being rendered.
	 */
	public void setRenderListener(Consumer<GenomicNetwork> renderListener) {
		this.renderListener = renderListener;
	}

	/**
	 * Returns the records as they stand, as an immutable snapshot.
	 *
	 * <p>The records themselves are shared - a score delivered later attaches
	 * to the record a caller is already holding - but the list is not. The
	 * process rebuilds its list whenever it falls out of step with the
	 * population, so a caller keeping the returned list would be reading a set
	 * of records that is no longer being written to. Ask again rather than
	 * remembering the answer.</p>
	 *
	 * @return the records at the moment of the call
	 */
	public List<GenomicNetwork> getNetworks() {
		synchronized (recordLock) {
			return List.copyOf(networks);
		}
	}

	/**
	 * Returns the record whose audio is being rendered right now, or
	 * {@code null} when nothing is.
	 *
	 * <p>Evaluations run one at a time, so at most one record is ever being
	 * rendered. During a cycle this is non-null; it returns to {@code null}
	 * when the cycle finishes.</p>
	 *
	 * @return the record being rendered, or {@code null}
	 */
	public GenomicNetwork getRendering() { return rendering; }

	/**
	 * Returns how far the current cycle has got.
	 *
	 * <p>Read this rather than counting scored records. The two are not the
	 * same number: a delivered score attaches to every record carrying its
	 * signature, and the record list is replaced outright whenever it falls out
	 * of step with the population, so a caller holding an earlier list would be
	 * counting something no longer being written to.</p>
	 *
	 * @return the progress of the cycle in progress
	 */
	public Progress getProgress() {
		return new Progress(completedThisCycle, cycleSize());
	}

	/**
	 * Returns how many health evaluations a cycle delivers, which is one per
	 * member of the population being optimized.
	 *
	 * <p>Falls back to the number of records before a run has been prepared,
	 * when there is no population to ask, and reports zero when neither is
	 * known rather than inventing a total.</p>
	 *
	 * @return the number of evaluations in a cycle
	 */
	private int cycleSize() {
		if (optimizer != null && optimizer.getPopulation() != null) {
			return optimizer.getPopulation().size();
		}

		synchronized (recordLock) {
			return networks.size();
		}
	}

	/**
	 * Replaces the population records, for callers that manage the record list
	 * directly (trimming, reordering).
	 *
	 * <p>The records are copied in. The caller's list stays its own, and may be
	 * the snapshot {@link #getNetworks()} returned, so this is safe to call with
	 * a list derived from the process's own records.</p>
	 *
	 * @param networks the records to adopt
	 */
	public void setNetworks(List<GenomicNetwork> networks) {
		replaceRecords(new ArrayList<>(networks));
	}

	/**
	 * Adopts a new set of records as one action, so a reader never sees the
	 * list part-way through being rebuilt.
	 *
	 * @param replacement the records to adopt, already detached from any caller
	 */
	private void replaceRecords(List<GenomicNetwork> replacement) {
		synchronized (recordLock) {
			networks.clear();
			networks.addAll(replacement);
		}
	}

	/** Returns the optimizer for the current run, or {@code null} before {@link #prepare}. */
	public AudioSceneOptimizer getOptimizer() { return optimizer; }

	/** Returns the cloned scene for the current run, or {@code null} before {@link #prepare}. */
	public AudioScene<?> getLocalScene() { return localScene; }

	/** Returns the number of completed optimization cycles. */
	public int getIterationCount() { return iterationCount; }

	/**
	 * Persists the current records, then builds an optimizer over a clone of the
	 * given scene and wires score delivery, error, and cycle callbacks. The
	 * optimizer is available from {@link #getOptimizer()} for configuration
	 * before {@link #run()}.
	 *
	 * <p>Finally calls {@code optimizer.init()} to wire the master output file
	 * and the per-channel stem files on the health computation. Without this
	 * step every rendered arrangement reports no stems, because the stem
	 * {@link org.almostrealism.audio.WaveOutput}s resolve to a null file and
	 * never record a path for the health score to carry. The standalone runner
	 * ({@link AudioSceneOptimizer#run}) calls this immediately after
	 * {@link AudioSceneOptimizer#build} for the same reason.</p>
	 *
	 * @param scene  the scene to optimize (cloned; the original is untouched)
	 * @param cycles the number of optimization cycles for {@link #run()}
	 * @throws IOException if the records cannot be persisted
	 */
	public void prepare(AudioScene<?> scene, int cycles) throws IOException {
		saveNetworks();

		localScene = scene.clone();
		optimizer = AudioSceneOptimizer.build(localScene, cycles);
		if (waveDetailsProcessor != null)
			optimizer.setWaveDetailsProcessor(waveDetailsProcessor);
		optimizer.setHealthListener(this::attachScore);
		optimizer.setEvaluationListener(signature -> {
			if (signature == null) {
				endRender();
			} else {
				beginRender(signature);
			}
		});
		if (errorListener != null) optimizer.setErrorListener(errorListener);
		optimizer.setCycleListener(() -> {
			completedThisCycle = 0;
			if (cycleListener != null) cycleListener.run();
		});
		optimizer.setCompletionListener(() -> {
			iterationCount++;
			if (completionListener != null) completionListener.run();
		});

		optimizer.init();
	}

	/**
	 * Runs the prepared optimizer, then persists and reloads the records so the
	 * process ends in the same state a fresh session would load.
	 *
	 * @throws IOException if the records cannot be persisted
	 */
	public void run() throws IOException {
		optimizer.run();
		saveNetworks();
		loadNetworks();
	}

	/**
	 * Convenience for {@link #prepare} followed by {@link #run()}.
	 *
	 * @param scene  the scene to optimize
	 * @param cycles the number of optimization cycles
	 * @throws IOException if the records cannot be persisted
	 */
	public void iterate(AudioScene<?> scene, int cycles) throws IOException {
		prepare(scene, cycles);
		run();
	}

	/**
	 * Attaches a delivered health score to the record whose genome carries the
	 * given signature. Delivery is logged before anything else so a failure in
	 * attachment can never make the delivery itself invisible; records without
	 * a genome are skipped rather than treated as fatal, and a score matching
	 * no record is reported.
	 *
	 * @param signature the delivered genome signature
	 * @param score     the delivered health score
	 */
	protected void attachScore(String signature, AudioHealthScore score) {
		completedThisCycle++;

		log("scoreStems=" + (score.getStems() == null ?
				"null" : score.getStems().size()) + " signature=" + signature
				+ " completed=" + completedThisCycle + "/" + cycleSize());

		GenomicNetwork matched = null;

		for (GenomicNetwork g : recordsFor(signature)) {
			g.setHealthScore(score);

			SceneAudioNode node = new SceneAudioNode(g.getKey(),
					networkNaming.apply(g), localScene, null);
			node.setRange(0, localScene.getTotalMeasures());
			g.setSceneAudioNode(node);

			matched = g;
		}

		if (matched == null) {
			warn("No record found for generated health score " + signature);
		}

		if (scoreListener != null) scoreListener.accept(matched, score);
	}

	/**
	 * Records that the genome carrying the given signature is now being
	 * rendered, and notifies the render listener.
	 *
	 * <p>This arrives before the render rather than after it, so it is the
	 * first thing in a cycle to name a genome. That makes it, rather than the
	 * first delivered score, where the records are brought back into step with
	 * a population that breeding has moved on.</p>
	 *
	 * @param signature the signature of the genome being rendered
	 */
	protected void beginRender(String signature) {
		GenomicNetwork record = recordsFor(signature).stream()
				.findFirst().orElse(null);

		if (record == null) {
			warn("No record found for rendering genome " + signature);
		}

		rendering = record;
		if (renderListener != null) renderListener.accept(record);
	}

	/**
	 * Marks rendering as finished, so nothing is reported as in progress
	 * between cycles.
	 */
	protected void endRender() {
		rendering = null;
		if (renderListener != null) renderListener.accept(null);
	}

	/**
	 * Returns the records carrying the given genome signature, rebuilding the
	 * records from the population first if none of them does.
	 *
	 * <p>Records outlive the population they were built from: they carry the
	 * previous cycle's scores until breeding moves the population on and a
	 * signature arrives that none of them answers to.</p>
	 *
	 * @param signature the genome signature to look for
	 * @return the records carrying it, empty if the rebuild found none either
	 */
	private List<GenomicNetwork> recordsFor(String signature) {
		if (!carriesSignature(signature)) refreshNetworks();

		return getNetworks().stream()
				.filter(g -> g.getGenome() != null &&
						Objects.equals(g.getGenome().signature(), signature))
				.collect(Collectors.toList());
	}

	/** Returns whether any record carries the given genome signature. */
	private boolean carriesSignature(String signature) {
		return getNetworks().stream()
				.map(GenomicNetwork::getGenome)
				.filter(Objects::nonNull)
				.anyMatch(g -> Objects.equals(g.signature(), signature));
	}

	/**
	 * Rebuilds the records from the optimizer's current population, one record
	 * per genome. Existing records (and their scores) are discarded, matching
	 * the behavior the app has always had when its records fall out of sync
	 * with the population.
	 */
	public void refreshNetworks() {
		if (optimizer == null) return;

		List<GenomicNetwork> rebuilt = new ArrayList<>();

		AudioScenePopulation population = (AudioScenePopulation) optimizer.getPopulation();
		for (Genome<PackedCollection> genome : population.getGenomes()) {
			rebuilt.add(new GenomicNetwork(rebuilt.size(), genome));
		}

		replaceRecords(rebuilt);
	}

	/**
	 * Loads the persisted records, replacing the current list. A missing file
	 * yields an empty list; a failure to decode is reported rather than being
	 * silently indistinguishable from a fresh start.
	 */
	public void loadNetworks() {
		if (!new File(networksFile).exists()) {
			replaceRecords(new ArrayList<>());
			return;
		}

		try (FileInputStream in = new FileInputStream(networksFile);
			 InputStream migrated = MigrationClassLoader.migrateStream(in);
			 XMLDecoder dec = new XMLDecoder(migrated)) {
			List<GenomicNetwork> loaded = (List<GenomicNetwork>) dec.readObject();
			replaceRecords(loaded);

			log("loadedNetworks=" + loaded.size() +
					" scoredNetworks=" + loaded.stream()
							.filter(g -> g.getHealthScore() != null).count() +
					" stemNetworks=" + loaded.stream()
							.filter(g -> g.getHealthScore() != null &&
									g.getHealthScore().getStems() != null &&
									!g.getHealthScore().getStems().isEmpty()).count());
		} catch (Exception e) {
			warn("Unable to load population records from " + networksFile, e);
			replaceRecords(new ArrayList<>());
		}
	}

	/**
	 * Persists the current records, and mirrors their genomes into the shared
	 * population file that {@link AudioSceneOptimizer} reads its population from,
	 * so the next run evaluates the genomes these records describe.
	 *
	 * @throws IOException if the records cannot be written
	 */
	public void saveNetworks() throws IOException {
		List<GenomicNetwork> saved = getNetworks();

		try (FileOutputStream out = new FileOutputStream(networksFile);
			 XMLEncoder enc = new XMLEncoder(out)) {
			enc.writeObject(new ArrayList<>(saved));
		}

		AudioScenePopulation.store(saved.stream()
						.map(GenomicNetwork::getGenome)
						.filter(Objects::nonNull)
						.collect(Collectors.toList()),
				new FileOutputStream(AudioSceneOptimizer.POPULATION_FILE));
	}

	@Override
	public void destroy() {
		if (optimizer != null) {
			optimizer.destroy();
			optimizer = null;
		}
		if (localScene != null) {
			localScene.destroy();
			localScene = null;
		}
	}

	@Override
	public Console console() { return CellFeatures.console; }
}
