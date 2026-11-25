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

package org.almostrealism.studio.discovery.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.algorithm.GraphFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.SAMEAutoEncoder;
import org.almostrealism.studio.discovery.PrototypeDiscovery;
import org.almostrealism.studio.ml.CompiledModelAutoEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Investigates why {@link PrototypeDiscovery} returns prototypes that are too similar to each
 * other, using a labelled subset of a real sample library and the Stable Audio 3 (SAME-S)
 * autoencoder features the application indexes.
 *
 * <p>Every clip is assigned a ground-truth family from the folder it sits in (kick, snare, closed
 * hat, sustained synth tone, beat loop, ...). Each clip is encoded once and pooled two ways:</p>
 * <ul>
 *   <li>{@code all-frames}: {@link WaveFeatures#meanPooledEmbedding()} over every latent frame of
 *       the fixed-length encoder buffer, which is the vector the application inserts into the HNSW
 *       index and searches with;</li>
 *   <li>{@code valid-frames}: the same mean restricted to the frames that cover the clip itself,
 *       the range {@link WaveDetails#getValidFeatureFrameCount()} describes.</li>
 * </ul>
 *
 * <p>A third measure, {@code frame-aligned}, is the per-frame similarity
 * {@link WaveDetailsFactory#batchSimilarity} computes for the similar-samples view.</p>
 *
 * <p>For each pooling the test reports how well the embeddings separate the families, then runs
 * the discovery graph pipeline (exact top-K neighbour graph, {@link AudioSimilarityGraph}, Louvain,
 * PageRank) and reports per-community size, family composition, intra and inter similarity, how
 * typical the PageRank representative is of its community, and how similar the representatives
 * are to each other. A small sweep over K and the Louvain resolution separates a partition
 * problem from an embedding problem.</p>
 *
 * <p>This is a long-running investigation on developer-local data: it needs the sample library
 * ({@code AR_RINGS_LIBRARY}) and the SAME-S weights ({@code AR_SA3_AE_WEIGHTS}).</p>
 */
public class PrototypeDiversityDiagnosticTest extends TestSuiteBase implements GraphFeatures {

	/** Root of the sample library the families are drawn from. */
	private static final String SAMPLES_ROOT =
			SystemUtils.getProperty("AR_RINGS_LIBRARY", "/Users/Shared/Music/Samples");

	/** Directory holding the SAME-S autoencoder weights. */
	private static final String AE_WEIGHTS =
			SystemUtils.getProperty("AR_SA3_AE_WEIGHTS", "/Users/Shared/models/stable-audio-3-autoencoder");

	/** Clips taken from each family. */
	private static final int PER_FAMILY =
			Integer.parseInt(SystemUtils.getProperty("AR_PROTOTYPE_DIAG_PER_FAMILY", "16"));

	/** Length of the encoder buffer, in seconds; longer clips are truncated, shorter ones padded. */
	private static final double MAX_SECONDS =
			Double.parseDouble(SystemUtils.getProperty("AR_PROTOTYPE_DIAG_SECONDS", "8.0"));

	/** Sample rate the clips are encoded at. */
	private static final int SAMPLE_RATE = 44100;

	/** Number of nearest neighbours inspected for the label-agreement and cross-kind statistics. */
	private static final int QUERY_NEIGHBORS = 10;

	/** Members whose names are printed for each community. */
	private static final int SAMPLE_MEMBERS = 4;

	/** Embedding components printed for each sampled member. */
	private static final int SAMPLE_COMPONENTS = 4;

	/** Separates a folder that is searched recursively from the file-name pattern its files must match. */
	private static final String RECURSIVE = "::";

	/**
	 * Families, keyed by name, mapped to where their files come from: a library-relative folder,
	 * a file-name prefix at the library root, or a folder searched recursively for file names
	 * matching a pattern ({@code folder::pattern}). The {@code silence} family is a control: the
	 * {@code AUMIDISynth} renders in the library are digital silence, so their embedding is the
	 * encoding of silence that pads every short clip.
	 */
	private static final Map<String, String> FAMILIES = new LinkedHashMap<>();

	/** The broad kind of each family: one-shot percussion, sustained note, loop, or silence. */
	private static final Map<String, String> KINDS = new HashMap<>();

	static {
		String hits = "Essential WAV From Mars/Drums/01. Individual Hits/";
		String loops = "RAW LOOPS - IBIZA UNDERGROUND/";
		family("kick", hits + "01. Bass Drum", "one-shot");
		family("snare", hits + "02. Snare Drum", "one-shot");
		family("clap", hits + "03. Clap", "one-shot");
		family("hat-closed", hits + "04. Hi Hat/CH", "one-shot");
		family("hat-open", hits + "04. Hi Hat/OH", "one-shot");
		family("tom", hits + "06. Tom", "one-shot");
		family("cymbal", hits + "10. Cymbal", "one-shot");
		family("synth-note", "Essential WAV From Mars/Synths" + RECURSIVE + ".* C[0-9]\\.wav", "sustained");
		family("silence", "AUMIDISynth", "silence");
		family("bass-loop", loops + "BASS LOOPS_125", "loop");
		family("beat-loop", loops + "BEAT LOOPS_125", "loop");
		family("music-loop", loops + "MUSIC LOOPS_125", "loop");
		family("perc-loop", loops + "PERC LOOPS_125", "loop");
		family("top-loop", loops + "TOP LOOPS_125", "loop");
		family("kick-loop", loops + "RAW KICKS_125", "loop");
		family("fx-texture", loops + "TEXTURES & FX_125", "loop");
	}

	/**
	 * Registers a family.
	 *
	 * @param name   the family name
	 * @param source the library-relative folder, the file-name prefix of files at the root, or
	 *               {@code folder::pattern}
	 * @param kind   the broad kind of sound
	 */
	private static void family(String name, String source, String kind) {
		FAMILIES.put(name, source);
		KINDS.put(name, kind);
	}

	/** One encoded clip with its ground truth. */
	private record Clip(String name, String family, int audioFrames, int validFrames,
						PackedCollection features) {
		/** @return the broad kind of the clip's family */
		String kind() { return KINDS.get(family); }
	}

	/**
	 * Encodes the labelled subset and reports the separation and community statistics for both
	 * poolings.
	 *
	 * @throws Exception if the weights or a clip cannot be read
	 */
	@Test(timeout = 2400000)
	public void diagnosePrototypeDiversity() throws Exception {
		if (skipLongTests) return;

		File root = new File(SAMPLES_ROOT);
		File weights = new File(AE_WEIGHTS);
		requireLocalResource("sample library " + root, "AR_RINGS_LIBRARY", root.isDirectory());
		requireLocalResource("SAME-S weights " + weights, "AR_SA3_AE_WEIGHTS", weights.isDirectory());

		StateDictionary dictionary = new StateDictionary(weights.getPath());
		SAMEAutoEncoder autoencoder = SAMEAutoEncoder.small(dictionary);
		CompiledModelAutoEncoder encoder =
				CompiledModelAutoEncoder.of(autoencoder, 1, SAMPLE_RATE, MAX_SECONDS);
		TraversalPolicy audioShape = encoder.getAudioShape();
		int samples = audioShape.length(2);
		log("encoder buffer " + audioShape + " latentRate=" + encoder.getLatentSampleRate());

		List<Clip> clips = new ArrayList<>();
		long start = System.currentTimeMillis();
		for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
			for (File file : select(root, family.getValue())) {
				Clip clip = encode(encoder, samples, file, family.getKey());
				if (clip != null) clips.add(clip);
			}
			log("encoded family=" + family.getKey() + " clips=" + clips.size()
					+ " elapsedSeconds=" + (System.currentTimeMillis() - start) / 1000);
		}

		long families = clips.stream().map(Clip::family).distinct().count();
		assertEquals("every family should contribute clips", FAMILIES.size(), families);

		PackedCollection allFrames = pool(clips, false);
		PackedCollection validFrames = pool(clips, true);
		analyze("all-frames", clips, allFrames, cosine(allFrames));
		analyze("valid-frames", clips, validFrames, cosine(validFrames));
		analyze("frame-aligned", clips, validFrames,
				frameAligned(clips, encoder.getLatentSampleRate()));
		allFrames.destroy();
		validFrames.destroy();

		encoder.destroy();
		dictionary.destroy();
	}

	/**
	 * Picks up to {@link #PER_FAMILY} files evenly spread over a family's sorted file list.
	 *
	 * @param root   the library root
	 * @param source the family's folder, its file-name prefix at the root, or {@code folder::pattern}
	 * @return the selected files
	 * @throws IOException if a folder cannot be walked
	 */
	private List<File> select(File root, String source) throws IOException {
		File[] files;
		if (source.contains(RECURSIVE)) {
			String[] parts = source.split(RECURSIVE, 2);
			Pattern pattern = Pattern.compile(parts[1]);
			try (Stream<Path> paths = Files.walk(new File(root, parts[0]).toPath())) {
				files = paths.map(Path::toFile)
						.filter(f -> f.isFile() && pattern.matcher(f.getName()).matches())
						.toArray(File[]::new);
			}
		} else {
			File dir = new File(root, source);
			files = dir.isDirectory()
					? dir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".wav"))
					: root.listFiles(f -> f.isFile() && f.getName().startsWith(source)
							&& f.getName().toLowerCase(Locale.ROOT).endsWith(".wav"));
		}
		if (files == null || files.length == 0) return List.of();

		Arrays.sort(files, Comparator.comparing(File::getName));
		int count = Math.min(PER_FAMILY, files.length);
		return IntStream.range(0, count)
				.mapToObj(i -> files[(int) ((long) i * files.length / count)])
				.toList();
	}

	/**
	 * Encodes one clip the way the application does: conformed to the encoder's stereo buffer
	 * (zero-padded or truncated), encoded, and transposed to {@code (frames, bins)}.
	 *
	 * @param encoder the compiled autoencoder
	 * @param samples the encoder buffer length in samples
	 * @param file    the clip
	 * @param family  the clip's family
	 * @return the encoded clip, or {@code null} if it cannot be read at {@link #SAMPLE_RATE}
	 */
	private Clip encode(CompiledModelAutoEncoder encoder, int samples, File file, String family) {
		WaveData wave;
		try {
			wave = WaveData.load(file);
		} catch (Exception e) {
			warn("skipping unreadable " + file.getName() + ": " + e.getMessage());
			return null;
		}

		if (wave.getSampleRate() != SAMPLE_RATE) {
			warn("skipping " + file.getName() + " at " + wave.getSampleRate() + " Hz");
			wave.destroy();
			return null;
		}

		PackedCollection audio = wave.conform(2, samples);
		PackedCollection latent = encoder.encode(cp(audio.reshape(1, 2, samples))).evaluate();
		int bins = latent.getShape().length(1);
		int frames = latent.getShape().length(2);
		PackedCollection features = cp(latent.reshape(bins, frames)).transpose().evaluate();

		double seconds = Math.min(wave.getDuration(), MAX_SECONDS);
		int valid = Math.max(1, Math.min(frames,
				(int) Math.ceil(seconds * encoder.getLatentSampleRate())));

		int audioFrames = Math.min(wave.getFrameCount(), samples);
		audio.destroy();
		wave.destroy();
		return new Clip(file.getName(), family, audioFrames, valid, features);
	}

	/**
	 * Mean-pools every clip into one row of an embedding matrix.
	 *
	 * @param clips the encoded clips
	 * @param valid whether to pool only the frames that cover the clip, rather than every frame
	 *              of the encoder buffer as the application's index does
	 * @return the {@code (clips, bins)} embeddings
	 */
	private PackedCollection pool(List<Clip> clips, boolean valid) {
		int n = clips.size();
		int bins = clips.get(0).features().getShape().length(1);

		PackedCollection embeddings = new PackedCollection(n, bins);
		for (int i = 0; i < n; i++) {
			Clip clip = clips.get(i);
			int frames = valid ? clip.validFrames() : clip.features().getShape().length(0);
			PackedCollection pooled = new WaveFeatures("diagnostic", 0, 1, bins, frames,
					clip.features().range(shape(frames, bins))).meanPooledEmbedding();
			cp(pooled).into(embeddings.range(shape(bins), i * bins)).evaluate();
			pooled.destroy();
		}

		return embeddings;
	}

	/**
	 * The cosine similarity of every pair of embeddings, the measure the HNSW index ranks by.
	 *
	 * @param embeddings the {@code (clips, bins)} embeddings
	 * @return the row-major {@code (clips, clips)} similarity matrix
	 */
	private double[] cosine(PackedCollection embeddings) {
		return matmul(normalize(cp(embeddings)), normalize(cp(embeddings)).transpose())
				.evaluate().toArray();
	}

	/**
	 * The frame-aligned similarity of every pair of clips, computed by
	 * {@link WaveDetailsFactory#batchSimilarity} exactly as {@code AudioLibrary.computeSimilarities}
	 * does for the similar-samples view: a trimmed mean of per-frame cosines over the longer
	 * clip's valid frames.
	 *
	 * @param clips      the encoded clips
	 * @param latentRate the feature frame rate
	 * @return the row-major {@code (clips, clips)} similarity matrix
	 */
	private double[] frameAligned(List<Clip> clips, double latentRate) {
		int n = clips.size();
		List<WaveDetails> details = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Clip clip = clips.get(i);
			WaveDetails d = new WaveDetails(String.valueOf(i), SAMPLE_RATE);
			d.setFrameCount(clip.audioFrames());
			PackedCollection features = clip.features();
			d.putFeatures(new WaveFeatures("diagnostic", latentRate, 1,
					features.getShape().length(1), features.getShape().length(0), features));
			d.setActiveModelIdentifier("diagnostic");
			details.add(d);
		}

		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		double[] similarity = new double[n * n];
		for (int i = 0; i < n; i++) {
			double[] row = factory.batchSimilarity(details.get(i), details);
			System.arraycopy(row, 0, similarity, i * n, n);
		}
		return similarity;
	}

	/**
	 * Reports family separation, the discovery communities at the production settings, and a
	 * K/resolution sweep, for one similarity measure.
	 *
	 * @param label      name of the measure, for the log
	 * @param clips      the encoded clips
	 * @param embeddings the pooled embeddings, of which a few components are logged per member
	 * @param similarity the row-major similarity matrix
	 */
	private void analyze(String label, List<Clip> clips, PackedCollection embeddings, double[] similarity) {
		assertTrue("the " + label + " similarity matrix has a non-finite entry",
				Arrays.stream(similarity).allMatch(Double::isFinite));

		CommunityReport report = new CommunityReport(label, clips, embeddings, similarity);
		report.logSeparation();
		report.logCommunities(PrototypeDiscovery.DEFAULT_K_NEIGHBORS, 1.0, true);

		for (int k : new int[] {5, 10, 20, 40}) {
			for (double resolution : new double[] {0.5, 1.0, 2.0}) {
				report.logCommunities(k, resolution, false);
			}
		}
	}

	/**
	 * Statistics over one pooling: a cosine similarity matrix over the clips plus their ground
	 * truth, evaluated once, from which separation and community statistics are read.
	 */
	private class CommunityReport {
		/** Name of the pooling. */
		private final String label;

		/** The clips, in matrix order. */
		private final List<Clip> clips;

		/** The pooled embeddings, one row per clip. */
		private final PackedCollection embeddings;

		/** Row-major cosine similarity matrix over the clips. */
		private final double[] similarity;

		/** Number of clips. */
		private final int n;

		/**
		 * Creates the report.
		 *
		 * @param label      name of the pooling
		 * @param clips      the clips
		 * @param embeddings the pooled embeddings
		 * @param similarity the cosine similarity matrix
		 */
		CommunityReport(String label, List<Clip> clips, PackedCollection embeddings, double[] similarity) {
			this.label = label;
			this.clips = clips;
			this.embeddings = embeddings;
			this.similarity = similarity;
			this.n = clips.size();
		}

		/** @return the cosine similarity of clips {@code i} and {@code j} */
		double sim(int i, int j) { return similarity[i * n + j]; }

		/**
		 * The clips most similar to clip {@code i}, most similar first, excluding itself.
		 *
		 * @param i the query clip
		 * @param k how many to return
		 * @return the neighbour indices
		 */
		List<Integer> neighbors(int i, int k) {
			return IntStream.range(0, n).filter(j -> j != i).boxed()
					.sorted(Comparator.comparingDouble((Integer j) -> sim(i, j)).reversed())
					.limit(k).toList();
		}

		/** @return mean similarity between two index sets, skipping identical indices */
		double meanSim(List<Integer> a, List<Integer> b) {
			double sum = 0;
			long count = 0;
			for (int i : a) {
				for (int j : b) {
					if (i == j) continue;
					sum += sim(i, j);
					count++;
				}
			}
			return count == 0 ? Double.NaN : sum / count;
		}

		/** Logs how well the pooled embeddings separate the labelled families and kinds. */
		void logSeparation() {
			Map<String, List<Integer>> byFamily = new LinkedHashMap<>();
			Map<String, List<Integer>> byKind = new LinkedHashMap<>();
			for (int i = 0; i < n; i++) {
				byFamily.computeIfAbsent(clips.get(i).family(), f -> new ArrayList<>()).add(i);
				byKind.computeIfAbsent(clips.get(i).kind(), f -> new ArrayList<>()).add(i);
			}

			double min = Double.POSITIVE_INFINITY;
			double sum = 0;
			for (int i = 0; i < n; i++) {
				for (int j = i + 1; j < n; j++) {
					min = Math.min(min, sim(i, j));
					sum += sim(i, j);
				}
			}
			log(String.format("pooling=%s clips=%d overall pairwise cosine min=%.4f mean=%.4f",
					label, n, min, sum / ((double) n * (n - 1) / 2)));

			double intraSum = 0;
			double interSum = 0;
			for (Map.Entry<String, List<Integer>> f : byFamily.entrySet()) {
				List<Integer> others = IntStream.range(0, n)
						.filter(j -> !clips.get(j).family().equals(f.getKey())).boxed().toList();
				double intra = meanSim(f.getValue(), f.getValue());
				double inter = meanSim(f.getValue(), others);
				String nearest = byFamily.keySet().stream().filter(g -> !g.equals(f.getKey()))
						.max(Comparator.comparingDouble(g -> meanSim(f.getValue(), byFamily.get(g))))
						.orElse("-");
				long agree = 0;
				for (int i : f.getValue()) {
					agree += neighbors(i, QUERY_NEIGHBORS).stream()
							.filter(j -> clips.get(j).family().equals(f.getKey())).count();
				}
				intraSum += intra;
				interSum += inter;
				log(String.format("pooling=%s family=%s n=%d intra=%.4f inter=%.4f margin=%.4f "
								+ "nearestFamily=%s(%.4f) top%dSameFamily=%.2f",
						label, f.getKey(), f.getValue().size(), intra, inter, intra - inter,
						nearest, meanSim(f.getValue(), byFamily.get(nearest)), QUERY_NEIGHBORS,
						agree / (double) (f.getValue().size() * QUERY_NEIGHBORS)));
			}
			log(String.format("pooling=%s family mean intra=%.4f mean inter=%.4f",
					label, intraSum / byFamily.size(), interSum / byFamily.size()));

			for (Map.Entry<String, List<Integer>> k : byKind.entrySet()) {
				Map<String, Long> neighborKinds = new LinkedHashMap<>();
				for (int i : k.getValue()) {
					for (int j : neighbors(i, QUERY_NEIGHBORS)) {
						neighborKinds.merge(clips.get(j).kind(), 1L, Long::sum);
					}
				}
				StringBuilder kinds = new StringBuilder();
				for (Map.Entry<String, Long> e : neighborKinds.entrySet()) {
					kinds.append(String.format(" %s=%.2f", e.getKey(),
							e.getValue() / (double) (k.getValue().size() * QUERY_NEIGHBORS)));
				}
				log(String.format("pooling=%s kind=%s n=%d intra=%.4f top%dNeighborKinds:%s",
						label, k.getKey(), k.getValue().size(),
						meanSim(k.getValue(), k.getValue()), QUERY_NEIGHBORS, kinds));
			}
		}

		/**
		 * Builds the discovery graph from each clip's top-{@code k} neighbours, runs Louvain and
		 * PageRank as {@code PrototypeDiscovery} does, and logs the resulting communities.
		 *
		 * @param k          neighbours per clip
		 * @param resolution the Louvain resolution
		 * @param detail     whether to log every community, or only the summary line
		 */
		void logCommunities(int k, double resolution, boolean detail) {
			List<WaveDetails> details = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				WaveDetails d = new WaveDetails(String.valueOf(i), SAMPLE_RATE);
				Map<String, Double> similarities = new HashMap<>();
				for (int j : neighbors(i, k)) {
					similarities.put(String.valueOf(j), sim(i, j));
				}
				d.setSimilarities(similarities);
				details.add(d);
			}

			AudioSimilarityGraph graph = new AudioSimilarityGraph(details);
			int[] communities = louvain(graph, resolution);
			double[] ranks = pageRank(graph, 0.85, 50);
			List<List<Integer>> members = getCommunityMembers(communities).values().stream()
					.sorted(Comparator.comparingInt((List<Integer> m) -> m.size()).reversed())
					.toList();

			List<Integer> reps = new ArrayList<>();
			List<Integer> medoids = new ArrayList<>();
			double purity = 0;
			double typicalityRep = 0;
			double typicalityMedoid = 0;
			int multi = 0;

			for (int c = 0; c < members.size(); c++) {
				List<Integer> m = members.get(c);
				int rep = m.stream().max(Comparator.comparingDouble(i -> ranks[i])).orElseThrow();
				int medoid = m.stream().max(Comparator.comparingDouble(
						i -> meanSim(List.of(i), m))).orElseThrow();
				reps.add(rep);
				medoids.add(medoid);

				Map<String, Long> composition = new LinkedHashMap<>();
				m.forEach(i -> composition.merge(clips.get(i).family(), 1L, Long::sum));
				long majority = composition.values().stream().mapToLong(Long::longValue).max().orElse(0);
				purity += majority;

				if (m.size() > 1) {
					multi++;
					typicalityRep += meanSim(List.of(rep), m);
					typicalityMedoid += meanSim(List.of(medoid), m);
				}

				if (detail) {
					List<Integer> rest = IntStream.range(0, n).filter(i -> !m.contains(i)).boxed().toList();
					double nearestOther = members.stream().filter(o -> o != m)
							.mapToDouble(o -> meanSim(m, o)).max().orElse(Double.NaN);
					log(String.format("pooling=%s community=%d size=%d intra=%.4f inter=%.4f "
									+ "nearestCommunity=%.4f repTypicality=%.4f medoidTypicality=%.4f "
									+ "rep=%s composition=%s",
							label, c, m.size(), meanSim(m, m), meanSim(m, rest), nearestOther,
							meanSim(List.of(rep), m), meanSim(List.of(medoid), m),
							clips.get(rep).name(), composition));
					for (int s = 0; s < Math.min(SAMPLE_MEMBERS, m.size()); s++) {
						int i = m.get(s);
						double[] head = embeddings.toArray(i * embeddings.getShape().length(1),
								SAMPLE_COMPONENTS);
						log(String.format("pooling=%s community=%d member=%s family=%s embeddingHead=%s",
								label, c, clips.get(i).name(), clips.get(i).family(),
								Arrays.toString(Arrays.stream(head)
										.mapToObj(v -> String.format("%.3f", v)).toArray())));
					}
				}
			}

			int top = Math.min(10, reps.size());
			log(String.format("pooling=%s K=%d resolution=%.1f communities=%d purity=%.3f nmi=%.3f "
							+ "repTypicality=%.4f medoidTypicality=%.4f "
							+ "top%dRepPairwise=%.4f top%dMedoidPairwise=%.4f top%dRandomPairwise=%.4f "
							+ "top%dRepMaxPair=%.4f top%dRepFamilies=%d",
					label, k, resolution, members.size(), purity / n, nmi(members),
					multi == 0 ? Double.NaN : typicalityRep / multi,
					multi == 0 ? Double.NaN : typicalityMedoid / multi,
					top, meanSim(reps.subList(0, top), reps.subList(0, top)),
					top, meanSim(medoids.subList(0, top), medoids.subList(0, top)),
					top, meanSim(randomMembers(members, top), randomMembers(members, top)),
					top, maxPair(reps.subList(0, top)),
					top, reps.subList(0, top).stream().map(i -> clips.get(i).family()).distinct().count()));
		}

		/** @return the middle member of each of the largest {@code top} communities, a selection blind to centrality */
		private List<Integer> randomMembers(List<List<Integer>> members, int top) {
			return members.subList(0, top).stream().map(m -> m.get(m.size() / 2)).toList();
		}

		/** @return the largest similarity between two distinct entries */
		private double maxPair(List<Integer> items) {
			double max = Double.NEGATIVE_INFINITY;
			for (int a = 0; a < items.size(); a++) {
				for (int b = a + 1; b < items.size(); b++) {
					max = Math.max(max, sim(items.get(a), items.get(b)));
				}
			}
			return max;
		}

		/**
		 * Normalised mutual information between the communities and the family labels.
		 *
		 * @param members the communities, as clip indices
		 * @return NMI in {@code [0, 1]}
		 */
		private double nmi(List<List<Integer>> members) {
			Map<String, Integer> familyCounts = new HashMap<>();
			clips.forEach(c -> familyCounts.merge(c.family(), 1, Integer::sum));

			double hc = 0;
			double hf = 0;
			double mi = 0;
			for (List<Integer> m : members) {
				double pc = m.size() / (double) n;
				hc -= pc * Math.log(pc);
				Map<String, Integer> joint = new HashMap<>();
				m.forEach(i -> joint.merge(clips.get(i).family(), 1, Integer::sum));
				for (Map.Entry<String, Integer> e : joint.entrySet()) {
					double pj = e.getValue() / (double) n;
					double pf = familyCounts.get(e.getKey()) / (double) n;
					mi += pj * Math.log(pj / (pc * pf));
				}
			}
			for (int count : familyCounts.values()) {
				double pf = count / (double) n;
				hf -= pf * Math.log(pf);
			}
			return hc + hf == 0 ? 0 : 2 * mi / (hc + hf);
		}
	}
}
