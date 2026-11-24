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

package org.almostrealism.ml.clap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.SAMEAutoEncoder;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The real-weight measurements of the native CLAP2 port: parity of the log-mel front end and of
 * both towers with the reference model, zero-shot tagging accuracy, per-clip inference cost and
 * device memory beside the SAME-S autoencoder, on the musical probe set
 * {@code engine/ml/scripts/dump_clap2_reference.py} writes (one-shots, loops and stems scored
 * against twenty tag captions).
 *
 * <p>The weights and the reference dump are local resources, located through the
 * {@code AR_CLAP2_WEIGHTS} and {@code AR_CLAP2_REFERENCES} system properties (or environment
 * variables of the same names), and the SAME-S weights through {@code AR_SAME_WEIGHTS}. Where a
 * resource is absent the test is reported as skipped by {@link #requireLocalResource}; where it is
 * declared but missing the test fails. Each test writes its measurements to
 * {@code results/clap2_*.out}.</p>
 */
public class Clap2TaggingSpikeTest extends TestSuiteBase implements AttentionFeatures {

	/** The extracted CLAP2 weights. */
	private static final String WEIGHTS = System.getProperty("AR_CLAP2_WEIGHTS", System.getenv("AR_CLAP2_WEIGHTS"));

	/** The reference dump. */
	private static final String REFERENCES = System.getProperty("AR_CLAP2_REFERENCES", System.getenv("AR_CLAP2_REFERENCES"));

	/** The extracted SAME-S autoencoder weights. */
	private static final String SAME_WEIGHTS = System.getProperty("AR_SAME_WEIGHTS", System.getenv("AR_SAME_WEIGHTS"));

	/** The token positions the text tower is compiled for (every probe caption is shorter). */
	private static final int TEXT_LENGTH = 32;

	/**
	 * Required cosine similarity of a native audio embedding with the reference: the parity
	 * {@code engine/ml/docs/audio-tagging-feasibility.md} reports for every checkpoint.
	 */
	private static final double AUDIO_PARITY = 0.99999;

	/** Required cosine similarity of a native text embedding with the reference. */
	private static final double TEXT_PARITY = 0.999999;

	/**
	 * Permitted largest difference of the native log-mel spectrogram, in decibels. Both front
	 * ends compute in single precision with different FFT algorithms; the largest differences
	 * sit in quiet bins, where a small absolute power error is a visible fraction of the power.
	 */
	private static final double MEL_TOLERANCE_DB = 0.25;

	/** Permitted mean difference of the native log-mel spectrogram, in decibels. */
	private static final double MEL_MEAN_TOLERANCE_DB = 0.01;

	/** Only bins this loud are compared; below it single-precision power rounds visibly in dB. */
	private static final double MEL_FLOOR_DB = -80.0;

	/** Number of timed encodes per clip after the warm-up. */
	private static final int TIMED_RUNS = 3;

	/** Number of top-scoring tags a clip is judged by. */
	private static final int TOP = 3;

	/** One probe clip of the manifest. */
	private static final class Clip {
		/** The clip's name. */
		final String name;
		/** One-shot, loop or stem. */
		final String kind;
		/** The labels of the tags that describe the clip. */
		final Set<String> tags;

		/**
		 * Creates a clip.
		 *
		 * @param name the name
		 * @param kind the kind
		 * @param tags the describing tags
		 */
		Clip(String name, String kind, Set<String> tags) {
			this.name = name;
			this.kind = kind;
			this.tags = tags;
		}
	}

	/** The results-file listener {@link #prepare} added for the current test, or null. */
	private Consumer<String> results;

	/**
	 * Requires the CLAP2 weights and reference dump and directs the log to a results file. The
	 * listener stays attached only for the current test; {@link #detachResults} removes it.
	 *
	 * @param name the results file's name
	 */
	private void prepare(String name) {
		requireLocalResource("CLAP2 weights", "AR_CLAP2_WEIGHTS", WEIGHTS != null && new File(WEIGHTS).isDirectory());
		requireLocalResource("CLAP2 reference dump", "AR_CLAP2_REFERENCES",
				REFERENCES != null && new File(REFERENCES, "manifest.json").isFile());
		new File("results").mkdirs();
		results = OutputFeatures.fileOutput("results/clap2_" + name + ".out");
		Console.root().addListener(results);
	}

	/** Detaches the results-file listener of the test that just ran, so later tests do not write to it. */
	@After
	public void detachResults() {
		if (results != null) {
			Console.root().removeListener(results);
			results = null;
		}
	}

	/**
	 * Reads the manifest of the reference dump.
	 *
	 * @return the manifest
	 * @throws IOException if it cannot be read
	 */
	private JsonObject manifest() throws IOException {
		String text = new String(Files.readAllBytes(new File(REFERENCES, "manifest.json").toPath()),
				StandardCharsets.UTF_8);
		return JsonParser.parseString(text).getAsJsonObject();
	}

	/**
	 * The clips of the manifest, in order.
	 *
	 * @param manifest the manifest
	 * @return the clips
	 */
	private List<Clip> clips(JsonObject manifest) {
		List<Clip> clips = new ArrayList<>();
		for (JsonElement element : manifest.getAsJsonArray("clips")) {
			JsonObject clip = element.getAsJsonObject();
			Set<String> tags = new HashSet<>();
			for (JsonElement tag : clip.getAsJsonArray("tags")) {
				tags.add(tag.getAsString());
			}
			clips.add(new Clip(clip.get("name").getAsString(), clip.get("kind").getAsString(), tags));
		}
		return clips;
	}

	/**
	 * The labels of the manifest's tags, in order.
	 *
	 * @param manifest the manifest
	 * @return the labels
	 */
	private List<String> labels(JsonObject manifest) {
		List<String> labels = new ArrayList<>();
		JsonArray tags = manifest.getAsJsonArray("tags");
		for (JsonElement tag : tags) {
			labels.add(tag.getAsJsonObject().get("label").getAsString());
		}
		return labels;
	}

	/**
	 * The token ids of a reference caption.
	 *
	 * @param references the reference dump
	 * @param index      the caption's index
	 * @return the ids
	 */
	private PackedCollection tokens(StateDictionary references, int index) {
		return references.require("tokens." + index);
	}

	/**
	 * The device memory currently allocated by every accelerator memory provider, in bytes.
	 *
	 * <p>Only the Metal and OpenCL providers account for their allocations, so on any other
	 * backend the measurement cannot be taken and this fails with that reason rather than reporting
	 * zero bytes and a misleading assertion failure.</p>
	 *
	 * @return the allocated bytes
	 * @throws AssertionError if no memory provider of the current backend accounts for its allocations
	 */
	private long allocatedDeviceBytes() {
		long total = 0;
		boolean accounted = false;
		for (MemoryProvider<? extends Memory> provider :
				Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (provider instanceof MetalMemoryProvider) {
				total += ((MetalMemoryProvider) provider).getAllocatedMemory();
				accounted = true;
			} else if (provider instanceof CLMemoryProvider) {
				total += ((CLMemoryProvider) provider).getAllocatedMemory();
				accounted = true;
			}
		}
		if (!accounted) {
			throw new AssertionError("Device memory can only be measured on the Metal or OpenCL backend");
		}
		return total;
	}

	/**
	 * The number of bytes the tensors of a dictionary under a prefix hold in single precision.
	 *
	 * @param weights the dictionary
	 * @param prefix  the key prefix
	 * @return the bytes
	 */
	private long weightBytes(StateDictionary weights, String prefix) {
		long total = 0;
		for (String key : weights.keySet()) {
			if (key.startsWith(prefix)) {
				total += 4L * weights.get(key).getShape().getTotalSize();
			}
		}
		return total;
	}

	/**
	 * The native log-mel spectrogram of the first probe clip matches the reference front end.
	 *
	 * @throws IOException if the dump cannot be read
	 */
	@Test(timeout = 1200000)
	public void frontEndMatchesReference() throws IOException {
		prepare("front_end");
		String name = clips(manifest()).get(0).name;
		try (StateDictionary references = new StateDictionary(REFERENCES)) {
			PackedCollection waveform = references.require("clip." + name);
			PackedCollection expected = references.require("mel." + name);

			PackedCollection mel = logMelSpectrogram(Clap2Config.FFT_SIZE, Clap2Config.HOP_SIZE,
					Clap2Config.SAMPLE_RATE, Clap2Config.MEL_BANDS, Clap2Config.MEL_MIN_HZ,
					Clap2Config.MEL_MAX_HZ, cp(waveform)).evaluate();
			assertEquals(expected.getShape().getTotalSize(), mel.getShape().getTotalSize());

			double largest = 0;
			double sum = 0;
			int compared = 0;
			for (int i = 0; i < expected.getShape().getTotalSize(); i++) {
				if (expected.toDouble(i) > MEL_FLOOR_DB) {
					double difference = Math.abs(expected.toDouble(i) - mel.toDouble(i));
					largest = Math.max(largest, difference);
					sum += difference;
					compared++;
				}
			}
			mel.destroy();
			log("clip=" + name + " melBins=" + expected.getShape() + " compared=" + compared
					+ " maxAbsDb=" + largest + " meanAbsDb=" + sum / Math.max(compared, 1));
			assertTrue("log-mel differs by " + largest + " dB", largest < MEL_TOLERANCE_DB);
			assertTrue("log-mel differs by " + sum / compared + " dB on average", sum / compared < MEL_MEAN_TOLERANCE_DB);
		}
	}

	/**
	 * Both native towers reproduce the reference embeddings of every probe clip and caption, and
	 * the native scores tag the probe set; the accuracy and per-clip inference cost are logged.
	 *
	 * @throws IOException if the weights or the dump cannot be read
	 */
	@Test(timeout = 2400000)
	public void towersMatchReferenceAndTagTheProbeSet() throws IOException {
		prepare("tagging_probe");
		JsonObject manifest = manifest();
		List<Clip> clips = clips(manifest);
		List<String> labels = labels(manifest);
		try (StateDictionary references = new StateDictionary(REFERENCES);
			 Clap2 model = new Clap2(WEIGHTS, TEXT_LENGTH)) {
			int width = model.getConfig().getEmbedDim();
			log("checkpoint=" + manifest.get("repo").getAsString() + " audioWidth=" + model.getConfig().getAudioWidth()
					+ " textWidth=" + model.getConfig().getTextWidth() + " embedDim=" + width);

			CollectionProducer[] tagRows = new CollectionProducer[labels.size()];
			double worstText = 1.0;
			for (int t = 0; t < labels.size(); t++) {
				PackedCollection embedding = model.getTextEncoder().encode(tokens(references, t)).clone();
				double parity = Clap2WeightFixture.cosine(embedding, references.require("text." + t));
				worstText = Math.min(worstText, parity);
				tagRows[t] = cp(embedding).reshape(shape(1, width));
				log("tag=" + labels.get(t) + " textParity=" + parity);
			}
			PackedCollection tagEmbeddings = concat(0, tagRows).evaluate();

			double[][] scores = new double[clips.size()][];
			double worstAudio = 1.0;
			for (int c = 0; c < clips.size(); c++) {
				Clip clip = clips.get(c);
				PackedCollection waveform = references.require("clip." + clip.name);
				PackedCollection embedding = model.getAudioEncoder().encode(waveform).clone();

				long start = System.nanoTime();
				for (int r = 0; r < TIMED_RUNS; r++) {
					model.getAudioEncoder().encode(waveform);
				}
				double millis = (System.nanoTime() - start) / 1e6 / TIMED_RUNS;

				double parity = Clap2WeightFixture.cosine(embedding, references.require("audio." + clip.name));
				worstAudio = Math.min(worstAudio, parity);
				PackedCollection logits = model.logits(cp(embedding), cp(tagEmbeddings)).evaluate();
				scores[c] = logits.toArray(0, labels.size());
				log("clip=" + clip.name + " kind=" + clip.kind + " seconds="
						+ waveform.getShape().getTotalSize() / (double) Clap2Config.SAMPLE_RATE
						+ " audioParity=" + parity + " warmMillis=" + millis
						+ " top=" + Arrays.toString(topTags(scores[c], labels)));
			}

			log("worstAudioParity=" + worstAudio + " worstTextParity=" + worstText);
			reportAccuracy("raw", clips, labels, scores);
			reportAccuracy("meanCentered", clips, labels, meanCentered(scores));

			assertTrue("worst audio parity " + worstAudio, worstAudio >= AUDIO_PARITY);
			assertTrue("worst text parity " + worstText, worstText >= TEXT_PARITY);
		}
	}

	/**
	 * The warm per-clip inference cost of the audio tower for clips of 1, 4, 10 and 30 seconds
	 * (the drum loop of the probe set, repeated), with the compile time of each length. The tower
	 * attends over every patch, so its cost grows faster than linearly with the clip length.
	 *
	 * @throws IOException if the weights or the dump cannot be read
	 */
	@Test(timeout = 2400000)
	public void inferenceCostByClipLength() throws IOException {
		prepare("inference_cost");
		try (StateDictionary references = new StateDictionary(REFERENCES)) {
			PackedCollection loop = references.require("clip.drum_loop");
			int loopSamples = loop.getShape().getTotalSize();

			for (double seconds : new double[] {1, 4, 10, 30}) {
				int samples = (int) (seconds * Clap2Config.SAMPLE_RATE);
				int repeats = (samples + loopSamples - 1) / loopSamples;
				PackedCollection waveform = cp(loop).repeat(repeats).reshape(shape(repeats * loopSamples))
						.subset(shape(samples), 0).evaluate();

				// TODO(review): this now fails the timing measurement on backends without memory accounting; read memory only where measurable
				long before = allocatedDeviceBytes();
				try (Clap2 model = new Clap2(WEIGHTS, TEXT_LENGTH)) {
					long compileStart = System.nanoTime();
					PackedCollection first = model.getAudioEncoder().encode(waveform);
					double firstMillis = (System.nanoTime() - compileStart) / 1e6;

					long start = System.nanoTime();
					for (int r = 0; r < TIMED_RUNS; r++) {
						model.getAudioEncoder().encode(waveform);
					}
					double millis = (System.nanoTime() - start) / 1e6 / TIMED_RUNS;
					log("seconds=" + seconds + " patches=" + (1 + Clap2Config.FREQUENCY_PATCHES
							* Clap2Config.timePatches(samples)) + " firstCallMillis=" + firstMillis
							+ " warmMillis=" + millis + " audioTowerBytes=" + (allocatedDeviceBytes() - before));
					assertTrue("embedding must be finite", Double.isFinite(first.toDouble(0)));
				} finally {
					waveform.destroy();
				}
			}
		}
	}

	/**
	 * Device memory of the two CLAP2 towers beside a loaded, compiled and once-run SAME-S
	 * encoder: the allocated bytes are logged after SAME-S, after the CLAP2 weights are bound
	 * and compiled, and after one scoring pass. The readings are differences of allocated bytes, so
	 * the test is run in its own JVM: memory left behind by an earlier test in the same JVM (one
	 * that ran out of memory partway through a compile, for example) distorts them.
	 *
	 * @throws IOException if a weight directory cannot be read
	 */
	@Test(timeout = 2400000)
	public void deviceMemoryBesideSameEncoder() throws IOException {
		prepare("device_memory");
		requireLocalResource("SAME-S weights", "AR_SAME_WEIGHTS", SAME_WEIGHTS != null && new File(SAME_WEIGHTS).isDirectory());
		JsonObject manifest = manifest();
		StateDictionary references = new StateDictionary(REFERENCES);
		StateDictionary sameWeights = null;
		CompiledModel sameEncoder = null;
		Clap2 model = null;
		try {
			PackedCollection waveform = null;
			for (Clip clip : clips(manifest)) {
				PackedCollection candidate = references.require("clip." + clip.name);
				if (waveform == null || candidate.getShape().getTotalSize() > waveform.getShape().getTotalSize()) {
					waveform = candidate;
				}
			}

			long baseline = allocatedDeviceBytes();
			sameWeights = new StateDictionary(SAME_WEIGHTS);
			SAMEAutoEncoder same = SAMEAutoEncoder.small(sameWeights);
			int sameSamples = 24576;
			Model sameModel = new Model(shape(1, 2, sameSamples));
			sameModel.add(same.encoder(1, sameSamples));
			sameEncoder = sameModel.compile(false);
			try (PackedCollection sameInput = new PackedCollection(shape(1, 2, sameSamples))) {
				sameEncoder.forward(sameInput);
			}
			long afterSame = allocatedDeviceBytes();

			StateDictionary clapWeights = new StateDictionary(WEIGHTS);
			model = new Clap2(clapWeights, TEXT_LENGTH);
			PackedCollection text = model.getTextEncoder().encode(tokens(references, 0)).clone();
			long afterText = allocatedDeviceBytes();
			PackedCollection audio = model.getAudioEncoder().encode(waveform).clone();
			long afterAudio = allocatedDeviceBytes();
			model.similarity(cp(audio), cp(text)).evaluate();
			long afterScore = allocatedDeviceBytes();

			log("checkpoint=" + manifest.get("repo").getAsString());
			log("audioWeightBytes=" + weightBytes(clapWeights, "audio.") + " textWeightBytes=" + weightBytes(clapWeights, "text."));
			log("baselineBytes=" + baseline + " sameBytes=" + (afterSame - baseline));
			log("clapTextTowerBytes=" + (afterText - afterSame) + " clapAudioTowerBytes=" + (afterAudio - afterText)
					+ " scoringBytes=" + (afterScore - afterAudio) + " clapTotalBytes=" + (afterScore - afterSame)
					+ " clipSamples=" + waveform.getShape().getTotalSize());
			assertTrue("SAME-S must occupy device memory", afterSame > baseline);
			assertTrue("the CLAP2 towers must occupy device memory", afterScore > afterSame);
		} finally {
			if (model != null) model.destroy();
			if (sameEncoder != null) sameEncoder.destroy();
			if (sameWeights != null) sameWeights.destroy();
			references.destroy();
		}
	}

	/**
	 * The labels of the highest-scoring tags of one clip, best first.
	 *
	 * @param scores the clip's score for every tag
	 * @param labels the tag labels
	 * @return the {@link #TOP} best labels with their scores
	 */
	private String[] topTags(double[] scores, List<String> labels) {
		Integer[] order = rank(scores);
		String[] top = new String[TOP];
		for (int k = 0; k < TOP; k++) {
			top[k] = labels.get(order[k]) + "=" + String.format("%.2f", scores[order[k]]);
		}
		return top;
	}

	/**
	 * The tag indices of one clip ordered by descending score.
	 *
	 * @param scores the clip's score for every tag
	 * @return the indices, best first
	 */
	private Integer[] rank(double[] scores) {
		Integer[] order = new Integer[scores.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
		return order;
	}

	/**
	 * The scores with each tag's mean over the clips subtracted, the calibration the model card
	 * recommends before taking a clip's top tags.
	 *
	 * @param scores the scores, one row per clip
	 * @return the centered scores
	 */
	private double[][] meanCentered(double[][] scores) {
		int tags = scores[0].length;
		double[] mean = new double[tags];
		for (double[] row : scores) {
			for (int t = 0; t < tags; t++) {
				mean[t] += row[t] / scores.length;
			}
		}
		double[][] centered = new double[scores.length][tags];
		for (int c = 0; c < scores.length; c++) {
			for (int t = 0; t < tags; t++) {
				centered[c][t] = scores[c][t] - mean[t];
			}
		}
		return centered;
	}

	/**
	 * Logs, overall and per clip kind, how often a clip's best tag describes it (top-1) and how
	 * often one of its {@link #TOP} best does (top-3), with chance levels for comparison.
	 *
	 * @param name   the name of this scoring, for the log
	 * @param clips  the clips
	 * @param labels the tag labels
	 * @param scores the scores, one row per clip
	 */
	private void reportAccuracy(String name, List<Clip> clips, List<String> labels, double[][] scores) {
		for (String kind : new String[] {"all", "one-shot", "loop", "stem"}) {
			int count = 0;
			int top1 = 0;
			int top3 = 0;
			for (int c = 0; c < clips.size(); c++) {
				Clip clip = clips.get(c);
				if (!kind.equals("all") && !kind.equals(clip.kind)) {
					continue;
				}
				Integer[] order = rank(scores[c]);
				count++;
				top1 += clip.tags.contains(labels.get(order[0])) ? 1 : 0;
				boolean hit = false;
				for (int k = 0; k < TOP; k++) {
					hit |= clip.tags.contains(labels.get(order[k]));
				}
				top3 += hit ? 1 : 0;
			}
			if (count > 0) {
				log("scoring=" + name + " kind=" + kind + " clips=" + count + " top1=" + top1 + "/" + count
						+ " top3=" + top3 + "/" + count + " vocabulary=" + labels.size());
			}
		}
	}
}
