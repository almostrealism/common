/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.discovery;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.studio.persistence.LibraryDestination;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.audio.similarity.SimilarityNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.algorithm.GraphFeatures;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Headless console application for discovering prototypical audio samples
 * from a pre-computed protobuf library file.
 *
 * <p>This tool loads pre-computed feature data from a protobuf library file
 * and finds representative samples (prototypes) using graph algorithms.
 * It does NOT perform any feature computation - all features must already
 * exist in the protobuf file.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -cp ... org.almostrealism.studio.discovery.PrototypeDiscovery [options]
 *
 * Options:
 *   --data PREFIX     Path prefix for protobuf library files (required)
 *                     Files are expected at PREFIX_0.bin, PREFIX_1.bin, etc.
 *   --samples DIR     Path to audio samples directory (optional but recommended)
 *                     Required to display file paths instead of just identifiers
 *   --clusters N      Number of clusters to show (default: 10)
 *   --reveal          Open files in Finder/Explorer (requires --samples)
 * </pre>
 *
 * <h2>Example</h2>
 * <pre>
 * java -cp ... org.almostrealism.studio.discovery.PrototypeDiscovery \
 *   --data ~/.almostrealism/library --samples ~/Music/Samples --clusters 5
 * </pre>
 *
 * @see AudioLibraryPersistence
 * @see AudioSimilarityGraph
 * @see GraphFeatures
 */
public class PrototypeDiscovery implements ConsoleFeatures, GraphFeatures {

	/**
	 * Toggles verbose diagnostic logging of HNSW graph construction and
	 * Louvain community size distribution. Disabled by default to keep
	 * normal-run logs quiet; re-enable for prototype-quality investigations
	 * by setting {@code AR_PROTOTYPE_DIAGNOSTICS=enabled} as either a system
	 * property or environment variable. See
	 * {@code docs/plans/PROTOTYPE_DISCOVERY_QUALITY.md} for what the output
	 * is useful for.
	 */
	private static final boolean DIAGNOSTICS_ENABLED =
			SystemUtils.isEnabled("AR_PROTOTYPE_DIAGNOSTICS").orElse(false);

	/** Default audio sample rate used when loading audio files for analysis. */
	private static final int DEFAULT_SAMPLE_RATE = 44100;

	/** Path prefix for the library protobuf batch files. */
	private final String dataPrefix;

	/** Path to the directory containing raw audio sample files, or {@code null}. */
	private final String samplesDir;

	/** Maximum number of prototype clusters to discover. */
	private final int maxClusters;

	/** When {@code true}, cluster results are revealed (logged or persisted) after discovery. */
	private final boolean reveal;

	/** The loaded audio library used as the source for prototype discovery. */
	private AudioLibrary library;

	/**
	 * Creates a prototype discovery pipeline.
	 *
	 * @param dataPrefix  path prefix for library protobuf batch files
	 * @param samplesDir  path to the audio samples directory, or {@code null}
	 * @param maxClusters maximum number of clusters to discover
	 * @param reveal      if {@code true}, discovery results are persisted and logged
	 */
	public PrototypeDiscovery(String dataPrefix, String samplesDir, int maxClusters, boolean reveal) {
		this.dataPrefix = dataPrefix;
		this.samplesDir = samplesDir;
		this.maxClusters = maxClusters;
		this.reveal = reveal;
	}

