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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The real-weight checks of the native CLAP2 tagging path: the native tokenizer over the full
 * {@code r50k_base} vocabulary reproduces the token ids of the reference dump, the fixed-window
 * audio path reproduces the reference audio embeddings, and scoring the musical probe set against
 * its tag captions entirely natively (tag strings tokenized here, no reference ids, no ONNX)
 * ranks the describing tags above irrelevant ones.
 *
 * <p>The weights and the reference dump are written by {@code engine/ml/scripts/dump_clap2_reference.py}
 * and the rank file by {@code engine/ml/scripts/export_clap2_tokenizer.py}; they are local resources
 * located through the {@code AR_CLAP2_WEIGHTS}, {@code AR_CLAP2_REFERENCES} and
 * {@code AR_CLAP2_TOKENIZER} system properties (or environment variables of the same names). Where
 * a resource is absent the test is reported as skipped by {@link #requireLocalResource}; where it
 * is declared but missing the test fails. The scores are written to
 * {@code results/clap2_native_tagging.out}.</p>
 */
public class Clap2NativeTaggingTest extends TestSuiteBase {

	/** The extracted CLAP2 weights. */
	private static final String WEIGHTS = System.getProperty("AR_CLAP2_WEIGHTS", System.getenv("AR_CLAP2_WEIGHTS"));

	/** The reference dump. */
	private static final String REFERENCES = System.getProperty("AR_CLAP2_REFERENCES", System.getenv("AR_CLAP2_REFERENCES"));

	/** The exported {@code r50k_base} rank file. */
	private static final String TOKENIZER = System.getProperty("AR_CLAP2_TOKENIZER", System.getenv("AR_CLAP2_TOKENIZER"));

	/** The token positions the text tower is compiled for (every probe caption is shorter). */
	private static final int TEXT_LENGTH = 32;

	/**
	 * Required cosine of a native fixed-window audio embedding with the reference: the parity the
	 * exact per-length port reaches, since every probe clip fits one window.
	 */
	private static final double AUDIO_PARITY = 0.99999;

	/** Number of top-scoring tags a clip is judged by. */
	private static final int TOP = 3;

	/**
	 * The clips whose describing tag must be among their {@link #TOP} best: the synthesized drums,
	 * which the reference model separates clearly. Single sustained pitched notes are often
	 * confused by the model itself, so they are only counted in the overall rate.
	 */
	private static final Set<String> SEPARABLE = Set.of("synth_kick", "synth_snare", "synth_hihat", "drum_loop");

	/**
	 * The fewest probe clips that must have a describing tag among their best {@link #TOP}: the
	 * reference model reaches 16 of the 27 clips of the probe set on raw cosine scores.
	 */
	private static final int MIN_TOP_HITS = 14;

	/** The results-file listener of the current test, or null. */
	private Consumer<String> results;

	/** Detaches the results-file listener of the test that just ran. */
	@After
	public void detachResults() {
		if (results != null) {
			Console.root().removeListener(results);
			results = null;
		}
	}

	/**
	 * Requires the rank file and the reference dump, and directs the log to a results file.
	 *
	 * @param name the results file's name
	 */
	private void prepare(String name) {
		requireLocalResource("CLAP2 rank file", "AR_CLAP2_TOKENIZER", TOKENIZER != null && new File(TOKENIZER).isFile());
		requireLocalResource("CLAP2 reference dump", "AR_CLAP2_REFERENCES",
				REFERENCES != null && new File(REFERENCES, "manifest.json").isFile());
		new File("results").mkdirs();
		results = OutputFeatures.fileOutput("results/clap2_" + name + ".out");
		Console.root().addListener(results);
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
	 * The native tokenizer over the full rank file gives every probe caption exactly the ids of
	 * the reference dump.
	 *
	 * @throws IOException if a resource cannot be read
	 */
	@Test(timeout = 300000)
	public void tokenizerMatchesReferenceDump() throws IOException {
		prepare("native_tokenizer");
		Clap2Tokenizer tokenizer = new Clap2Tokenizer(Path.of(TOKENIZER));
		assertEquals(Clap2Config.EOS_TOKEN - 1, tokenizer.getVocabSize());

		try (StateDictionary references = new StateDictionary(REFERENCES)) {
			int index = 0;
			for (JsonElement element : manifest().getAsJsonArray("tags")) {
				String caption = element.getAsJsonObject().get("caption").getAsString();
				PackedCollection expected = references.require("tokens." + index);
				int[] actual = tokenizer.encode(caption, true);
				int[] reference = new int[expected.getShape().getTotalSize()];
				for (int i = 0; i < reference.length; i++) reference[i] = (int) expected.toDouble(i);
				log("caption=" + caption + " ids=" + Arrays.toString(actual));
				Assert.assertArrayEquals("ids of \"" + caption + "\"", reference, actual);
				index++;
			}
			assertTrue("the dump must hold captions", index > 0);
		}
	}

	/**
	 * The task's acceptance check: every probe clip is scored against the probe tags through the
	 * full native path, {@link Clap2#score(PackedCollection, TagVocabulary)}, with the tag captions
	 * tokenized natively and the clip embedded through the fixed window. Each clip's fixed-window
	 * embedding matches the reference, every separable clip has a describing tag among its best
	 * {@link #TOP}, and at least {@link #MIN_TOP_HITS} clips do overall.
	 *
	 * @throws IOException if a resource cannot be read
	 */
	@Test(timeout = 2400000)
	public void expectedTagsRankAboveIrrelevantOnes() throws IOException {
		prepare("native_tagging");
		requireLocalResource("CLAP2 weights", "AR_CLAP2_WEIGHTS", WEIGHTS != null && new File(WEIGHTS).isDirectory());
		JsonObject manifest = manifest();

		List<TagVocabulary.Tag> tags = new ArrayList<>();
		for (JsonElement element : manifest.getAsJsonArray("tags")) {
			JsonObject tag = element.getAsJsonObject();
			tags.add(new TagVocabulary.Tag(tag.get("label").getAsString(), tag.get("caption").getAsString()));
		}
		TagVocabulary vocabulary = new TagVocabulary(manifest.get("repo").getAsString(), tags);

		try (StateDictionary references = new StateDictionary(REFERENCES);
			 Clap2 model = new Clap2(WEIGHTS, TOKENIZER, TEXT_LENGTH)) {
			int hits = 0;
			int clips = 0;
			double worstParity = 1.0;
			for (JsonElement element : manifest.getAsJsonArray("clips")) {
				JsonObject clip = element.getAsJsonObject();
				String name = clip.get("name").getAsString();
				Set<String> describing = new HashSet<>();
				clip.getAsJsonArray("tags").forEach(t -> describing.add(t.getAsString()));

				PackedCollection waveform = references.require("clip." + name);
				double[] scores = model.score(waveform, vocabulary).evaluate().toArray(0, vocabulary.size());
				double parity = Clap2WeightFixture.cosine(model.getAudioEncoder().encodeClip(waveform),
						references.require("audio." + name));
				worstParity = Math.min(worstParity, parity);

				List<String> top = topTags(scores, vocabulary);
				boolean hit = top.stream().anyMatch(describing::contains);
				hits += hit ? 1 : 0;
				clips++;
				log("clip=" + name + " audioParity=" + parity + " top=" + top + " describing=" + describing);

				assertTrue("clip " + name + " parity " + parity, parity >= AUDIO_PARITY);
				if (SEPARABLE.contains(name)) {
					assertTrue("a describing tag of " + name + " must be among its top " + TOP + ", got " + top, hit);
				}
			}

			log("clips=" + clips + " topHits=" + hits + " worstAudioParity=" + worstParity);
			assertTrue("only " + hits + " of " + clips + " clips have a describing tag in their top " + TOP,
					hits >= MIN_TOP_HITS);
		}
	}

	/**
	 * The labels of the {@link #TOP} highest-scoring tags, best first.
	 *
	 * @param scores     the score of every tag
	 * @param vocabulary the tags
	 * @return the labels
	 */
	private List<String> topTags(double[] scores, TagVocabulary vocabulary) {
		Integer[] order = new Integer[scores.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
		List<String> top = new ArrayList<>();
		for (int k = 0; k < TOP; k++) {
			top.add(vocabulary.getTags().get(order[k]).getLabel());
		}
		return top;
	}
}
