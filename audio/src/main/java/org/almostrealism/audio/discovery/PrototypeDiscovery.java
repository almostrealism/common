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

package org.almostrealism.audio.discovery;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.graph.algorithm.CommunityDetection;
import org.almostrealism.graph.algorithm.GraphCentrality;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headless console application for discovering prototypical audio samples.
 *
 * <p>This tool loads an audio library using {@link FileWaveDataProviderNode} and
 * {@link AudioLibrary} directly, computes similarity graphs using frequency analysis,
 * and finds representative samples (prototypes) using graph algorithms.</p>
 *
 * <p>This version does not require JavaFX or the autoencoder - it uses frequency-based
 * similarity which is computed by {@link org.almostrealism.audio.data.WaveDetailsFactory}.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery [options]
 *
 * Options:
 *   --library PATH    Path to audio library (required, or set AR_RINGS_LIBRARY)
 *   --clusters N      Number of clusters to find (default: 10)
 *   --reveal          Open files in Finder/Explorer
 *   --wait SECONDS    Max wait time for analysis (default: 300)
 * </pre>
 *
 * <h2>Example</h2>
 * <pre>
 * java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
 *   --library ~/Music/Samples --clusters 5 --reveal
 * </pre>
 *
 * @see AudioLibrary
 * @see AudioSimilarityGraph
 * @see org.almostrealism.graph.algorithm.GraphCentrality
 * @see org.almostrealism.graph.algorithm.CommunityDetection
 */
public class PrototypeDiscovery implements ConsoleFeatures {

	private final File libraryRoot;
	private final int maxClusters;
	private final boolean reveal;
	private final int maxWaitSeconds;

	private AudioLibrary library;

	public PrototypeDiscovery(File libraryRoot, int maxClusters, boolean reveal, int maxWaitSeconds) {
		this.libraryRoot = libraryRoot;
		this.maxClusters = maxClusters;
		this.reveal = reveal;
		this.maxWaitSeconds = maxWaitSeconds;
	}

	public void run() throws Exception {
		log("=== Prototype Discovery ===");
		log("Library: " + libraryRoot.getAbsolutePath());
		log("Max clusters: " + maxClusters);
		log("");

		// Create file tree from directory
		log("Scanning library directory...");
		FileWaveDataProviderNode root = new FileWaveDataProviderNode(libraryRoot);

		// Create audio library
		log("Creating audio library...");
		library = new AudioLibrary(root, OutputLine.sampleRate);

		// Wait for library to analyze files
		log("Analyzing audio files (this may take a while)...");
		CountDownLatch latch = new CountDownLatch(1);
		library.refresh().thenRun(latch::countDown);

		// Show progress periodically
		long startTime = System.currentTimeMillis();
		while (!latch.await(5, TimeUnit.SECONDS)) {
			double progress = library.getProgress();
			int pending = library.getPendingJobs();
			long elapsed = (System.currentTimeMillis() - startTime) / 1000;
			log(String.format("  Progress: %.1f%% (%d jobs pending, %ds elapsed)",
					progress * 100, pending, elapsed));

			if (elapsed > maxWaitSeconds) {
				log("WARNING: Max wait time exceeded, proceeding with available data");
				break;
			}
		}

		// Get all analyzed samples
		Collection<WaveDetails> allDetails = library.getAllDetails();
		log("");
		log("Analyzed " + allDetails.size() + " audio samples");

		if (allDetails.isEmpty()) {
			log("ERROR: No audio samples found in library");
			library.stop();
			return;
		}

		// Compute similarities for all samples
		log("Computing similarities between samples...");
		int count = 0;
		for (WaveDetails details : allDetails) {
			if (details.getSimilarities().isEmpty()) {
				library.getSimilarities(details);
			}
			count++;
			if (count % 50 == 0) {
				log(String.format("  Processed %d/%d samples", count, allDetails.size()));
			}
		}

		// Build similarity graph
		log("");
		log("Building similarity graph...");
		AudioSimilarityGraph graph = AudioSimilarityGraph.fromLibrary(library);
		log("  Nodes: " + graph.nodeCount());

		// Count edges
		int edgeCount = 0;
		for (int i = 0; i < graph.nodeCount(); i++) {
			edgeCount += graph.neighborIndices(i).size();
		}
		edgeCount /= 2; // Undirected graph
		log("  Edges: " + edgeCount);

		if (edgeCount == 0) {
			log("ERROR: No similarity edges found.");
			log("       This may happen if samples are too different or analysis failed.");
			library.stop();
			return;
		}

		// Run community detection (Louvain algorithm)
		log("");
		log("Detecting communities (Louvain algorithm)...");
		int[] communities = CommunityDetection.louvain(graph, 1.0);
		int numCommunities = CommunityDetection.countCommunities(communities);
		log("  Found " + numCommunities + " communities");

		// Compute PageRank centrality
		log("Computing centrality (PageRank)...");
		double[] ranks = GraphCentrality.pageRank(graph, 0.85, 50);

		// Get community members
		Map<Integer, List<Integer>> communityMembers =
				CommunityDetection.getCommunityMembers(communities);

		// Find prototypes (highest-centrality node in each community)
		log("");
		log("========================================");
		log("           DISCOVERED PROTOTYPES       ");
		log("========================================");
		log("");

		List<Prototype> prototypes = new ArrayList<>();
		for (var entry : communityMembers.entrySet()) {
			int communityId = entry.getKey();
			List<Integer> members = entry.getValue();

			// Find most central member in this community
			int prototypeIdx = members.stream()
					.max(Comparator.comparingDouble(i -> ranks[i]))
					.orElse(-1);

			if (prototypeIdx >= 0) {
				WaveDetails details = graph.nodeAt(prototypeIdx);
				prototypes.add(new Prototype(
						communityId,
						details,
						ranks[prototypeIdx],
						members.size()
				));
			}
		}

		// Sort by community size (largest clusters first)
		prototypes.sort(Comparator.comparingInt((Prototype p) -> p.communitySize).reversed());

		// Display top prototypes
		int displayCount = Math.min(prototypes.size(), maxClusters);
		for (int i = 0; i < displayCount; i++) {
			Prototype p = prototypes.get(i);
			String path = getFilePath(p.details);
			String name = getDisplayName(path);

			log(String.format("Cluster %d (%d samples):", i + 1, p.communitySize));
			log(String.format("  Prototype: %s", name));
			log(String.format("  Centrality: %.6f", p.centrality));
			log(String.format("  Path: %s", path));
			log("");

			if (reveal && path != null) {
				revealInFinder(path);
			}
		}

		// Show modularity score
		double modularity = CommunityDetection.modularity(graph, communities);
		log("----------------------------------------");
		log(String.format("Modularity score: %.3f", modularity));
		log("  (Values > 0.3 indicate significant community structure)");
		log("  (Higher values = better-defined clusters)");

		// Cleanup
		log("");
		log("Shutting down...");
		library.stop();
		log("Done.");
	}