	/** Executes the prototype discovery pipeline, clustering audio samples and revealing prototypes. */
	public void run() throws Exception {
		log("=== Prototype Discovery ===");
		log("Data prefix: " + dataPrefix);
		log("Samples dir: " + (samplesDir != null ? samplesDir : "(not specified)"));
		log("Max clusters: " + maxClusters);
		log("");

		// Initialize AudioLibrary if samples directory is provided
		if (samplesDir != null) {
			File samplesRoot = new File(samplesDir);
			if (!samplesRoot.isDirectory()) {
				warn("Samples directory does not exist: " + samplesDir);
				warn("File paths will not be resolved.");
			} else {
				log("Initializing library from samples directory...");
				library = new AudioLibrary(samplesRoot, DEFAULT_SAMPLE_RATE);
				log("  Scanning file tree...");
			}
		} else {
			log("Note: No --samples directory specified.");
			log("      Prototypes will show identifiers instead of file paths.");
			log("      Use --samples DIR to enable file path display.");
			log("");
		}

		// Load pre-computed library data from protobuf
		log("Loading library from protobuf...");
		List<WaveDetails> allDetails = new ArrayList<>();

		if (library != null) {
			// Load into the AudioLibrary so we can use find() to resolve paths
			AudioLibraryPersistence.loadLibrary(library, dataPrefix);
			library.allDetails()
					.filter(this::hasFeatures)
					.forEach(allDetails::add);
		} else {
			// No samples directory - load directly from protobuf
			LibraryDestination destination = new LibraryDestination(dataPrefix);
			List<Audio.AudioLibraryData> libraryDataList = destination.load();
			for (Audio.AudioLibraryData libraryData : libraryDataList) {
				for (Map.Entry<String, Audio.WaveDetailData> entry : libraryData.getInfoMap().entrySet()) {
					WaveDetails details = AudioLibraryPersistence.decode(entry.getValue());
					if (hasFeatures(details)) {
						allDetails.add(details);
					}
				}
			}
		}

		log("Loaded " + allDetails.size() + " samples with pre-computed features");

		if (allDetails.isEmpty()) {
			log("ERROR: No samples with features found in library data");
			log("       Make sure the protobuf file contains feature_data");
			return;
		}

		// Build similarity graph from pre-computed data
		log("");
		log("Building similarity graph from pre-computed similarities...");
		AudioSimilarityGraph graph = new AudioSimilarityGraph(allDetails);
		log("  Nodes: " + graph.countNodes());

		// Count edges
		int edgeCount = 0;
		for (int i = 0; i < graph.countNodes(); i++) {
			edgeCount += graph.neighborIndices(i).size();
		}
		edgeCount /= 2; // Undirected graph
		log("  Edges: " + edgeCount);

		if (edgeCount == 0) {
			log("WARNING: No similarity edges found in pre-computed data.");
			log("         Will compute similarities on the fly...");
			computeMissingSimilarities(allDetails);
			graph = new AudioSimilarityGraph(allDetails);

			edgeCount = 0;
			for (int i = 0; i < graph.countNodes(); i++) {
				edgeCount += graph.neighborIndices(i).size();
			}
			edgeCount /= 2;
			log("  Edges after computation: " + edgeCount);
		}

		if (edgeCount == 0) {
			log("ERROR: Still no similarity edges. Cannot proceed.");
			return;
		}

		// Run community detection and find prototypes
		log("");
		log("Detecting communities (Louvain algorithm)...");
		List<PrototypeResult> prototypes = findPrototypesFromGraph(graph, maxClusters);
		log("  Found " + prototypes.size() + " prototype communities");

		// Display top prototypes
		log("");
		log("========================================");
		log("           DISCOVERED PROTOTYPES       ");
		log("========================================");
		log("");

		for (int i = 0; i < prototypes.size(); i++) {
			PrototypeResult p = prototypes.get(i);
			String id = p.identifier();
			String filePath = resolveFilePath(id);
			String displayName = filePath != null ? getDisplayName(filePath) : id;

			log(String.format("Cluster %d (%d samples):", i + 1, p.communitySize()));
			log(String.format("  Prototype: %s", displayName));
			if (filePath != null) {
				log(String.format("  Path: %s", filePath));
			}
			log(String.format("  Centrality: %.6f", p.centrality()));
			log(String.format("  Identifier: %s", id));
			log("");

			if (reveal && filePath != null) {
				revealInFinder(filePath);
			} else if (reveal && filePath == null) {
				warn("Cannot reveal: no file path for identifier " + id);
				warn("Use --samples DIR to enable file path resolution");
			}
		}

		log("----------------------------------------");
		log("Done.");

		// Cleanup
		if (library != null) {
			library.stop();
		}
	}

	/**
	 * Returns {@code true} if the given wave details contain non-null feature data.
	 *
	 * @param details the wave details to check
	 * @return {@code true} if feature data is present
	 */
	private boolean hasFeatures(WaveDetails details) {
		return details != null && details.getFeatureData() != null;
	}

