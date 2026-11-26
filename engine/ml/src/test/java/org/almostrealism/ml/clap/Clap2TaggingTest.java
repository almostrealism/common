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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves the native CLAP2 tagging path without real weights: the {@link Clap2Tokenizer} reproduces
 * the reference tokenizer's ids exactly, a {@link TagVocabulary} embeds each tag as the normalized
 * mean of its captions, {@link Clap2AudioEncoder#encodeClip} embeds a padded short clip exactly as
 * the clip alone and a long clip as the mean of its windows, and {@link Clap2#score} composes them
 * into finite, deterministic, content-dependent scores.
 *
 * <p>The tokenizer parity reads {@code /clap2/r50k_probe_ids.json}, the reference ids of a set of
 * captions written by {@code engine/ml/scripts/export_clap2_tokenizer.py} with the reference
 * tokenizer, and {@code /clap2/r50k_probe.tiktoken}, the part of the {@code r50k_base} rank file
 * that encodes those captions exactly as the whole file does.</p>
 */
public class Clap2TaggingTest extends TestSuiteBase {

	/** The token positions the text tower is compiled for. */
	private static final int TEXT_LENGTH = 16;

	/** The fixed window of the audio tower: half a second, 51 frames, 4 time patches. */
	private static final int WINDOW = Clap2Config.SAMPLE_RATE / 2;

	/**
	 * The path of a test resource.
	 *
	 * @param name the resource name
	 * @return the path
	 */
	private static Path resource(String name) {
		try {
			return Path.of(Clap2TaggingTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The tokenizer over the probe rank file.
	 *
	 * @return the tokenizer
	 * @throws IOException if the resource cannot be read
	 */
	private static Clap2Tokenizer probeTokenizer() throws IOException {
		return new Clap2Tokenizer(resource("/clap2/r50k_probe.tiktoken"));
	}

	/**
	 * A test clip: a sum of two sines at the given frequencies.
	 *
	 * @param samples the number of samples
	 * @param first   the first frequency, in Hz
	 * @param second  the second frequency, in Hz
	 * @return the waveform
	 */
	private PackedCollection clip(int samples, double first, double second) {
		CollectionProducer time = integers(0, samples).divide(c((double) Clap2Config.SAMPLE_RATE));
		return sin(time.multiply(c(2 * Math.PI * first))).multiply(c(0.5))
				.add(sin(time.multiply(c(2 * Math.PI * second))).multiply(c(0.25))).evaluate();
	}

	/**
	 * A copy of a range of a waveform.
	 *
	 * @param waveform the waveform
	 * @param offset   the first sample
	 * @param length   the number of samples
	 * @return the copy
	 */
	private PackedCollection piece(PackedCollection waveform, int offset, int length) {
		PackedCollection piece = new PackedCollection(shape(length));
		piece.setFrom(0, waveform, offset, length);
		return piece;
	}

	/**
	 * A copy of an embedding scaled to unit length, computed on the host.
	 *
	 * @param embedding the embedding
	 * @return its values divided by its norm
	 */
	private static double[] unit(PackedCollection embedding) {
		double[] values = embedding.toArray(0, embedding.getShape().getTotalSize());
		double norm = 0;
		for (double v : values) norm += v * v;
		norm = Math.sqrt(norm);
		for (int i = 0; i < values.length; i++) values[i] /= norm;
		return values;
	}

	/**
	 * The normalized mean of the normalized embeddings, computed on the host.
	 *
	 * @param embeddings the embeddings
	 * @return the unit-length mean direction
	 */
	private static double[] meanDirection(List<PackedCollection> embeddings) {
		double[] sum = null;
		for (PackedCollection embedding : embeddings) {
			double[] u = unit(embedding);
			if (sum == null) sum = new double[u.length];
			for (int i = 0; i < u.length; i++) sum[i] += u[i];
		}
		double norm = 0;
		for (double v : sum) norm += v * v;
		norm = Math.sqrt(norm);
		for (int i = 0; i < sum.length; i++) sum[i] /= norm;
		return sum;
	}

	/**
	 * Asserts that a collection holds the given unit vector, value by value.
	 *
	 * @param label    the value's name, for the message
	 * @param expected the expected values
	 * @param actual   the collection, read from {@code offset}
	 * @param offset   the first value of the collection to compare
	 */
	private void assertVector(String label, double[] expected, PackedCollection actual, int offset) {
		for (int i = 0; i < expected.length; i++) {
			assertEquals(label + "[" + i + "]", expected[i], actual.toDouble(offset + i), 1e-4);
		}
	}

	/**
	 * Reads the reference ids of the probe captions.
	 *
	 * @return the captions, each an object with its {@code text} and {@code ids}
	 * @throws IOException if the resource cannot be read
	 */
	private static JsonArray referenceCaptions() throws IOException {
		try (InputStream in = Clap2TaggingTest.class.getResourceAsStream("/clap2/r50k_probe_ids.json")) {
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			return root.getAsJsonArray("captions");
		}
	}

	/**
	 * The native tokenizer reproduces, exactly, the ids the reference tokenizer gives every probe
	 * caption, BOS and EOS included, and decodes them back to the caption. The probe covers
	 * contractions in either case, digit runs, accented and four-byte characters, runs of spaces,
	 * tabs and newlines, punctuation runs and text that spells a special token.
	 *
	 * @throws IOException if a resource cannot be read
	 */
	@Test(timeout = 60000)
	public void tokenizerReproducesReferenceIds() throws IOException {
		Clap2Tokenizer tokenizer = probeTokenizer();
		JsonArray captions = referenceCaptions();
		assertTrue("the probe must hold captions", captions.size() > 20);

		for (JsonElement element : captions) {
			JsonObject caption = element.getAsJsonObject();
			String text = caption.get("text").getAsString();
			JsonArray ids = caption.getAsJsonArray("ids");
			int[] expected = new int[ids.size()];
			for (int i = 0; i < expected.length; i++) expected[i] = ids.get(i).getAsInt();

			int[] actual = tokenizer.encode(text, true);
			Assert.assertArrayEquals("ids of \"" + text + "\"", expected, actual);
			Assert.assertEquals("decoded \"" + text + "\"", text, tokenizer.decode(actual));
		}
	}

	/**
	 * A rank file that repeats a rank, or whose ranks reach the text tower's control tokens, is
	 * rejected rather than loaded into a vocabulary whose ids the tower would misread.
	 *
	 * @throws IOException if a temporary file cannot be written
	 */
	@Test(timeout = 60000)
	public void malformedRankFileIsRejected() throws IOException {
		assertRankFileRejected("assigned twice", "YQ== 0\nYg== 0\n");
		assertRankFileRejected("appears twice", "YQ== 0\nYQ== 1\n");
		assertRankFileRejected("control tokens", "YQ== 0\nYg== " + Clap2Config.EOS_TOKEN + "\n");
		assertRankFileRejected("not a token and a rank", "YQ==\n");
		assertRankFileRejected("malformed", "!!!! 0\n");
	}

	/**
	 * Asserts that reading the given rank file fails with a message containing {@code expected}.
	 *
	 * @param expected text the exception's message must contain
	 * @param content  the rank file
	 * @throws IOException if the temporary file cannot be written
	 */
	private void assertRankFileRejected(String expected, String content) throws IOException {
		File file = File.createTempFile("ranks", ".tiktoken");
		try {
			Files.writeString(file.toPath(), content, StandardCharsets.US_ASCII);
			new Clap2Tokenizer(file.toPath());
			Assert.fail("Expected the rank file to be rejected with \"" + expected + "\"");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(expected));
		} finally {
			file.delete();
		}
	}

	/**
	 * A caption given as token ids embeds the same whether the ids arrive as a collection (the
	 * gather path) or as an array (written one id at a time), and an id outside the vocabulary is
	 * rejected before any kernel reads it.
	 */
	@Test(timeout = 600000)
	public void captionIdsEmbedTheSameOnEitherPath() {
		StateDictionary weights = new Clap2WeightFixture().weights(17);
		Clap2TextEncoder text = new Clap2TextEncoder(Clap2Config.of(weights, TEXT_LENGTH), weights);
		try {
			int[] ids = {50259, 32, 2769, 4829, 13026, 7127, 13, 50257};
			PackedCollection fromArray = text.encode(ids).clone();
			PackedCollection fromCollection = text.encode(pack(50259, 32, 2769, 4829, 13026, 7127, 13, 50257)).clone();
			PackedCollection shorter = text.encode(new int[] {50259, 32, 50257}).clone();
			PackedCollection again = text.encode(ids).clone();

			assertVector("array path", fromCollection.toArray(0, Clap2WeightFixture.EMBED), fromArray, 0);
			assertVector("repeated caption", fromArray.toArray(0, Clap2WeightFixture.EMBED), again, 0);
			assertTrue("Different captions must embed differently",
					Clap2WeightFixture.cosine(fromArray, shorter) < 0.9999);

			try {
				text.encode(new int[] {50259, Clap2WeightFixture.VOCABULARY, 50257});
				Assert.fail("An id outside the vocabulary must be rejected");
			} catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("outside the vocabulary"));
			}
		} finally {
			text.destroy();
			weights.destroy();
		}
	}

	/**
	 * The front end of a clip held at the start of a longer buffer lays the clip's patches out on
	 * the buffer's grid exactly as the clip alone lays them out on its own: patch {@code (f, t)} of
	 * every time column the clip covers holds the same values, whatever follows the clip.
	 */
	@Test(timeout = 300000)
	public void bufferedClipPatchesMatchTheClipAlone() {
		StateDictionary weights = new Clap2WeightFixture().weights(41);
		Clap2AudioEncoder audio = new Clap2AudioEncoder(Clap2Config.of(weights, TEXT_LENGTH, WINDOW), weights);
		try {
			int samples = WINDOW / 3;
			PackedCollection waveform = clip(samples, 330, 2700);
			PackedCollection buffer = clip(WINDOW, 1234, 77);
			buffer.setFrom(0, waveform, 0, samples);

			PackedCollection alone = audio.patches(cp(waveform)).evaluate();
			PackedCollection buffered = audio.patches(cp(buffer), c((double) samples)).evaluate();

			int width = Clap2Config.PATCH_SIZE + 1;
			int clipColumns = Clap2Config.timePatches(samples);
			int bufferColumns = Clap2Config.timePatches(WINDOW);
			for (int f = 0; f < Clap2Config.FREQUENCY_PATCHES; f++) {
				for (int t = 0; t < clipColumns; t++) {
					int a = (1 + f * clipColumns + t) * width;
					int b = (1 + f * bufferColumns + t) * width;
					for (int j = 0; j < width; j++) {
						assertEquals("patch (" + f + ", " + t + ") value " + j,
								alone.toDouble(a + j), buffered.toDouble(b + j), 1e-3);
					}
				}
			}
		} finally {
			audio.destroy();
			weights.destroy();
		}
	}

	/**
	 * The masked tower ignores the patches its masks exclude: run on a clip's patches laid out on a
	 * wider grid, with the columns beyond the clip masked, it embeds the clip as the tower of the
	 * clip's own grid does.
	 */
	@Test(timeout = 600000)
	public void maskedTowerIgnoresExcludedPatches() {
		StateDictionary weights = new Clap2WeightFixture().weights(43);
		Clap2AudioEncoder audio = new Clap2AudioEncoder(Clap2Config.of(weights, TEXT_LENGTH, WINDOW), weights);
		try {
			int samples = WINDOW / 3;
			PackedCollection waveform = clip(samples, 330, 2700);
			PackedCollection buffer = clip(WINDOW, 1234, 77);
			buffer.setFrom(0, waveform, 0, samples);
			int clipColumns = Clap2Config.timePatches(samples);
			int bufferColumns = Clap2Config.timePatches(WINDOW);

			PackedCollection aloneRows = audio.patches(cp(waveform)).evaluate();
			PackedCollection bufferRows = audio.patches(cp(buffer), c((double) samples)).evaluate();
			PackedCollection keys = audio.keyMask(bufferColumns, c((double) clipColumns)).evaluate();
			PackedCollection pool = audio.poolMask(bufferColumns, c((double) clipColumns)).evaluate();
			assertEquals(1.0, keys.toDouble(0), 0.0);
			assertEquals(0.0, pool.toDouble(0), 0.0);
			assertEquals(1.0, keys.toDouble(clipColumns), 0.0);
			assertEquals(0.0, keys.toDouble(clipColumns + 1), 0.0);
			assertEquals(1.0, keys.toDouble(bufferColumns + 1), 0.0);

			PackedCollection expected = run(audio.block(clipColumns), aloneRows);
			PackedCollection masked = run(audio.block(bufferColumns, cp(keys), cp(pool)), bufferRows);
			assertVector("masked tower", expected.toArray(0, Clap2WeightFixture.EMBED), masked, 0);
		} finally {
			audio.destroy();
			weights.destroy();
		}
	}

	/**
	 * Compiles a block and runs it once.
	 *
	 * @param block the block
	 * @param input the input
	 * @return a copy of the output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		CompiledModel compiled = model.compile(false);
		try {
			return compiled.forward(input).clone();
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * A clip shorter than the window, padded into it with its padding masked, embeds exactly as
	 * the clip alone does at its own length, whatever the window: the padding changes neither the
	 * spectrogram of the clip nor the embedding.
	 */
	@Test(timeout = 900000)
	public void paddedShortClipEmbedsAsTheClipAlone() {
		StateDictionary weights = new Clap2WeightFixture().weights(19);
		Clap2 small = new Clap2(weights, null, TEXT_LENGTH, WINDOW);
		Clap2 large = new Clap2(weights, null, TEXT_LENGTH, 3 * WINDOW / 2);
		try {
			for (int samples : new int[] {WINDOW / 3, WINDOW / 2 + 777, WINDOW}) {
				PackedCollection waveform = clip(samples, 330, 2700);
				double[] alone = unit(small.getAudioEncoder().encode(waveform));
				PackedCollection windowed = small.getAudioEncoder().encodeClip(waveform).clone();
				PackedCollection larger = large.getAudioEncoder().encodeClip(waveform).clone();

				assertVector("windowed " + samples, alone, windowed, 0);
				assertVector("larger window " + samples, alone, larger, 0);
				assertEquals("unit length " + samples, 1.0, Clap2WeightFixture.cosine(windowed, windowed), 1e-6);
			}
		} finally {
			small.destroy();
			large.destroy();
			weights.destroy();
		}
	}

	/**
	 * A clip longer than the window embeds as the normalized mean of the normalized embeddings of
	 * its windows, the last holding the remainder, and a remainder shorter than one analysis window
	 * is dropped.
	 */
	@Test(timeout = 900000)
	public void longClipAveragesItsWindows() {
		StateDictionary weights = new Clap2WeightFixture().weights(23);
		Clap2 model = new Clap2(weights, null, TEXT_LENGTH, WINDOW);
		Clap2AudioEncoder audio = model.getAudioEncoder();
		try {
			int remainder = WINDOW / 4;
			PackedCollection waveform = clip(2 * WINDOW + remainder, 180, 4100);
			List<PackedCollection> windows = new ArrayList<>();
			windows.add(audio.encode(piece(waveform, 0, WINDOW)).clone());
			windows.add(audio.encode(piece(waveform, WINDOW, WINDOW)).clone());
			windows.add(audio.encode(piece(waveform, 2 * WINDOW, remainder)).clone());

			PackedCollection embedding = audio.encodeClip(waveform).clone();
			assertVector("three windows", meanDirection(windows), embedding, 0);
			assertTrue("the windows must differ, or the mean proves nothing",
					Clap2WeightFixture.cosine(windows.get(0), windows.get(2)) < 0.9999);

			PackedCollection withTail = clip(2 * WINDOW + Clap2Config.MIN_CLIP_SAMPLES - 1, 180, 4100);
			assertVector("short remainder dropped", meanDirection(windows.subList(0, 2)),
					audio.encodeClip(withTail), 0);

			try {
				audio.encodeClip(new PackedCollection(shape(Clap2Config.MIN_CLIP_SAMPLES - 1)));
				Assert.fail("A clip shorter than one analysis window must be rejected");
			} catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("shorter than"));
			}
		} finally {
			model.destroy();
			weights.destroy();
		}
	}

	/**
	 * The embedding of a tag is the normalized mean of its captions' normalized embeddings, each
	 * caption tokenized natively; a vocabulary is embedded once and reused, and a caption too long
	 * for the compiled text length is rejected.
	 *
	 * @throws IOException if the tokenizer resource cannot be read
	 */
	@Test(timeout = 900000)
	public void tagEmbeddingAveragesItsCaptions() throws IOException {
		StateDictionary weights = new Clap2WeightFixture().weights(29);
		Clap2Tokenizer tokenizer = probeTokenizer();
		Clap2 model = new Clap2(weights, tokenizer, TEXT_LENGTH, WINDOW);
		try {
			TagVocabulary vocabulary = new TagVocabulary("v1", List.of(
					new TagVocabulary.Tag("kick drum", "A deep kick drum hits.", "kick drum"),
					new TagVocabulary.Tag("piano", "A piano plays notes.")));

			PackedCollection kickSentence = model.getTextEncoder()
					.encode(tokenizer.encode("A deep kick drum hits.", true)).clone();
			PackedCollection kickLabel = model.getTextEncoder().encode(tokenizer.encode("kick drum", true)).clone();
			PackedCollection piano = model.getTextEncoder().encode(tokenizer.encode("A piano plays notes.", true)).clone();

			PackedCollection embeddings = model.embed(vocabulary);
			int width = Clap2WeightFixture.EMBED;
			assertEquals(2 * width, embeddings.getShape().getTotalSize());
			assertVector("kick drum", meanDirection(List.of(kickSentence, kickLabel)), embeddings, 0);
			assertVector("piano", unit(piano), embeddings, width);
			assertTrue("the captions must differ, or the mean proves nothing",
					Clap2WeightFixture.cosine(kickSentence, kickLabel) < 0.9999);
			assertTrue("an equal vocabulary must reuse the embeddings", embeddings == model.embed(
					new TagVocabulary("v1", new ArrayList<>(vocabulary.getTags()))));
			assertTrue("a new version must be embedded anew",
					embeddings != model.embed(new TagVocabulary("v2", vocabulary.getTags())));

			try {
				model.embed(TagVocabulary.of(List.of("a b c d e f g h i j k l m n o p q r s t")));
				Assert.fail("A caption longer than the compiled text length must be rejected");
			} catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("compiled for"));
			}
		} finally {
			model.destroy();
			weights.destroy();
		}
	}

	/**
	 * The full native path scores a clip against text tags: the scores are finite, are the cosine
	 * of the clip's fixed-window embedding with each tag's embedding, repeat exactly for the same
	 * clip, differ between clips, and are the same for a tag list and its single-caption
	 * vocabulary.
	 *
	 * @throws IOException if the tokenizer resource cannot be read
	 */
	@Test(timeout = 900000)
	public void scoresAreDeterministicAndContentDependent() throws IOException {
		StateDictionary weights = new Clap2WeightFixture().weights(31);
		Clap2 model = new Clap2(weights, probeTokenizer(), TEXT_LENGTH, WINDOW);
		try {
			List<String> tags = List.of("A deep kick drum hits.", "A piano plays notes.", "A bell rings.");
			PackedCollection low = clip(WINDOW + WINDOW / 3, 110, 220);
			PackedCollection high = clip(WINDOW + WINDOW / 3, 3100, 6700);

			PackedCollection first = model.score(low, tags).evaluate();
			PackedCollection repeated = model.score(low, TagVocabulary.of(tags)).evaluate();
			PackedCollection other = model.score(high, tags).evaluate();
			PackedCollection clipEmbedding = model.getAudioEncoder().encodeClip(low).clone();
			PackedCollection tagEmbeddings = model.embed(TagVocabulary.of(tags));

			assertEquals(tags.size(), first.getShape().getTotalSize());
			double difference = 0;
			for (int t = 0; t < tags.size(); t++) {
				assertTrue("score " + t + " = " + first.toDouble(t), Double.isFinite(first.toDouble(t)));
				assertEquals("repeated score " + t, first.toDouble(t), repeated.toDouble(t), 1e-6);
				double expected = Clap2WeightFixture.cosine(clipEmbedding,
						tagEmbeddings.range(shape(Clap2WeightFixture.EMBED), t * Clap2WeightFixture.EMBED));
				assertEquals("score " + t + " is the cosine", expected, first.toDouble(t), 1e-4);
				difference = Math.max(difference, Math.abs(first.toDouble(t) - other.toDouble(t)));
			}
			assertTrue("different clips must score differently", difference > 1e-3);
		} finally {
			model.destroy();
			weights.destroy();
		}
	}

	/**
	 * Text tags cannot be scored by a model loaded without a tokenizer.
	 */
	@Test(timeout = 60000)
	public void textTagsRequireATokenizer() {
		StateDictionary weights = new Clap2WeightFixture().weights(37);
		Clap2 model = new Clap2(weights, TEXT_LENGTH);
		try {
			model.embed(TagVocabulary.of(List.of("piano")));
			Assert.fail("Embedding text tags without a tokenizer must fail");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("tokenizer"));
		} finally {
			model.destroy();
			weights.destroy();
		}
	}

	/**
	 * The tag embeddings of at most {@link Clap2#TAG_EMBEDDING_CACHE_SIZE} vocabularies are kept:
	 * embedding one more releases the vocabulary used least recently, a vocabulary used again is
	 * kept in preference to older ones, and a released vocabulary embeds anew to the same values.
	 *
	 * @throws IOException if a resource cannot be read
	 */
	@Test(timeout = 900000)
	public void tagEmbeddingCacheReleasesLeastRecentlyUsed() throws IOException {
		JsonArray captions = referenceCaptions();
		int size = Clap2.TAG_EMBEDDING_CACHE_SIZE;
		List<TagVocabulary> vocabularies = new ArrayList<>();
		for (int v = 0; v <= size; v++) {
			vocabularies.add(TagVocabulary.of(List.of(
					captions.get(v).getAsJsonObject().get("text").getAsString())));
		}

		StateDictionary weights = new Clap2WeightFixture().weights(41);
		Clap2 model = new Clap2(weights, probeTokenizer(), TEXT_LENGTH, WINDOW);
		try {
			List<PackedCollection> embedded = new ArrayList<>();
			for (int v = 0; v < size; v++) {
				embedded.add(model.embed(vocabularies.get(v)));
			}
			double[] second = unit(embedded.get(1));

			assertTrue("a cached vocabulary is reused", embedded.get(0) == model.embed(vocabularies.get(0)));
			PackedCollection newest = model.embed(vocabularies.get(size));

			assertTrue("the vocabulary used again must be kept",
					embedded.get(0) == model.embed(vocabularies.get(0)));
			assertTrue("the newest vocabulary must be kept", newest == model.embed(vocabularies.get(size)));
			PackedCollection reembedded = model.embed(vocabularies.get(1));
			assertTrue("the least recently used vocabulary must be released", reembedded != embedded.get(1));
			assertVector("re-embedded", second, reembedded, 0);
		} finally {
			model.destroy();
			weights.destroy();
		}
	}

	/**
	 * A model loaded from files reads its tokenizer before its weights, so a missing rank file
	 * fails with an error naming it before any weight is loaded, even from a directory that does
	 * not exist.
	 *
	 * @throws IOException if a temporary directory cannot be created
	 */
	@Test(timeout = 60000)
	public void missingRankFileFailsBeforeWeightsLoad() throws IOException {
		Path directory = Files.createTempDirectory("clap2");
		String weightsDirectory = directory.resolve("no-weights").toString();
		String rankFile = directory.resolve("missing.tiktoken").toString();
		try {
			new Clap2(weightsDirectory, rankFile, TEXT_LENGTH).destroy();
			Assert.fail("A missing rank file must be rejected");
		} catch (IOException e) {
			assertTrue(String.valueOf(e.getMessage()), String.valueOf(e.getMessage()).contains("missing.tiktoken"));
		} finally {
			Files.delete(directory);
		}
	}

	/**
	 * A model that cannot be built over a caller's weights rejects its configuration and leaves
	 * the weights to the caller, still usable.
	 */
	@Test(timeout = 60000)
	public void failedConstructionLeavesCallerWeights() {
		StateDictionary weights = new Clap2WeightFixture().weights(43);
		try {
			new Clap2(weights, null, TEXT_LENGTH, Clap2Config.MIN_CLIP_SAMPLES - 1).destroy();
			Assert.fail("A window shorter than the minimum clip must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("windowSamples"));
		}

		Clap2 model = new Clap2(weights, null, TEXT_LENGTH, WINDOW);
		try {
			assertEquals(Clap2WeightFixture.EMBED, model.getConfig().getEmbedDim());
			assertTrue(Double.isFinite(weights.require(Clap2.LOGIT_SCALE_KEY).toDouble(0)));
		} finally {
			model.destroy();
			weights.destroy();
		}
	}

	/**
	 * A vocabulary rejects an empty tag list, a repeated label and a tag without captions, and
	 * equal vocabularies are equal.
	 */
	@Test(timeout = 60000)
	public void vocabularyValidatesItsTags() {
		assertRejected("at least one tag", () -> new TagVocabulary("v", List.of()));
		assertRejected("appears twice", () -> TagVocabulary.of(List.of("piano", "piano")));
		assertRejected("at least one caption", () -> new TagVocabulary.Tag("piano", List.of()));
		assertRejected("empty caption", () -> new TagVocabulary.Tag("piano", ""));
		assertRejected("non-empty label", () -> new TagVocabulary.Tag("", "A piano plays."));

		TagVocabulary a = TagVocabulary.of(List.of("piano", "bass"));
		Assert.assertEquals(a, TagVocabulary.of(List.of("piano", "bass")));
		assertEquals(1, a.indexOf("bass"));
		assertEquals(-1, a.indexOf("organ"));
		assertTrue(!a.equals(TagVocabulary.of(List.of("bass", "piano"))));
	}

	/**
	 * Asserts that an operation throws an {@link IllegalArgumentException} whose message contains
	 * {@code expected}.
	 *
	 * @param expected text the exception's message must contain
	 * @param create   the operation
	 */
	private void assertRejected(String expected, Runnable create) {
		try {
			create.run();
			Assert.fail("Expected an IllegalArgumentException mentioning \"" + expected + "\"");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(expected));
		}
	}
}