	private String getFilePath(WaveDetails details) {
		if (details == null || details.getIdentifier() == null) return null;

		var provider = library.find(details.getIdentifier());
		if (provider != null) {
			return provider.getKey();
		}

		return details.getIdentifier();
	}

	private String getDisplayName(String path) {
		if (path == null) return "unknown";
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path;
	}

	private void revealInFinder(String path) {
		try {
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("mac")) {
				Runtime.getRuntime().exec(new String[]{"open", "-R", path});
			} else if (os.contains("win")) {
				Runtime.getRuntime().exec(new String[]{"explorer", "/select,", path});
			} else {
				// Linux - open parent directory
				File parent = new File(path).getParentFile();
				if (parent != null) {
					Runtime.getRuntime().exec(new String[]{"xdg-open", parent.getAbsolutePath()});
				}
			}
		} catch (IOException e) {
			warn("Could not reveal file: " + e.getMessage());
		}
	}

	record Prototype(int communityId, WaveDetails details, double centrality, int communitySize) {}

	public static void main(String[] args) throws Exception {
		// Parse arguments
		String libraryPath = System.getenv("AR_RINGS_LIBRARY");
		if (libraryPath == null) {
			libraryPath = System.getProperty("AR_RINGS_LIBRARY");
		}

		int maxClusters = 10;
		boolean reveal = false;
		int maxWait = 300;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--library" -> libraryPath = args[++i];
				case "--clusters" -> maxClusters = Integer.parseInt(args[++i]);
				case "--reveal" -> reveal = true;
				case "--wait" -> maxWait = Integer.parseInt(args[++i]);
				case "--help" -> {
					printUsage();
					return;
				}
			}
		}

		if (libraryPath == null) {
			System.err.println("ERROR: No library path specified.");
			System.err.println("Use --library PATH or set AR_RINGS_LIBRARY environment variable.");
			System.err.println();
			printUsage();
			System.exit(1);
		}

		File libraryRoot = new File(libraryPath);
		if (!libraryRoot.exists() || !libraryRoot.isDirectory()) {
			System.err.println("ERROR: Library path does not exist or is not a directory:");
			System.err.println("       " + libraryPath);
			System.exit(1);
		}

		PrototypeDiscovery discovery = new PrototypeDiscovery(
				libraryRoot, maxClusters, reveal, maxWait);
		discovery.run();
	}

	private static void printUsage() {
		System.out.println("Prototype Discovery - Find representative samples in an audio library");
		System.out.println();
		System.out.println("Usage: PrototypeDiscovery [options]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  --library PATH    Path to audio library directory");
		System.out.println("  --clusters N      Number of clusters to show (default: 10)");
		System.out.println("  --reveal          Open prototype files in Finder/Explorer");
		System.out.println("  --wait SECONDS    Max wait time for analysis (default: 300)");
		System.out.println("  --help            Show this help message");
		System.out.println();
		System.out.println("Environment:");
		System.out.println("  AR_RINGS_LIBRARY  Default library path if --library not specified");
		System.out.println();
		System.out.println("Example:");
		System.out.println("  java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \\");
		System.out.println("    --library ~/Music/Samples --clusters 5 --reveal");
	}
}