	/**
	 * Resolves a content identifier (MD5 hash) to a file path using the AudioLibrary.
	 *
	 * @param identifier the content identifier to resolve
	 * @return the file path, or null if the library is not initialized or the identifier is not found
	 */
	private String resolveFilePath(String identifier) {
		if (library == null || identifier == null) {
			return null;
		}

		WaveDataProvider provider = library.find(identifier);
		return provider != null ? provider.getKey() : null;
	}

	/**
	 * Computes pairwise similarity scores for any pair of wave details that does not yet
	 * have a similarity entry, updating the similarity maps in place.
	 *
	 * @param allDetails the list of all wave details to process
	 */
	private void computeMissingSimilarities(List<WaveDetails> allDetails) {
		log("Computing similarities for " + allDetails.size() + " samples...");
		int count = 0;
		for (int i = 0; i < allDetails.size(); i++) {
			WaveDetails a = allDetails.get(i);
			for (int j = i + 1; j < allDetails.size(); j++) {
				WaveDetails b = allDetails.get(j);
				if (!a.getSimilarities().containsKey(b.getIdentifier())) {
					double sim = WaveDetails.differenceSimilarity(
							a.getFeatureData(), b.getFeatureData());
					a.getSimilarities().put(b.getIdentifier(), sim);
					b.getSimilarities().put(a.getIdentifier(), sim);
				}
			}
			count++;
			if (count % 50 == 0) {
				log(String.format("  Processed %d/%d samples", count, allDetails.size()));
			}
		}
	}

	/**
	 * Returns the filename component of the given path for display purposes.
	 * Returns {@code "unknown"} if the path is {@code null}.
	 *
	 * @param path the full file path
	 * @return the filename portion of the path
	 */
	private String getDisplayName(String path) {
		if (path == null) return "unknown";
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path;
	}

