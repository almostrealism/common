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

package org.almostrealism.music.pattern;

import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedEnvelopeLayers;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedLayerEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.PatternNoteLayer;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleFunction;

/**
 * End-to-end gather test: builds a real production melodic note (three layers,
 * per-layer / filter / volume envelopes), extracts its {@link BatchedNoteInputs}
 * record, renders it with the batched kernel
 * ({@code BatchedPatternRenderer.buildBatchedSssChainPlacedFromScalars}), and
 * verifies the result against the production envelope filters applied note-by-note
 * to the same resampled sources. This proves the gather reads the real note
 * structure correctly (the point the prior attempt got wrong) and feeds the
 * kernel the scalars that reproduce the production per-note computation.
 */
public class BatchedNoteGatherTest extends TestSuiteBase implements TemporalFeatures {

	private static final int LAYERS = 3;
	private static final int SOURCE_LENGTH = 2048;
	private static final int TARGET_LENGTH = 1024;
	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final int FILTER_ORDER = 40;
	private static final double DURATION_SEC = 0.02;
	private static final double AUTOMATION_LEVEL = 0.5;

	private PackedCollection single(double value) {
		PackedCollection c = new PackedCollection(1);
		c.setMem(new double[] { value });
		return c;
	}

	private PackedCollection sample(long seed) {
		Random rng = new Random(seed);
		double[] data = new double[SOURCE_LENGTH];
		for (int i = 0; i < SOURCE_LENGTH; i++) {
			data[i] = rng.nextDouble() * 2.0 - 1.0;
		}
		PackedCollection c = new PackedCollection(SOURCE_LENGTH);
		c.setMem(data);
		return c;
	}

	@Test(timeout = 240000)
	@TestDepth(2)
	public void testGatheredNoteMatchesProductionFilters() {
		// Three distinct synthetic samples, one per layer choice.
		List<PatternNoteAudio> choices = Arrays.asList(
				new SimplePatternNote(provider(sample(1L))),
				new SimplePatternNote(provider(sample(2L))),
				new SimplePatternNote(provider(sample(3L))));
		DoubleFunction<PatternNoteAudio> audioSelection =
				d -> choices.get(Math.min(LAYERS - 1, (int) (d * LAYERS)));

		// Build the production melodic note: 3-layer inner, wrapped by filter then volume envelopes.
		PatternNoteFactory factory = new PatternNoteFactory();
		factory.setLayerEnvelopes(ParameterizedEnvelopeLayers.random(LAYERS));
		factory.setVolumeEnvelope(
				ParameterizedVolumeEnvelope.random(ParameterizedVolumeEnvelope.Mode.NOTE_LAYER));
		factory.setFilterEnvelope(
				ParameterizedFilterEnvelope.random(ParameterizedFilterEnvelope.Mode.NOTE_LAYER));

		ParameterSet params = ParameterSet.random();
		PatternNote inner = factory.apply(params, ChannelInfo.Voicing.MAIN, true, 0.1, 0.4, 0.7);
		PatternNote mid = factory.getFilterEnvelope().apply(params, ChannelInfo.Voicing.MAIN, inner);
		PatternNote outer = factory.getVolumeEnvelope().apply(params, ChannelInfo.Voicing.MAIN, mid);

		Assert.assertTrue("note should classify as melodic SSS",
				BatchedNoteInputs.isMelodicSssShape(outer));

		// ── Gather the flat record from the real note. ──
		BatchedNoteInputs in = BatchedNoteInputs.from(
				outer, null, 0, DURATION_SEC, AUTOMATION_LEVEL, audioSelection, SOURCE_LENGTH);
		Assert.assertNotNull("gather should succeed for the melodic-SSS note", in);

		// ── Batched kernel from the gathered scalars (N = 1, no placement offset). ──
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				1, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);

		PackedCollection[] sources = new PackedCollection[LAYERS];
		PackedCollection[] ratios = new PackedCollection[LAYERS];
		PackedCollection[][] layerEnvParams = new PackedCollection[LAYERS][8];
		for (int l = 0; l < LAYERS; l++) {
			sources[l] = in.getSources()[l];
			ratios[l] = single(in.getRatios()[l]);
			for (int p = 0; p < 8; p++) {
				layerEnvParams[l][p] = single(in.getLayerParams()[l][p]);
			}
		}
		PackedCollection[] filterAdsr = scalarColumns(in.getFilterAdsr());
		PackedCollection[] volumeAdsr = scalarColumns(in.getVolumeAdsr());

		PackedCollection out = renderer.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr,
				single(0.0), TARGET_LENGTH)
				.get().evaluate();

		// ── Reference: production envelope filters on the same resampled sources. ──
		PackedCollection dur = single(DURATION_SEC);
		PackedCollection auto = single(AUTOMATION_LEVEL);

		double[] merged = new double[TARGET_LENGTH];
		for (int l = 0; l < LAYERS; l++) {
			PackedCollection resampled = renderer.buildResampleProducer(in.getSources()[l], in.getRatios()[l])
					.get().evaluate();
			ParameterizedLayerEnvelope.Filter lf =
					(ParameterizedLayerEnvelope.Filter) ((PatternNoteLayer) inner.getLayers().get(l)).getAppliedFilter();
			PackedCollection enveloped = lf.apply(cp(resampled), cp(dur), cp(auto)).get().evaluate();
			for (int i = 0; i < TARGET_LENGTH; i++) {
				merged[i] += enveloped.toDouble(i);
			}
		}

		PackedCollection mergedColl = new PackedCollection(TARGET_LENGTH);
		mergedColl.setMem(merged);

		ParameterizedFilterEnvelope.Filter filtF = (ParameterizedFilterEnvelope.Filter) mid.getAppliedFilter();
		PackedCollection filtered = filtF.apply(cp(mergedColl), cp(dur), cp(auto)).get().evaluate();

		ParameterizedVolumeEnvelope.Filter volF = (ParameterizedVolumeEnvelope.Filter) outer.getAppliedFilter();
		PackedCollection voiced = volF.apply(cp(filtered), cp(dur), cp(auto)).get().evaluate();

		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < TARGET_LENGTH; i++) {
			double diff = voiced.toDouble(i) - out.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += voiced.toDouble(i) * voiced.toDouble(i);
		}
		double rms = Math.sqrt(sumSqDiff / TARGET_LENGTH);
		double refRms = Math.sqrt(sumSqRef / TARGET_LENGTH);

		log("Gathered batched note vs production filters:");
		log(String.format("  Reference RMS: %.6f", refRms));
		log(String.format("  Difference RMS: %.6f", rms));
		if (refRms > 1e-10) {
			log(String.format("  Relative difference: %.2e", rms / refRms));
		}

		Assert.assertTrue(
				"Gathered batched note differs from production filters (RMS " + rms + ")",
				rms < 1e-3);
	}

	/** Splits a per-note ADSR array into one single-element collection per parameter. */
	private PackedCollection[] scalarColumns(double[] adsr) {
		PackedCollection[] cols = new PackedCollection[adsr.length];
		for (int i = 0; i < adsr.length; i++) {
			cols[i] = single(adsr[i]);
		}
		return cols;
	}

	private NoteAudioProvider provider(PackedCollection sampleData) {
		return NoteAudioProvider.create(() -> sampleData, WesternChromatic.C1);
	}
}
