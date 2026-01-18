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

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.audio.persistence.LibraryDestination;
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
 * java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery [options]
 *
 * Options:
 *   --data PREFIX     Path prefix for protobuf library files (required)
 *                     Files are expected at PREFIX_0.bin, PREFIX_1.bin, etc.
 *   --clusters N      Number of clusters to show (default: 10)
 *   --reveal          Open files in Finder/Explorer
 * </pre>
 *
 * <h2>Example</h2>
 * <pre>
 * java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
 *   --data ~/.almostrealism/library --clusters 5
 * </pre>
 *
 * @see AudioLibraryPersistence
 * @see AudioSimilarityGraph
 * @see org.almostrealism.graph.algorithm.GraphCentrality
 * @see org.almostrealism.graph.algorithm.CommunityDetection
 */
public class PrototypeDiscovery implements ConsoleFeatures {

	private final String dataPrefix;
	private final int maxClusters;
	private final boolean reveal;

	public PrototypeDiscovery(String dataPrefix, int maxClusters, boolean reveal) {
		this.dataPrefix = dataPrefix;
		this.maxClusters = maxClusters;
		this.reveal = reveal;
	}

	public void run() throws Exception {
		log("=== Prototype Discovery ===");
		log("Data prefix: " + dataPrefix);
		log("Max clusters: " + maxClusters);
		log("");

		// Load pre-computed library data from protobuf
		log("Loading library from protobuf...");
		LibraryDestination destination = new LibraryDestination(dataPrefix);
		List<WaveDetails> allDetails = new ArrayList<>();

		try {
			AudioLibraryPersistence.loadLibrary(null, destination.in())
					.getAllDetails()
					.stream()
					.filter(this::hasFeatures)
					.forEach(allDetails::add);
		} catch (Exception e) {
			// loadLibrary with null AudioLibrary won't work, need different approach
		}

		// Actually load the protobuf data directly
		allDetails.clear();
		var libraryDataList = destination.load();
		for (var libraryData : libraryDataList) {
			for (var entry : libraryData.getInfoMap().entrySet()) {
				WaveDetails details = AudioLibraryPersistence.decode(entry.getValue());
				if (hasFeatures(details)) {
					allDetails.add(details);
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
		AudioSimilarityGraph graph = AudioSimilarityGraph.fromDetails(allDetails);
		log("  Nodes: " + graph.nodeCount());

		// Count edges
		int edgeCount = 0;
		for (int i = 0; i < graph.nodeCount(); i++) {
			edgeCount += graph.neighborIndices(i).size();
		}
		edgeCount /= 2; // Undirected graph
		log("  Edges: " + edgeCount);

		if (edgeCount == 0) {
			log("WARNING: No similarity edges found in pre-computed data.");
			log("         Will compute similarities on the fly...");
			computeMissingSimilarities(allDetails);
			graph = AudioSimilarityGraph.fromDetails(allDetails);

			edgeCount = 0;
			for (int i = 0; i < graph.nodeCount(); i++) {
				edgeCount += graph.neighborIndices(i).size();
			}
			edgeCount /= 2;
			log("  Edges after computation: " + edgeCount);
		}

		if (edgeCount == 0) {
			log("ERROR: Still no similarity edges. Cannot proceed.");
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
			String id = p.details.getIdentifier();
			String name = getDisplayName(id);

			log(String.format("Cluster %d (%d samples):", i + 1, p.communitySize));
			log(String.format("  Prototype: %s", name));
			log(String.format("  Centrality: %.6f", p.centrality));
			log(String.format("  Identifier: %s", id));
			log("");

			if (reveal && id != null) {
				revealInFinder(id);
			}
		}

		// Show modularity score
		double modularity = CommunityDetection.modularity(graph, communities);
		log("----------------------------------------");
		log(String.format("Modularity score: %.3f", modularity));
		log("  (Values > 0.3 indicate significant community structure)");
		log("  (Higher values = better-defined clusters)");

		log("");
		log("Done.");
	}

	private boolean hasFeatures(WaveDetails details) {
		return details != null && details.getFeatureData() != null;
	}

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
			File f = new File(path);
			if (!f.exists()) {
				warn("File does not exist: " + path);
				return;
			}

			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("mac")) {
				Runtime.getRuntime().exec(new String[]{"open", "-R", path});
			} else if (os.contains("win")) {
				Runtime.getRuntime().exec(new String[]{"explorer", "/select,", path});
			} else {
				// Linux - open parent directory
				File parent = f.getParentFile();
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
		String dataPrefix = null;
		int maxClusters = 10;
		boolean reveal = false;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--data" -> dataPrefix = args[++i];
				case "--clusters" -> maxClusters = Integer.parseInt(args[++i]);
				case "--reveal" -> reveal = true;
				case "--help" -> {
					printUsage();
					return;
				}
			}
		}

		if (dataPrefix == null) {
			System.err.println("ERROR: No data prefix specified.");
			System.err.println("Use --data PREFIX to specify the library protobuf file prefix.");
			System.err.println();
			printUsage();
			System.exit(1);
		}

		// Verify at least one data file exists
		File firstFile = new File(dataPrefix + "_0.bin");
		if (!firstFile.exists()) {
			System.err.println("ERROR: Library data file not found:");
			System.err.println("       " + firstFile.getAbsolutePath());
			System.err.println();
			System.err.println("Expected files: " + dataPrefix + "_0.bin, " + dataPrefix + "_1.bin, ...");
			System.exit(1);
		}

		PrototypeDiscovery discovery = new PrototypeDiscovery(dataPrefix, maxClusters, reveal);
		discovery.run();
	}

	private static void printUsage() {
		System.out.println("Prototype Discovery - Find representative samples from pre-computed library data");
		System.out.println();
		System.out.println("Usage: PrototypeDiscovery [options]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  --data PREFIX     Path prefix for protobuf library files (required)");
		System.out.println("                    Files are expected at PREFIX_0.bin, PREFIX_1.bin, etc.");
		System.out.println("  --clusters N      Number of clusters to show (default: 10)");
		System.out.println("  --reveal          Open prototype files in Finder/Explorer");
		System.out.println("  --help            Show this help message");
		System.out.println();
		System.out.println("Example:");
		System.out.println("  java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \\");
		System.out.println("    --data ~/.almostrealism/library --clusters 5");
	}
}