	/**
	 * Opens the platform's file manager and selects the given file path.
	 * Supports macOS (Finder), Windows (Explorer), and Linux (xdg-open).
	 *
	 * @param path the absolute file path to reveal
	 */
	private void revealInFinder(String path) {
		try {
			File f = new File(path);
			if (!f.exists()) {
				warn("File does not exist: " + path);
				return;
			}

			ProcessBuilder pb;
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("mac")) {
				pb = new ProcessBuilder("open", "-R", path);
			} else if (os.contains("win")) {
				pb = new ProcessBuilder("explorer", "/select,", path);
			} else {
				// Linux - open parent directory
				File parent = f.getParentFile();
				if (parent == null) return;
				pb = new ProcessBuilder("xdg-open", parent.getAbsolutePath());
			}

			Process process = pb.start();
			process.getInputStream().close();
			process.getErrorStream().close();
		} catch (IOException e) {
			warn("Could not reveal file: " + e.getMessage());
		}
	}

	// ── Reusable API for in-process callers ──────────────────────────────

	/**
	 * Result of prototype discovery for a single community.
	 *
	 * @param identifier        content identifier (MD5) of the prototype sample
	 * @param centrality        PageRank centrality score
	 * @param communitySize     number of samples in the community
	 * @param memberIdentifiers content identifiers of all community members
	 */
	public record PrototypeResult(String identifier, double centrality,
								  int communitySize, List<String> memberIdentifiers) {}

	/** Maximum time to wait for library refresh to complete. */
	private static final int REFRESH_TIMEOUT_MINUTES = 10;

	/**
	 * Discovers up to {@code maxPrototypes} representative samples from
	 * the given library by building a similarity graph, detecting
	 * communities via Louvain, and picking the highest-centrality node
	 * in each community.
	 *
	 * <p>This method blocks until the library's most recent refresh has
	 * completed (with a timeout), then computes any missing similarity
	 * data before running the graph algorithms. Progress is reported
	 * via the optional {@code statusCallback}.</p>
	 *
	 * @param library        the audio library to analyze
	 * @param maxPrototypes  maximum number of prototypes to return
	 * @param statusCallback optional callback for progress messages (may be null)
	 * @return prototypes sorted by community size (largest first)
	 * @throws PrototypeDiscoveryException if the process fails or times out
	 */
	public static List<PrototypeResult> discoverPrototypes(AudioLibrary library,
														   int maxPrototypes,
														   Consumer<String> statusCallback)
			throws PrototypeDiscoveryException {
		PrototypeDiscovery instance = new PrototypeDiscovery(null, null, maxPrototypes, false);
		return instance.doDiscoverPrototypes(library, maxPrototypes, statusCallback);
	}

	/** Default number of nearest neighbors for HNSW-based sparse graph. */
	public static final int DEFAULT_K_NEIGHBORS = 20;

	/**
	 * Executes the full prototype discovery pipeline on the given library.
	 *
	 * @param library        the audio library to analyze
	 * @param maxPrototypes  maximum number of prototypes to return
	 * @param statusCallback optional callback for progress messages
	 * @return prototypes sorted by community size (largest first)
	 * @throws PrototypeDiscoveryException if discovery fails or times out
	 */
	private List<PrototypeResult> doDiscoverPrototypes(AudioLibrary library,
													   int maxPrototypes,
													   Consumer<String> statusCallback)
			throws PrototypeDiscoveryException {
		report(statusCallback, "Waiting for library refresh...");
		log("Waiting for library refresh to complete...");
		log("Library has " + library.getPendingJobs() + " pending jobs, "
				+ "progress=" + String.format("%.1f%%", library.getProgress() * 100));

		try {
			waitForRefresh(library, statusCallback);
		} catch (TimeoutException e) {
			String msg = "Library refresh did not complete within "
					+ REFRESH_TIMEOUT_MINUTES + " minutes ("
					+ library.getPendingJobs() + " jobs still pending)";
			log("TIMEOUT: " + msg);
			throw new PrototypeDiscoveryException(msg, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PrototypeDiscoveryException("Interrupted waiting for refresh", e);
		}

		log("Library refresh complete");

		int totalDetails = library.getAllIdentifiers().size();

		if (totalDetails == 0) {
			log("No samples in library");
			throw new PrototypeDiscoveryException("No samples in library");
		}

		AudioSimilarityGraph graph;

		WaveDetailsStore store = library.getStore();
		if (store != null) {
			backfillEmbeddingsIfNeeded(library, store, totalDetails, statusCallback);
			report(statusCallback, "Building sparse K-NN graph via HNSW...");
			log("Building sparse K-NN graph (K=" + DEFAULT_K_NEIGHBORS
					+ ") for " + totalDetails + " samples...");
			graph = buildSparseGraph(library, store, DEFAULT_K_NEIGHBORS, statusCallback);
		} else {
			log("Computing pairwise similarities for "
					+ totalDetails + " samples...");
			report(statusCallback, "Computing similarities...");
			CompletableFuture<Void> similarityFuture =
					library.submitSimilarityJobs(statusCallback);
			similarityFuture.join();

			report(statusCallback, "Building similarity graph...");
			log("Building similarity graph...");
			graph = library.toSimilarityGraph();
		}

		int nodeCount = graph.countNodes();
		if (nodeCount == 0) {
			log("No samples with similarity data");
			throw new PrototypeDiscoveryException(
					"Similarity graph is empty (no samples with feature data)");
		}

		log("Graph has " + nodeCount + " nodes");

		report(statusCallback, "Detecting communities...");
		log("Running Louvain community detection...");

		List<PrototypeResult> prototypes = findPrototypesFromGraph(graph, maxPrototypes);
		log("Returning " + prototypes.size() + " prototypes");
		return prototypes;
	}

	/**
	 * Threshold below which the HNSW index is considered under-populated
	 * relative to the library size and a backfill pass is triggered.
	 * Expressed as a fraction of the library size.
	 */
	private static final double HNSW_BACKFILL_TRIGGER_RATIO = 0.9;

	/**
	 * Inserts HNSW vector entries for any sample whose record has feature
	 * data but is missing from the index. This recovers from the historical
	 * code paths that wrote records to the disk store via the no-vector
	 * {@code put} (e.g. {@link AudioLibrary#include(WaveDetails)} or the
	 * legacy migration path with un-featured records), which left the
	 * vector index empty even though feature data was later computed.
	 *
	 * <p>This runs on the same background thread that drives the rest of
	 * {@code doDiscoverPrototypes} (the Prototypes tab's executor) and
	 * reports progress through {@code statusCallback}. It only touches
	 * the HNSW index, not the on-disk record bytes, so it is cheap per
	 * record. It is also a no-op once the index is populated, so the
	 * cost is amortized to the first Prototypes click after the gap is
	 * introduced.</p>
	 *
	 * @param library        the audio library
	 * @param store          the backing store (must have HNSW capability)
	 * @param totalDetails   the number of complete library identifiers
	 * @param statusCallback optional progress callback
	 */
	private void backfillEmbeddingsIfNeeded(AudioLibrary library,
											 WaveDetailsStore store,
											 int totalDetails,
											 Consumer<String> statusCallback) {
		int indexed = store.indexedEmbeddingCount();
		if (indexed >= (int) (totalDetails * HNSW_BACKFILL_TRIGGER_RATIO)) {
			return;
		}

		log("HNSW index has " + indexed + " entries for "
				+ totalDetails + " samples; backfilling missing vectors...");
		report(statusCallback, "Indexing samples for similarity search...");

		List<WaveDetails> allDetails = library.allDetails().toList();
		int inserted = 0;
		int skippedNoEmbedding = 0;
		int skippedAlreadyIndexed = 0;
		int processed = 0;

		for (WaveDetails details : allDetails) {
			processed++;

			String id = details.getIdentifier();
			if (id == null) continue;

			if (store.hasEmbedding(id)) {
				skippedAlreadyIndexed++;
				continue;
			}

			PackedCollection embedding = AudioLibrary.computeEmbeddingVector(details);
			if (embedding == null) {
				skippedNoEmbedding++;
				continue;
			}

			store.insertEmbedding(id, embedding);
			inserted++;

			if (processed % 100 == 0 || processed == allDetails.size()) {
				report(statusCallback, "Indexing samples... "
						+ processed + "/" + allDetails.size());
			}
		}

		log("HNSW backfill complete: inserted=" + inserted
				+ ", already-indexed=" + skippedAlreadyIndexed
				+ ", no-features=" + skippedNoEmbedding);
	}

	/**
	 * Builds a sparse similarity graph using HNSW nearest neighbor search.
	 *
	 * <p>For each sample, its mean-pooled embedding vector is computed and
	 * used to search for the top-K most similar samples via the HNSW index
	 * in the backing store. The search results are stored as similarity
	 * scores in each {@link WaveDetails}, producing a sparse graph with
	 * O(N*K) edges instead of O(N^2).</p>
	 *
	 * @param library        the audio library
	 * @param store          the backing store with HNSW index
	 * @param k              number of nearest neighbors per sample
	 * @param statusCallback optional progress callback
	 * @return a sparse similarity graph
	 */
	private AudioSimilarityGraph buildSparseGraph(AudioLibrary library,
												   WaveDetailsStore store,
												   int k,
												   Consumer<String> statusCallback) {
		List<WaveDetails> allDetails = library.allDetails().toList();
		int total = allDetails.size();
		int processed = 0;

		int samplesNoEmbedding = 0;
		int samplesEmptyHnswResult = 0;
		int samplesNoPositiveEdges = 0;
		long totalNeighborsStored = 0;
		long edgesPositive = 0;
		long edgesNonPositive = 0;
		double simMin = Double.POSITIVE_INFINITY;
		double simMax = Double.NEGATIVE_INFINITY;
		double simSum = 0.0;

		for (WaveDetails details : allDetails) {
			PackedCollection embedding = AudioLibrary.computeEmbeddingVector(details);
			if (embedding == null) {
				if (DIAGNOSTICS_ENABLED) samplesNoEmbedding++;
				continue;
			}

			List<WaveDetailsStore.NeighborResult> neighbors =
					store.searchNeighbors(embedding, k);

			details.getSimilarities().clear();
			int neighborCount = 0;
			int positiveCount = 0;
			for (WaveDetailsStore.NeighborResult neighbor : neighbors) {
				if (!neighbor.identifier().equals(details.getIdentifier())) {
					double sim = (double) neighbor.similarity();
					details.getSimilarities().put(neighbor.identifier(), sim);
					if (DIAGNOSTICS_ENABLED) {
						neighborCount++;
						if (sim > 0) {
							positiveCount++;
							edgesPositive++;
						} else {
							edgesNonPositive++;
						}
						if (sim < simMin) simMin = sim;
						if (sim > simMax) simMax = sim;
						simSum += sim;
					}
				}
			}
			if (DIAGNOSTICS_ENABLED) {
				totalNeighborsStored += neighborCount;
				if (neighborCount == 0) samplesEmptyHnswResult++;
				if (positiveCount == 0) samplesNoPositiveEdges++;
			}

			processed++;
			if (processed % 100 == 0 || processed == total) {
				String msg = "Building K-NN graph... " + processed + "/" + total;
				report(statusCallback, msg);
			}
		}

		AudioSimilarityGraph graph = new AudioSimilarityGraph(allDetails);
		log("Sparse graph: " + total + " nodes, K=" + k);

		if (DIAGNOSTICS_ENABLED) {
			long edgesSurvivingThreshold = 0;
			int isolatedNodesInGraph = 0;
			for (int i = 0; i < graph.countNodes(); i++) {
				int deg = graph.neighborIndices(i).size();
				edgesSurvivingThreshold += deg;
				if (deg == 0) isolatedNodesInGraph++;
			}

			log("HNSW diagnostics:");
			log("  store size: " + store.size());
			log("  samples skipped (no embedding/feature data): " + samplesNoEmbedding);
			log("  samples whose HNSW search returned 0 neighbors (excl. self): " + samplesEmptyHnswResult);
			log("  samples with no positive-similarity edges: " + samplesNoPositiveEdges);
			log("  total directed neighbors stored: " + totalNeighborsStored);
			log("  edges with similarity > 0: " + edgesPositive);
			log("  edges with similarity <= 0: " + edgesNonPositive);
			if (totalNeighborsStored > 0) {
				log(String.format("  similarity min=%.4f max=%.4f mean=%.4f",
						simMin, simMax, simSum / totalNeighborsStored));
			}
			log("  edges surviving graph threshold (" + graph.getThreshold()
					+ "): " + edgesSurvivingThreshold);
			log("  isolated nodes in graph: " + isolatedNodesInGraph + " / " + graph.countNodes());
		}

		return graph;
	}

	/**
	 * Builds a {@link PrototypeIndexData} from discovered prototypes for
	 * persistence in the protobuf library file.
	 *
	 * @param prototypes the discovered prototypes
	 * @return a persistable index
	 */
	public static PrototypeIndexData buildIndex(List<PrototypeResult> prototypes) {
		List<PrototypeIndexData.Community> communities = prototypes.stream()
				.map(p -> new PrototypeIndexData.Community(
						p.identifier(), p.centrality(), p.memberIdentifiers()))
				.toList();
		return new PrototypeIndexData(System.currentTimeMillis(), communities);
	}

	/**
	 * Runs Louvain community detection and PageRank centrality on the given
	 * graph and returns the highest-centrality node in each community.
	 *
	 * @param graph          the similarity graph to analyze
	 * @param maxPrototypes  maximum number of prototypes to return
	 * @return prototypes sorted by community size (largest first)
	 */
	private List<PrototypeResult> findPrototypesFromGraph(AudioSimilarityGraph graph,
														   int maxPrototypes) {
		int[] communities = louvain(graph, 1.0);
		double[] ranks = pageRank(graph, 0.85, 50);
		Map<Integer, List<Integer>> communityMembers = getCommunityMembers(communities);

		List<PrototypeResult> prototypes = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> entry : communityMembers.entrySet()) {
			List<Integer> members = entry.getValue();

			int prototypeIdx = members.stream()
					.max(Comparator.comparingDouble(i -> ranks[i]))
					.orElse(-1);

			if (prototypeIdx < 0) continue;

			SimilarityNode node = graph.nodeAt(prototypeIdx);
			if (node == null || node.getIdentifier() == null) continue;

			List<String> memberIds = members.stream()
					.map(graph::nodeAt)
					.filter(n -> n != null && n.getIdentifier() != null)
					.map(SimilarityNode::getIdentifier)
					.toList();

			prototypes.add(new PrototypeResult(
					node.getIdentifier(),
					ranks[prototypeIdx],
					memberIds.size(),
					memberIds));
		}

		prototypes.sort(Comparator.comparingInt(
				(PrototypeResult p) -> p.communitySize()).reversed());

		if (DIAGNOSTICS_ENABLED) {
			logCommunitySizeDistribution(prototypes);
		}

		int count = Math.min(prototypes.size(), maxPrototypes);
		return List.copyOf(prototypes.subList(0, count));
	}

	/**
	 * Logs the size distribution of all discovered communities, so a
	 * caller looking at the top-N selection can tell whether the
	 * distribution is heavily skewed (a few very large communities and a
	 * long tail of small ones) or relatively flat (many communities of
	 * similar size). The full sorted list is printed for small counts;
	 * for larger counts the head, a summary of the middle, and the tail
	 * are printed to keep log volume bounded.
	 */
	private void logCommunitySizeDistribution(List<PrototypeResult> prototypes) {
		int n = prototypes.size();
		if (n == 0) {
			log("Community size distribution: (no communities)");
			return;
		}

		int largest = prototypes.get(0).communitySize();
		int smallest = prototypes.get(n - 1).communitySize();
		long totalMembers = 0;
		for (PrototypeResult p : prototypes) {
			totalMembers += p.communitySize();
		}
		double mean = (double) totalMembers / n;

		log("Community size distribution: " + n + " communities, "
				+ totalMembers + " total members, "
				+ "largest=" + largest + ", smallest=" + smallest
				+ String.format(", mean=%.1f", mean));

		int sampleHead = Math.min(20, n);
		StringBuilder head = new StringBuilder("  sizes (largest first, first ");
		head.append(sampleHead).append("): ");
		for (int i = 0; i < sampleHead; i++) {
			if (i > 0) head.append(", ");
			head.append(prototypes.get(i).communitySize());
		}
		log(head.toString());

		if (n > sampleHead) {
			int singletons = 0;
			int twoOrThree = 0;
			int small = 0; // 4..10
			int medium = 0; // 11..50
			int large = 0; // 51..200
			int huge = 0; // > 200
			for (PrototypeResult p : prototypes) {
				int s = p.communitySize();
				if (s == 1) singletons++;
				else if (s <= 3) twoOrThree++;
				else if (s <= 10) small++;
				else if (s <= 50) medium++;
				else if (s <= 200) large++;
				else huge++;
			}
			log("  size buckets: huge(>200)=" + huge
					+ ", large(51-200)=" + large
					+ ", medium(11-50)=" + medium
					+ ", small(4-10)=" + small
					+ ", 2-3=" + twoOrThree
					+ ", singletons=" + singletons);
		}
	}

	/**
	 * Blocks until the library's most recent refresh completes or the
	 * {@link #REFRESH_TIMEOUT_MINUTES} deadline is reached, polling every
	 * 500 ms and forwarding progress to the optional status callback.
	 *
	 * @param library        the library whose refresh to await
	 * @param statusCallback optional callback for progress messages (may be null)
	 * @throws TimeoutException    if the refresh does not complete in time
	 * @throws InterruptedException if the waiting thread is interrupted
	 */
	private void waitForRefresh(AudioLibrary library, Consumer<String> statusCallback)
			throws TimeoutException, InterruptedException {
		CompletableFuture<Void> refresh = library.awaitRefresh();

		if (refresh.isDone()) return;

		long deadline = System.currentTimeMillis()
				+ TimeUnit.MINUTES.toMillis(REFRESH_TIMEOUT_MINUTES);

		while (!refresh.isDone()) {
			if (System.currentTimeMillis() > deadline) {
				throw new TimeoutException("Refresh timeout after "
						+ REFRESH_TIMEOUT_MINUTES + " minutes");
			}

			int pending = library.getPendingJobs();
			double progress = library.getProgress();
			String msg = String.format("Processing library... %.0f%% (%d jobs remaining)",
					progress * 100, pending);
			report(statusCallback, msg);

			Thread.sleep(500);
		}
	}

	/** Forwards {@code message} to the callback if it is non-null. */
	private static void report(Consumer<String> callback, String message) {
		if (callback != null) callback.accept(message);
	}

	/**
	 * Thrown when prototype discovery fails due to timeout, missing data,
	 * or other unrecoverable conditions.
	 */
	public static class PrototypeDiscoveryException extends Exception {
		/**
		 * Creates an exception with the given message.
		 *
		 * @param message the detail message
		 */
		public PrototypeDiscoveryException(String message) {
			super(message);
		}

		/**
		 * Creates an exception with the given message and cause.
		 *
		 * @param message the detail message
		 * @param cause   the underlying cause
		 */
		public PrototypeDiscoveryException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	// ── CLI entry point ──────────────────────────────────────────────────

	/**
	 * CLI entry point for the prototype discovery tool.
	 *
	 * @param args command-line arguments: {@code --data}, {@code --samples},
	 *             {@code --clusters}, {@code --reveal}, {@code --help}
	 * @throws Exception if discovery fails
	 */
	public static void main(String[] args) throws Exception {
		// Parse arguments
		String dataPrefix = null;
		String samplesDir = null;
		int maxClusters = 10;
		boolean reveal = false;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--data" -> dataPrefix = args[++i];
				case "--samples" -> samplesDir = args[++i];
				case "--clusters" -> maxClusters = Integer.parseInt(args[++i]);
				case "--reveal" -> reveal = true;
				case "--help" -> {
					printUsage();
					return;
				}
			}
		}

		if (dataPrefix == null) {
			Console.root().warn("ERROR: No data prefix specified.");
			Console.root().warn("Use --data PREFIX to specify the library protobuf file prefix.");
			Console.root().println("");
			printUsage();
			System.exit(1);
		}

		// Verify at least one data file exists
		File firstFile = new File(dataPrefix + "_0.bin");
		if (!firstFile.exists()) {
			Console.root().warn("ERROR: Library data file not found:");
			Console.root().warn("       " + firstFile.getAbsolutePath());
			Console.root().println("");
			Console.root().warn("Expected files: " + dataPrefix + "_0.bin, " + dataPrefix + "_1.bin, ...");
			System.exit(1);
		}

		PrototypeDiscovery discovery = new PrototypeDiscovery(dataPrefix, samplesDir, maxClusters, reveal);
		discovery.run();
	}

	/** Prints usage information for the CLI entry point. */
	private static void printUsage() {
		Console.root().println("Prototype Discovery - Find representative samples from pre-computed library data");
		Console.root().println("");
		Console.root().println("Usage: PrototypeDiscovery [options]");
		Console.root().println("");
		Console.root().println("Options:");
		Console.root().println("  --data PREFIX     Path prefix for protobuf library files (required)");
		Console.root().println("                    Files are expected at PREFIX_0.bin, PREFIX_1.bin, etc.");
		Console.root().println("  --samples DIR     Path to audio samples directory (optional)");
		Console.root().println("                    Required to display file paths instead of identifiers");
		Console.root().println("  --clusters N      Number of clusters to show (default: 10)");
		Console.root().println("  --reveal          Open prototype files in Finder/Explorer (requires --samples)");
		Console.root().println("  --help            Show this help message");
		Console.root().println("");
		Console.root().println("Examples:");
		Console.root().println("  # Show prototypes with file paths:");
		Console.root().println("  java -cp ... org.almostrealism.studio.discovery.PrototypeDiscovery \\");
		Console.root().println("    --data ~/.almostrealism/library --samples ~/Music/Samples --clusters 5");
		Console.root().println("");
		Console.root().println("  # Show prototypes without file paths (identifiers only):");
		Console.root().println("  java -cp ... org.almostrealism.studio.discovery.PrototypeDiscovery \\");
		Console.root().println("    --data ~/.almostrealism/library --clusters 5");
	}
}
