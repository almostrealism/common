/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio;

import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveDetailsJob;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.audio.similarity.IncrementalSimilarityComputation;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.concurrent.SuspendableThreadPoolExecutor;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages a collection of audio files with metadata analysis and similarity computation.
 *
 * <p>AudioLibrary provides asynchronous loading and analysis of audio files, computing
 * {@link WaveDetails} including frequency analysis and feature extraction for each file.
 * It supports background processing with priority queuing, progress tracking, and
 * similarity comparison between audio samples.</p>
 *
 * <h2>Key-Identifier Architecture</h2>
 * <p>AudioLibrary uses a two-level identification system:</p>
 * <ul>
 *   <li><b>Key</b> (file path): The actual filesystem path to the audio file,
 *       obtained via {@link WaveDataProvider#getKey()}</li>
 *   <li><b>Identifier</b> (content hash): An MD5 hash of the file contents,
 *       obtained via {@link WaveDataProvider#getIdentifier()}</li>
 * </ul>
 *
 * <p>This separation allows:</p>
 * <ul>
 *   <li>Content-based deduplication (same audio = same identifier)</li>
 *   <li>File movement detection (path changes but identifier stays same)</li>
 *   <li>Efficient storage (protobuf stores identifier, file path resolved at runtime)</li>
 * </ul>
 *
 * <h2>Internal Data Structures</h2>
 * <ul>
 *   <li><b>identifiers</b> map: key (file path) to identifier (MD5 hash)</li>
 *   <li><b>info</b> map: identifier to {@link WaveDetails}</li>
 * </ul>
 *
 * <h2>Resolving Identifier to File Path</h2>
 * <p>To get the file path for a given identifier:</p>
 * <pre>{@code
 * WaveDataProvider provider = library.find(identifier);
 * if (provider != null) {
 *     String filePath = provider.getKey();
 * }
 * }</pre>
 * <p>The {@link #find(String)} method searches the file tree for a provider whose
 * identifier matches, then returns it so {@link WaveDataProvider#getKey()} can
 * retrieve the file path.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Asynchronous file scanning and analysis with configurable priority</li>
 *   <li>Automatic similarity computation between audio samples</li>
 *   <li>Progress tracking and error reporting via listeners</li>
 *   <li>Persistent metadata storage for faster subsequent access</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create library from a directory
 * AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);
 *
 * // Start background analysis
 * library.refresh().thenRun(() -> {
 *     // Analysis complete
 *     library.getAllDetails().forEach(d -> {
 *         // Get the file path for this sample
 *         WaveDataProvider provider = library.find(d.getIdentifier());
 *         String filePath = provider != null ? provider.getKey() : "unknown";
 *         System.out.println(filePath + ": " + d.getFrameCount() + " frames");
 *     });
 * });
 *
 * // Get details for a specific file
 * WaveDetails details = library.getDetailsAwait(new FileWaveDataProvider("/path/to/file.wav"));
 * }</pre>
 *
 * <h2>Priority Levels</h2>
 * <ul>
 *   <li>{@link #BACKGROUND_PRIORITY} (0.0) - Low priority background scanning</li>
 *   <li>{@link #DEFAULT_PRIORITY} (0.5) - Normal user-initiated requests</li>
 *   <li>{@link #HIGH_PRIORITY} (1.0) - Immediate processing for blocking requests</li>
 * </ul>
 *
 * @see WaveDetails
 * @see WaveDetailsFactory
 * @see FileWaveDataProviderTree
 */
public class AudioLibrary implements ConsoleFeatures {
	public static double BACKGROUND_PRIORITY = 0.0;
	public static double DEFAULT_PRIORITY = 0.5;
	public static double HIGH_PRIORITY = 1.0;

	/** The file tree providing access to audio files in the library directory. */
	private final FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> root;

	/** Target sample rate for audio analysis. */
	private final int sampleRate;

	/**
	 * Maps file path (key) to content identifier (MD5 hash).
	 * <p>This mapping is built as files are processed and allows resolving
	 * from a known file path to its content identifier.</p>
	 */
	private final Map<String, String> identifiers;

	/**
	 * Maps content identifier (MD5 hash) to {@link WaveDetails}.
	 * <p>This is the primary storage for analyzed audio metadata. The identifier
	 * is used as the key because it represents the actual content, allowing
	 * deduplication when the same file exists at multiple paths.</p>
	 */
	private final Map<String, WaveDetails> info;

	private final WaveDetailsFactory factory;
	private final PriorityBlockingQueue<WaveDetailsJob> queue;
	private int totalJobs;

	private DoubleConsumer progressListener;
	private Consumer<Exception> errorListener;
	private SuspendableThreadPoolExecutor executor;

	/**
	 * The most recent {@link CompletableFuture} returned by {@link #refresh()}.
	 * Used by {@link #awaitRefresh()} to allow callers to wait until all queued
	 * feature-extraction jobs have completed before starting work that depends
	 * on stable similarity data.
	 */
	private volatile CompletableFuture<Void> latestRefresh =
			CompletableFuture.completedFuture(null);

	/** Persisted prototype index loaded from protobuf at startup. */
	private volatile PrototypeIndexData prototypeIndex;

	public AudioLibrary(File root, int sampleRate) {
		this(new FileWaveDataProviderNode(root), sampleRate);
	}

	public AudioLibrary(FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> root, int sampleRate) {
		this.root = root;
		this.sampleRate = sampleRate;
		this.identifiers = new HashMap<>();
		this.info = new HashMap<>();
		this.factory = new WaveDetailsFactory(sampleRate);
		this.queue = new PriorityBlockingQueue<>(100,
				Comparator.comparing(WaveDetailsJob::getPriority).reversed());

		start();
	}

	public void start() {
		if (executor != null) return;

		executor = new SuspendableThreadPoolExecutor(1, 1,
				60, TimeUnit.MINUTES, queue);
		executor.setPriority(job -> ((WaveDetailsJob) job).getPriority());
	}

	public boolean isPaused() {
		return executor == null || executor.getPriorityThreshold() >= HIGH_PRIORITY;
	}

	public void pause() {
		if (executor != null) {
			executor.setPriorityThreshold(HIGH_PRIORITY);
		}
	}

	public void resume() {
		executor.resumeAllTasks();
	}

	/**
	 * Returns a {@link CompletableFuture} that completes when the most recent
	 * {@link #refresh()} has finished processing all queued jobs. If no refresh
	 * is in progress, the returned future is already complete.
	 *
	 * <p>Prototype discovery should call {@code awaitRefresh().join()} before
	 * computing similarities or building a similarity graph, to avoid racing
	 * against {@code resetSimilarities()} which is called by {@code refresh()}
	 * when incomplete files are found.</p>
	 *
	 * @return a future that completes when the current refresh finishes
	 */
	public CompletableFuture<Void> awaitRefresh() {
		return latestRefresh;
	}

	/**
	 * Returns the persisted prototype index, or null if none has been loaded.
	 *
	 * @see #setPrototypeIndex(PrototypeIndexData)
	 */
	public PrototypeIndexData getPrototypeIndex() {
		return prototypeIndex;
	}

	/**
	 * Sets the prototype index, typically after loading from protobuf or
	 * after background recomputation.
	 *
	 * @param prototypeIndex the index to store, or null to clear
	 */
	public void setPrototypeIndex(PrototypeIndexData prototypeIndex) {
		this.prototypeIndex = prototypeIndex;
	}

	public FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> getRoot() {
		return root;
	}

	public int getSampleRate() { return sampleRate; }

	public DoubleConsumer getProgressListener() { return progressListener; }
	public void setProgressListener(DoubleConsumer progressListener) {
		this.progressListener = progressListener;
	}

	public Consumer<Exception> getErrorListener() { return errorListener; }
	public void setErrorListener(Consumer<Exception> errorListener) {
		this.errorListener = errorListener;
	}

	public WaveDetailsFactory getWaveDetailsFactory() { return factory; }

	public int getTotalJobs() { return totalJobs; }
	public int getPendingJobs() { return queue.size(); }

	public Stream<WaveDetails> allDetails() {
		return new ArrayList<>(info.keySet()).stream().map(info::get).filter(Objects::nonNull);
	}

	public Collection<WaveDetails> getAllDetails() {
		return allDetails().toList();
	}

	/**
	 * Creates an {@link AudioSimilarityGraph} from this library's details.
	 *
	 * <p>The returned graph treats each sample as a node and the pre-existing
	 * similarity scores (from {@link WaveDetails#getSimilarities()}) as edge
	 * weights. <b>This method does not compute any missing similarity data.</b>
	 * If a sample's similarity map is empty, it will appear as an isolated node
	 * with no edges.</p>
	 *
	 * <p>Callers that need a fully-connected graph should call
	 * {@link #computeSimilarities(WaveDetails)} for each detail before building
	 * the graph. That method is incremental: it only computes missing pairs,
	 * so it is safe and cheap to call even when most similarities are already
	 * present.</p>
	 *
	 * <h3>Example (assuming similarities are pre-computed)</h3>
	 * <pre>{@code
	 * AudioSimilarityGraph graph = library.toSimilarityGraph();
	 * double[] ranks = pageRank(graph, 0.85, 50);
	 * int[] communities = louvain(graph, 1.0);
	 * }</pre>
	 *
	 * <h3>Example (ensuring similarities are computed first)</h3>
	 * <pre>{@code
	 * library.getAllDetails().forEach(library::computeSimilarities);
	 * AudioSimilarityGraph graph = library.toSimilarityGraph();
	 * }</pre>
	 *
	 * @return a new AudioSimilarityGraph containing all details from this library
	 * @see AudioSimilarityGraph
	 * @see #computeSimilarities(WaveDetails)
	 */
	public AudioSimilarityGraph toSimilarityGraph() {
		return new AudioSimilarityGraph(getAllDetails());
	}

	/**
	 * Retrieves the {@link WaveDetails} for the given content identifier.
	 *
	 * <p>The identifier is an MD5 hash of the file contents, not the file path.
	 * To get the file path for a sample, use {@link #find(String)} to get the
	 * {@link WaveDataProvider}, then call {@link WaveDataProvider#getKey()}.</p>
	 *
	 * @param identifier the content identifier (MD5 hash)
	 * @return the WaveDetails for this identifier, or null if not found
	 * @see #find(String)
	 */
	public WaveDetails get(String identifier) { return info.get(identifier); }

	/**
	 * Finds the {@link WaveDataProvider} for the given content identifier by
	 * searching the file tree.
	 *
	 * <p>This method is essential for resolving an identifier back to a file path.
	 * It searches all files in the library's file tree and returns the provider
	 * whose content identifier matches.</p>
	 *
	 * <h3>Usage: Getting file path from identifier</h3>
	 * <pre>{@code
	 * WaveDetails details = library.get(identifier);
	 * WaveDataProvider provider = library.find(identifier);
	 * if (provider != null) {
	 *     String filePath = provider.getKey();  // The actual file path
	 * }
	 * }</pre>
	 *
	 * <p><b>Note:</b> This method requires the file tree to be populated. If
	 * loading from protobuf without a corresponding file tree, this method
	 * will return null even for valid identifiers.</p>
	 *
	 * @param identifier the content identifier (MD5 hash) to search for
	 * @return the WaveDataProvider for this identifier, or null if not found
	 *         in the current file tree
	 * @see WaveDataProvider#getKey()
	 * @see WaveDataProvider#getIdentifier()
	 */
	public WaveDataProvider find(String identifier) {
		return root.children()
				.map(Supplier::get)
				.filter(Objects::nonNull)
				.filter(f -> Objects.equals(identifier, f.getIdentifier()))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Adds pre-computed {@link WaveDetails} to this library.
	 *
	 * <p>The details are indexed by their content identifier. This method is
	 * typically called when loading pre-computed data from external sources.</p>
	 *
	 * <p>After including details, use {@link #find(String)} to resolve identifiers
	 * back to file paths (requires the file tree to be populated with the
	 * corresponding audio files).</p>
	 *
	 * @param details the WaveDetails to add (must have non-null identifier)
	 * @throws IllegalArgumentException if details.getIdentifier() is null
	 */
	public void include(WaveDetails details) {
		if (details.getIdentifier() == null) {
			throw new IllegalArgumentException();
		}

		info.put(details.getIdentifier(), details);
	}

	public Optional<WaveDetails> getDetailsNow(String key) {
		return getDetailsNow(new FileWaveDataProvider(key));
	}

	public Optional<WaveDetails> getDetailsNow(String key, boolean persistent) {
		return getDetailsNow(new FileWaveDataProvider(key), persistent);
	}

	public Optional<WaveDetails> getDetailsNow(WaveDataProvider provider) {
		return getDetailsNow(provider, false);
	}

	public Optional<WaveDetails> getDetailsNow(WaveDataProvider provider, boolean persistent) {
		return Optional.ofNullable(getDetails(provider, persistent, DEFAULT_PRIORITY).getNow(null));
	}

	public WaveDetails getDetailsAwait(String key, boolean persistent) {
		return getDetailsAwait(new FileWaveDataProvider(key), persistent);
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider) {
		return getDetailsAwait(provider, false);
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider, long timeout) {
		return getDetailsAwait(provider, false, OptionalLong.of(timeout));
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider, boolean persistent) {
		return getDetailsAwait(provider, persistent, OptionalLong.empty());
	}

	public WaveDetails getDetailsAwait(WaveDataProvider provider, boolean persistent, OptionalLong timeout) {
		try {
			CompletableFuture<WaveDetails> future = getDetails(provider, persistent, HIGH_PRIORITY);

			if (timeout.isPresent()) {
				return future.get(timeout.getAsLong(), TimeUnit.SECONDS);
			} else {
				return future.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (TimeoutException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public void getDetails(String file, Consumer<WaveDetails> consumer, boolean priority) {
		getDetails(new FileWaveDataProvider(file), consumer, priority);
	}

	public void getDetails(WaveDataProvider provider, Consumer<WaveDetails> consumer, boolean priority) {
		getDetails(provider, false, priority ? HIGH_PRIORITY : DEFAULT_PRIORITY).thenAccept(consumer);
	}

	/**
	 * Retrieve {@link WaveDetails} for the given {@link WaveDataProvider}, queueing its computation
	 * if it is not already available.
	 *
	 * @param provider  {@link WaveDataProvider} to retrieve details for.
	 * @param persistent  If true, the details will be stored in the library for future use
	 *                    even if no associated file can be found.
	 *
	 * @return  {@link CompletableFuture} with the {@link WaveDetails} for the given provider.
	 */
	protected CompletableFuture<WaveDetails> getDetails(WaveDataProvider provider, boolean persistent, double priority) {
		WaveDetails existing = info.get(provider.getIdentifier());

		if (!isComplete(existing)) {
			return submitJob(provider, persistent, priority).getFuture();
		} else {
			existing.setPersistent(persistent || existing.isPersistent());
			return CompletableFuture.completedFuture(existing);
		}
	}

	/**
	 * Compute {@link WaveDetails} for the given {@link WaveDataProvider}.
	 *
	 * @param provider  {@link WaveDataProvider} to retrieve details for.
	 * @param persistent  If true, the details will be stored in the library for future use
	 *                    even if no associated file can be found.
	 *
	 * @return  {@link WaveDetails} for the given provider, or null if an error occurs.
	 */
	protected WaveDetails computeDetails(WaveDataProvider provider, boolean persistent) {
		try {
			String id = provider.getIdentifier();

			WaveDetails details = info.computeIfAbsent(id, k -> computeDetails(provider, null, persistent));
			if (getWaveDetailsFactory().getFeatureProvider() != null && details.getFeatureData() == null) {
				details = computeDetails(provider, details, persistent);
				info.put(id, details);
			}

			details.setPersistent(persistent || details.isPersistent());
			return details;
		} catch (Exception e) {
			warn("Failed to create WaveDetails for " +
					provider.getKey() + " (" +
					Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()) + ")");
			if (!(e.getCause() instanceof IOException) || !(provider instanceof FileWaveDataProvider)) {
				if (getErrorListener() == null) {
					e.printStackTrace();
				} else {
					getErrorListener().accept(e);
				}
			}

			return null;
		}
	}

	public boolean isComplete(WaveDetails details) {
		return details != null &&
				details.getFreqData() != null &&
				details.getFeatureData() != null;
	}

	public Map<String, Double> getSimilarities(String key) {
		return getSimilarities(new FileWaveDataProvider(key));
	}

	public Map<String, Double> getSimilarities(WaveDetails details) {
		return computeSimilarities(details).getSimilarities();
	}

	public Map<String, Double> getSimilarities(WaveDataProvider provider) {
		return computeSimilarities(getDetailsAwait(provider, false)).getSimilarities();
	}

	public void resetSimilarities() {
		getAllDetails().forEach(d -> d.getSimilarities().clear());
		// log("Similarities reset");
	}

	protected WaveDetails processJob(WaveDetailsJob job) {
		if (job == null) return null;

		try {
			return computeDetails(job.getTarget(), job.isPersistent());
		} finally {
			reportProgress();
		}
	}

	protected WaveDetailsJob submitJob(WaveDataProvider provider, boolean persistent, double priority) {
		return submitJob(new WaveDetailsJob(this::processJob, provider, persistent, priority));
	}

	protected WaveDetailsJob submitJob(WaveDetailsJob job) {
		if (job.getTarget() != null) {
			identifiers.computeIfAbsent(job.getTarget().getKey(), k -> job.getTarget().getIdentifier());
		}

		executor.execute(job);
		totalJobs++;
		return job;
	}

	public double getProgress() {
		int totalJobs = getTotalJobs();
		int queueSize = getPendingJobs();

		double total = totalJobs <= 0 ? 1.0 : totalJobs;
		double remaining = queueSize / total;
		double progress = remaining <= 0.0 ? 1.0 : 1.0 - remaining;

		if (queueSize == 0 && progress != 1.0) {
			warn("Progress != 1.0 despite no jobs in progress (" +
					progress + ", " + totalJobs + " total)");
			progress = 1.0;
		}

		return progress;
	}

	protected void reportProgress() {
		if (progressListener == null) return;
		progressListener.accept(getProgress());
	}

	public void stop() { stop(5); }

	public void stop(int timeout) {
		try {
			queue.clear();
			executor.shutdown();

			if (!executor.awaitTermination(timeout, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			warn("Interrupted waiting for executor to shut down", e);
			Thread.currentThread().interrupt();
		} finally {
			executor = null;
		}
	}

	protected WaveDetails computeDetails(WaveDataProvider provider, WaveDetails existing, boolean persistent) {
		WaveDetails details = factory.forProvider(provider, existing);
		details.setPersistent((existing != null && existing.isPersistent()) || persistent);
		return details;
	}

	/**
	 * Incrementally computes similarity scores between the given {@link WaveDetails}
	 * and every other detail in this library.
	 *
	 * <p>This method is <b>incremental</b>: it only computes similarities for pairs
	 * that are not already present in {@code details.getSimilarities()}. If the
	 * similarity map is already fully populated, this method does nothing. This
	 * makes it safe and cheap to call as a pre-condition before building a
	 * similarity graph.</p>
	 *
	 * <p>Similarity is stored bidirectionally: when computing the similarity between
	 * A and B, the result is stored in both {@code A.getSimilarities()} and
	 * {@code B.getSimilarities()}. This means calling {@code computeSimilarities(A)}
	 * also partially fills in the maps of other details, reducing work for subsequent
	 * calls.</p>
	 *
	 * <h3>Typical usage before prototype discovery</h3>
	 * <pre>{@code
	 * // Ensure all pairwise similarities exist before graph construction
	 * library.getAllDetails().forEach(library::computeSimilarities);
	 * AudioSimilarityGraph graph = library.toSimilarityGraph();
	 * }</pre>
	 *
	 * @param details the WaveDetails to compute similarities for, or null (returns null)
	 * @return the same WaveDetails instance with its similarity map updated
	 * @see #toSimilarityGraph()
	 */
	public WaveDetails computeSimilarities(WaveDetails details) {
		if (details == null) return null;

		try {
			List<WaveDetails> targets = allDetails()
					.filter(d -> !Objects.equals(d.getIdentifier(), details.getIdentifier()))
					.filter(d -> !details.getSimilarities().containsKey(d.getIdentifier()))
					.collect(Collectors.toList());

			double[] similarities = factory.batchSimilarity(details, targets);

			for (int i = 0; i < targets.size(); i++) {
				WaveDetails target = targets.get(i);
				double similarity = similarities[i];

				if (Math.abs(similarity - 1.0) < 1e-5) {
					warn("Identical features for distinct files");
				}

				details.getSimilarities().put(target.getIdentifier(), similarity);

				if (details.getIdentifier() != null)
					target.getSimilarities().put(details.getIdentifier(), similarity);
			}
		} catch (Exception e) {
			log("Failed to load similarities for " + details.getIdentifier() +
					" (" + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()) + ")");
		}

		return details;
	}

	/**
	 * Computes all pairwise similarities incrementally using approximate filtering.
	 *
	 * <p>This method uses a two-phase approach: first computing fast approximate
	 * similarity using mean-pooled feature embeddings, then only computing exact
	 * per-frame cosine similarity for pairs above the given threshold. This
	 * typically eliminates 80-90% of expensive exact comparisons while producing
	 * a similarity graph suitable for community detection.</p>
	 *
	 * <p>Existing similarity values are preserved; only missing pairs above the
	 * approximate threshold are computed.</p>
	 *
	 * @param approximateThreshold minimum approximate cosine similarity for
	 *        a pair to receive exact computation (recommended: 0.3)
	 * @return the computation result with timing and filtering statistics
	 * @see IncrementalSimilarityComputation
	 */
	public IncrementalSimilarityComputation.Result computeAllSimilaritiesIncremental(
			double approximateThreshold) {
		List<WaveDetails> all = new ArrayList<>(getAllDetails());
		IncrementalSimilarityComputation computation =
				new IncrementalSimilarityComputation(factory, all, approximateThreshold);
		return computation.compute();
	}

	public CompletableFuture<Void> refresh() {
		try {
			CompletableFuture<Void> future = new CompletableFuture<>();
			AtomicBoolean submitted = new AtomicBoolean(false);

			root.children().forEach(f -> {
				FileWaveDataProvider provider = f.get();

				boolean skipped = true;

				try {
					if (provider == null || isComplete(info.get(provider.getIdentifier())))
						return;

					// Similarities may no longer be valid if the library is being updated
					skipped = false;
					if (!submitted.get()) resetSimilarities();

					submitJob(new WaveDetailsJob(this::processJob, provider, true, BACKGROUND_PRIORITY));
				} finally {
					// Make sure not to repeatedly reset the similarities
					if (!skipped) submitted.set(true);
				}
			});

			WaveDetailsJob last = new WaveDetailsJob(this::processJob, null, false, -1.0);
			last.getFuture().thenRun(() -> future.complete(null));
			latestRefresh = future;
			executor.execute(last);
			return future;
		} finally {
			reportProgress();
		}
	}

	public void cleanup(Predicate<String> preserve) {
		// Identify current library files
		Set<String> activeIds = root.children()
				.map(Supplier::get).filter(Objects::nonNull)
				.map(WaveDataProvider::getIdentifier).filter(Objects::nonNull)
				.collect(Collectors.toSet());

		// Exclude persistent info and those associated with active files
		// or otherwise explicitly preserved
		List<String> keys = info.entrySet().stream()
				.filter(e -> !e.getValue().isPersistent())
				.filter(e -> !activeIds.contains(e.getKey()))
				.filter(e -> preserve == null || !preserve.test(e.getKey()))
				.map(Map.Entry::getKey).toList();

		// Remove everything else
		keys.forEach(info::remove);
	}

	/**
	 * Checks whether the persisted {@link PrototypeIndexData} is stale and
	 * needs recomputation.
	 *
	 * <p>Staleness is determined by these conditions (checked in order of cost):</p>
	 * <ol>
	 *   <li>No index present (first run / bootstrap)</li>
	 *   <li>Any prototype identifier not found in current info (prototype was deleted)</li>
	 *   <li>More than 5% of indexed member identifiers not found in current info (significant removal)</li>
	 *   <li>Current info size exceeds total indexed members by more than 5% (significant additions)</li>
	 *   <li>Index is older than 24 hours AND any difference exists between indexed and current members</li>
	 * </ol>
	 *
	 * @return true if the index should be recomputed
	 */
	public boolean isPrototypeIndexStale() {
		if (prototypeIndex == null || prototypeIndex.communities().isEmpty()) {
			return true;
		}

		Set<String> currentIds = info.keySet();
		int totalIndexed = 0;
		int missingMembers = 0;

		for (PrototypeIndexData.Community community : prototypeIndex.communities()) {
			// Check if the prototype itself was deleted
			if (!currentIds.contains(community.prototypeIdentifier())) {
				return true;
			}

			for (String memberId : community.memberIdentifiers()) {
				totalIndexed++;
				if (!currentIds.contains(memberId)) {
					missingMembers++;
				}
			}
		}

		// More than 5% of indexed members are missing
		if (totalIndexed > 0 && missingMembers > totalIndexed * 0.05) {
			return true;
		}

		// More than 5% new samples added beyond what's indexed
		int currentSize = info.size();
		if (totalIndexed > 0 && currentSize > totalIndexed * 1.05) {
			return true;
		}

		// Time-based: older than 24 hours with any difference
		long age = System.currentTimeMillis() - prototypeIndex.computedAt();
		boolean anyDifference = currentSize != totalIndexed || missingMembers > 0;
		if (age > Duration.ofHours(24).toMillis() && anyDifference) {
			return true;
		}

		return false;
	}
}
