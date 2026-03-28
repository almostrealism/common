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

package org.almostrealism.studio.ml.test;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.GRUCell;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MidiAutoregressiveModel;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MidiFileReader;
import org.almostrealism.ml.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.pattern.NoteDurationStrategy;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prototype demonstrating Moonbeam infilling on algorithmically generated
 * chord patterns. Generates baseline MIDI files and infilled variants.
 *
 * <p>This demo uses the test model configuration with random weights to
 * verify the end-to-end pipeline. With pretrained weights (from the
 * {@code moonbeam_309M.pt} checkpoint), the infilled output would be
 * musically meaningful.</p>
 *
 * <h2>Output Files</h2>
 * <p>Files are written to {@code ~/moonbeam-infill-samples/}:</p>
 * <ul>
 *   <li>{@code baseline-Cmaj-I-IV-V-I.mid} — original pattern</li>
 *   <li>{@code infilled-region-Cmaj-I-IV-V-I.mid} — measures 2-3 infilled</li>
 *   <li>{@code infilled-melody-Cmaj-I-IV-V-I.mid} — melody track infilled</li>
 * </ul>
 *
 * @see MidiAutoregressiveModel#generateInfill
 * @see MidiCompoundToken#fillStart()
 * @see MidiCompoundToken#fillEnd()
 */
public class MoonbeamInfillingDemo extends TestSuiteBase {

	/** BPM for all generated patterns. */
	private static final double BPM = 120.0;

	/** Beats per measure (4/4 time). */
	private static final int BEATS_PER_MEASURE = 4;

	/** Ticks per second (matches MidiTokenizer/MidiNoteEvent). */
	private static final int TIME_RESOLUTION = 100;

	/** Seconds per measure at 120 BPM, 4/4. */
	private static final double SECONDS_PER_MEASURE = (60.0 / BPM) * BEATS_PER_MEASURE;

	/** Ticks per measure. */
	private static final long TICKS_PER_MEASURE = (long) (SECONDS_PER_MEASURE * TIME_RESOLUTION);

	/** Maximum fill tokens to generate per infilling call. */
	private static final int MAX_FILL_TOKENS = 60;

	/** Output directory for generated MIDI files. */
	private static final String OUTPUT_DIR = System.getProperty("user.home") + "/moonbeam-infill-samples";

	/**
	 * Generate all infilling samples: baseline patterns and infilled variants.
	 *
	 * <p>Creates a C major I-IV-V-I chord progression over 4 measures, exports
	 * baseline MIDI, then generates infilled versions with region masking and
	 * melody masking strategies.</p>
	 */
	@Test
	public void generateInfillingSamples() throws Exception {
		File outputDir = new File(OUTPUT_DIR);
		outputDir.mkdirs();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		MoonbeamMidi model = buildTestModel(config);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.8);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(42);

		MidiTokenizer tokenizer = new MidiTokenizer();
		MidiFileReader fileWriter = new MidiFileReader();

		List<org.almostrealism.ml.midi.MidiNoteEvent> baselineEvents = generateCmajProgression();

		File baselineFile = new File(outputDir, "baseline-Cmaj-I-IV-V-I.mid");
		fileWriter.write(baselineEvents, baselineFile);
		System.err.println("Wrote baseline: " + baselineFile.getAbsolutePath()
				+ " (" + baselineEvents.size() + " notes)");

		List<MidiCompoundToken> tokens = tokenizer.tokenize(baselineEvents);
		System.err.println("Tokenized baseline: " + tokens.size() + " compound tokens");

		generateRegionInfill(model, tokenizer, fileWriter, tokens, baselineEvents,
				outputDir, "infilled-region-Cmaj-I-IV-V-I.mid");

		generateMelodyInfill(model, tokenizer, fileWriter, tokens, baselineEvents,
				outputDir, "infilled-melody-Cmaj-I-IV-V-I.mid");

		generateDenseRegionInfill(model, tokenizer, fileWriter, tokens, baselineEvents,
				outputDir, "infilled-dense-Cmaj-I-IV-V-I.mid");
	}

	/**
	 * Generate a C major I-IV-V-I chord progression spanning 4 measures.
	 *
	 * <p>Each measure contains a full triad chord plus a simple stepwise
	 * melody note for the infilling demo to work with.</p>
	 *
	 * @return list of MIDI note events for the full progression
	 */
	private List<org.almostrealism.ml.midi.MidiNoteEvent> generateCmajProgression() {
		List<org.almostrealism.ml.midi.MidiNoteEvent> events = new ArrayList<>();

		int[][] chords = {
			{60, 64, 67},  // C major (I) - C4, E4, G4
			{65, 69, 72},  // F major (IV) - F4, A4, C5
			{67, 71, 74},  // G major (V) - G4, B4, D5
			{60, 64, 67},  // C major (I) - C4, E4, G4
		};

		int[] melodyNotes = {72, 74, 76, 72}; // C5, D5, E5, C5

		for (int measure = 0; measure < 4; measure++) {
			long measureOnset = measure * TICKS_PER_MEASURE;

			for (int pitch : chords[measure]) {
				events.add(new org.almostrealism.ml.midi.MidiNoteEvent(
						pitch, measureOnset, TICKS_PER_MEASURE, 80, 0));
			}

			long beatDuration = TICKS_PER_MEASURE / BEATS_PER_MEASURE;
			for (int beat = 0; beat < BEATS_PER_MEASURE; beat++) {
				long noteOnset = measureOnset + beat * beatDuration;
				events.add(new org.almostrealism.ml.midi.MidiNoteEvent(
						melodyNotes[measure], noteOnset, beatDuration, 90, 1));
			}
		}

		events.sort(null);
		return events;
	}

	/**
	 * Region-based infilling: keep measures 1 and 4, mask measures 2-3.
	 *
	 * <p>Replaces tokens whose absolute onset falls within the masked
	 * time range with FILL_START/FILL_END delimiters, then runs the
	 * model to generate replacement tokens.</p>
	 */
	private void generateRegionInfill(MoonbeamMidi model, MidiTokenizer tokenizer,
									  MidiFileReader fileWriter, List<MidiCompoundToken> tokens,
									  List<org.almostrealism.ml.midi.MidiNoteEvent> baselineEvents,
									  File outputDir, String filename) throws Exception {
		long maskStart = TICKS_PER_MEASURE;
		long maskEnd = 3 * TICKS_PER_MEASURE;

		List<MidiCompoundToken> masked = maskTimeRegion(tokens, baselineEvents,
				maskStart, maskEnd);

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.8);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(42);

		List<MidiCompoundToken> fillTokens = autoregressive.generateInfill(masked, MAX_FILL_TOKENS);
		System.err.println("Region infill generated " + fillTokens.size() + " tokens");

		List<org.almostrealism.ml.midi.MidiNoteEvent> result = assembleInfilledEvents(
				tokens, baselineEvents, fillTokens, tokenizer, maskStart, maskEnd);

		File outputFile = new File(outputDir, filename);
		fileWriter.write(result, outputFile);
		System.err.println("Wrote region-infilled: " + outputFile.getAbsolutePath()
				+ " (" + result.size() + " notes)");
	}

	/**
	 * Melody-track infilling: keep chord notes (instrument 0), mask melody
	 * (instrument 1) across all measures.
	 */
	private void generateMelodyInfill(MoonbeamMidi model, MidiTokenizer tokenizer,
									  MidiFileReader fileWriter, List<MidiCompoundToken> tokens,
									  List<org.almostrealism.ml.midi.MidiNoteEvent> baselineEvents,
									  File outputDir, String filename) throws Exception {
		List<MidiCompoundToken> masked = maskInstrument(tokens, baselineEvents, 1);

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.8);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(123);

		List<MidiCompoundToken> fillTokens = autoregressive.generateInfill(masked, MAX_FILL_TOKENS);
		System.err.println("Melody infill generated " + fillTokens.size() + " tokens");

		List<org.almostrealism.ml.midi.MidiNoteEvent> chordEvents = new ArrayList<>();
		for (org.almostrealism.ml.midi.MidiNoteEvent event : baselineEvents) {
			if (event.getInstrument() != 1) {
				chordEvents.add(event);
			}
		}

		List<org.almostrealism.ml.midi.MidiNoteEvent> fillEvents = tokenizer.detokenize(fillTokens);
		List<org.almostrealism.ml.midi.MidiNoteEvent> result = new ArrayList<>(chordEvents);
		result.addAll(fillEvents);
		result.sort(null);

		File outputFile = new File(outputDir, filename);
		fileWriter.write(result, outputFile);
		System.err.println("Wrote melody-infilled: " + outputFile.getAbsolutePath()
				+ " (" + result.size() + " notes)");
	}

	/**
	 * Dense region infilling: shorter masked region (just measure 3),
	 * with more surrounding context.
	 */
	private void generateDenseRegionInfill(MoonbeamMidi model, MidiTokenizer tokenizer,
										   MidiFileReader fileWriter, List<MidiCompoundToken> tokens,
										   List<org.almostrealism.ml.midi.MidiNoteEvent> baselineEvents,
										   File outputDir, String filename) throws Exception {
		long maskStart = 2 * TICKS_PER_MEASURE;
		long maskEnd = 3 * TICKS_PER_MEASURE;

		List<MidiCompoundToken> masked = maskTimeRegion(tokens, baselineEvents,
				maskStart, maskEnd);

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.9);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(999);

		List<MidiCompoundToken> fillTokens = autoregressive.generateInfill(masked, MAX_FILL_TOKENS);
		System.err.println("Dense region infill generated " + fillTokens.size() + " tokens");

		List<org.almostrealism.ml.midi.MidiNoteEvent> result = assembleInfilledEvents(
				tokens, baselineEvents, fillTokens, tokenizer, maskStart, maskEnd);

		File outputFile = new File(outputDir, filename);
		fileWriter.write(result, outputFile);
		System.err.println("Wrote dense-infilled: " + outputFile.getAbsolutePath()
				+ " (" + result.size() + " notes)");
	}

	/**
	 * Mask a time region within a token sequence by replacing tokens whose
	 * onset falls within [maskStart, maskEnd) with FILL_START/FILL_END markers.
	 *
	 * <p>The returned sequence is: [SOS, context tokens before mask,
	 * FILL_START, FILL_END, context tokens after mask, EOS].</p>
	 *
	 * @param tokens         the original compound token sequence (with SOS/EOS)
	 * @param events         the original MIDI events (for absolute onset lookup)
	 * @param maskStartTicks start of masked region in absolute ticks
	 * @param maskEndTicks   end of masked region in absolute ticks
	 * @return masked token sequence with fill markers
	 */
	private List<MidiCompoundToken> maskTimeRegion(List<MidiCompoundToken> tokens,
												   List<org.almostrealism.ml.midi.MidiNoteEvent> events,
												   long maskStartTicks, long maskEndTicks) {
		List<MidiCompoundToken> result = new ArrayList<>();
		boolean inMaskedRegion = false;
		boolean fillStartAdded = false;
		int eventIdx = 0;

		for (MidiCompoundToken token : tokens) {
			if (token.isSOS() || token.isEOS()) {
				if (inMaskedRegion && !fillStartAdded) {
					result.add(MidiCompoundToken.fillStart());
					result.add(MidiCompoundToken.fillEnd());
				} else if (fillStartAdded && inMaskedRegion) {
					result.add(MidiCompoundToken.fillEnd());
					inMaskedRegion = false;
				}
				result.add(token);
				continue;
			}

			long absoluteOnset = eventIdx < events.size() ? events.get(eventIdx).getOnset() : 0;
			eventIdx++;

			if (absoluteOnset >= maskStartTicks && absoluteOnset < maskEndTicks) {
				if (!fillStartAdded) {
					result.add(MidiCompoundToken.fillStart());
					fillStartAdded = true;
					inMaskedRegion = true;
				}
			} else {
				if (inMaskedRegion) {
					result.add(MidiCompoundToken.fillEnd());
					inMaskedRegion = false;
				}
				result.add(token);
			}
		}

		return result;
	}

	/**
	 * Mask all tokens belonging to a specific instrument.
	 *
	 * <p>Tokens matching the target instrument are replaced with a single
	 * FILL_START/FILL_END region. Non-matching tokens are kept as context.</p>
	 */
	private List<MidiCompoundToken> maskInstrument(List<MidiCompoundToken> tokens,
												   List<org.almostrealism.ml.midi.MidiNoteEvent> events,
												   int targetInstrument) {
		List<MidiCompoundToken> contextTokens = new ArrayList<>();
		contextTokens.add(MidiCompoundToken.sos());

		int eventIdx = 0;
		boolean hasFilledRegion = false;

		for (MidiCompoundToken token : tokens) {
			if (token.isSpecial()) continue;

			long absoluteOnset = eventIdx < events.size() ? events.get(eventIdx).getOnset() : 0;
			int instrument = eventIdx < events.size() ? events.get(eventIdx).getInstrument() : -1;
			eventIdx++;

			if (instrument == targetInstrument) {
				if (!hasFilledRegion) {
					contextTokens.add(MidiCompoundToken.fillStart());
					contextTokens.add(MidiCompoundToken.fillEnd());
					hasFilledRegion = true;
				}
			} else {
				contextTokens.add(token);
			}
		}

		contextTokens.add(MidiCompoundToken.eos());
		return contextTokens;
	}

	/**
	 * Assemble the final event list by keeping non-masked events from the
	 * baseline and adding the infill-generated events.
	 */
	private List<org.almostrealism.ml.midi.MidiNoteEvent> assembleInfilledEvents(
			List<MidiCompoundToken> originalTokens,
			List<org.almostrealism.ml.midi.MidiNoteEvent> originalEvents,
			List<MidiCompoundToken> fillTokens,
			MidiTokenizer tokenizer,
			long maskStart, long maskEnd) {
		List<org.almostrealism.ml.midi.MidiNoteEvent> result = new ArrayList<>();

		for (org.almostrealism.ml.midi.MidiNoteEvent event : originalEvents) {
			if (event.getOnset() < maskStart || event.getOnset() >= maskEnd) {
				result.add(event);
			}
		}

		List<org.almostrealism.ml.midi.MidiNoteEvent> fillEvents = tokenizer.detokenize(fillTokens);
		for (org.almostrealism.ml.midi.MidiNoteEvent event : fillEvents) {
			result.add(new org.almostrealism.ml.midi.MidiNoteEvent(
					clampPitch(event.getPitch()),
					maskStart + event.getOnset(),
					Math.max(1, event.getDuration()),
					clampVelocity(event.getVelocity()),
					Math.max(0, Math.min(128, event.getInstrument()))));
		}

		result.sort(null);
		return result;
	}

	/**
	 * Build a MoonbeamMidi model with the test configuration and synthetic weights.
	 *
	 * <p>Uses properly shaped PackedCollections with TraversalPolicy to match
	 * the shapes expected by the transformer builder. Normalization weights are
	 * initialized to ones (identity normalization), other weights to zeros.</p>
	 */
	private MoonbeamMidi buildTestModel(MoonbeamConfig config) throws IOException {
		StateDictionary stateDict = createTestStateDictionary(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = buildTestDecoder(config);
		return new MoonbeamMidi(config, stateDict, embedding, decoder);
	}

	/**
	 * Create a StateDictionary with synthetic weights matching the test config.
	 * Follows the same pattern as MoonbeamMidiTest.createSyntheticWeights().
	 */
	private StateDictionary createTestStateDictionary(MoonbeamConfig config) {
		java.util.Map<String, org.almostrealism.collect.PackedCollection> weights =
				new java.util.HashMap<>();
		int dim = config.hiddenSize;
		int kvDim = dim * config.numKvHeads / config.numHeads;
		int ffnDim = config.intermediateSize;

		for (int i = 0; i < config.numLayers; i++) {
			String prefix = String.format("model.layers.%d", i);
			weights.put(prefix + ".input_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".post_attention_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".self_attn.q_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(dim, dim)));
			weights.put(prefix + ".self_attn.k_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(kvDim, dim)));
			weights.put(prefix + ".self_attn.v_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(kvDim, dim)));
			weights.put(prefix + ".self_attn.o_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(dim, dim)));
			weights.put(prefix + ".mlp.gate_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(ffnDim, dim)));
			weights.put(prefix + ".mlp.down_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(dim, ffnDim)));
			weights.put(prefix + ".mlp.up_proj.weight",
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(ffnDim, dim)));
		}

		weights.put("model.norm.weight", onesCollection(dim));
		return new StateDictionary(weights);
	}

	/**
	 * Build a GRU decoder with synthetic weights for the test configuration.
	 * Follows the same pattern as MoonbeamMidiTest.createSyntheticDecoder().
	 */
	private GRUDecoder buildTestDecoder(MoonbeamConfig config) {
		int hidden = config.hiddenSize;
		int decoderHidden = config.decoderHiddenSize;
		int vocabSize = config.decodeVocabSize;

		GRUCell[] layers = new GRUCell[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUCell(
					decoderHidden, decoderHidden,
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(3 * decoderHidden, decoderHidden)),
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(3 * decoderHidden, decoderHidden)),
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(3 * decoderHidden)),
					new org.almostrealism.collect.PackedCollection(
							new io.almostrealism.collect.TraversalPolicy(3 * decoderHidden)));
		}

		return new GRUDecoder(config, layers,
				new org.almostrealism.collect.PackedCollection(
						new io.almostrealism.collect.TraversalPolicy(decoderHidden, hidden)),
				new org.almostrealism.collect.PackedCollection(
						new io.almostrealism.collect.TraversalPolicy(decoderHidden)),
				new org.almostrealism.collect.PackedCollection(
						new io.almostrealism.collect.TraversalPolicy(vocabSize, decoderHidden)),
				new org.almostrealism.collect.PackedCollection(
						new io.almostrealism.collect.TraversalPolicy(vocabSize)),
				new org.almostrealism.collect.PackedCollection(
						new io.almostrealism.collect.TraversalPolicy(vocabSize, decoderHidden)));
	}

	/**
	 * Create a PackedCollection initialized to ones (for normalization weights).
	 */
	private static org.almostrealism.collect.PackedCollection onesCollection(int size) {
		org.almostrealism.collect.PackedCollection collection =
				new org.almostrealism.collect.PackedCollection(
						new io.almostrealism.collect.TraversalPolicy(size));
		double[] data = new double[size];
		java.util.Arrays.fill(data, 1.0);
		collection.setMem(0, data, 0, size);
		return collection;
	}

	/**
	 * Create an AudioSceneContext for pattern generation.
	 */
	private AudioSceneContext createContext(int measures, Scale<?> scale) {
		double secondsPerBeat = 60.0 / BPM;
		double secondsPerMeasure = secondsPerBeat * BEATS_PER_MEASURE;
		double framesPerMeasure = secondsPerMeasure * OutputLine.sampleRate;

		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames((int) (measures * framesPerMeasure));
		context.setFrameForPosition(pos -> (int) (pos * framesPerMeasure));
		context.setTimeForDuration(dur -> dur * secondsPerMeasure);
		context.setScaleForPosition(pos -> scale);

		return context;
	}

	/** Clamp pitch to valid MIDI range. */
	private static int clampPitch(int pitch) {
		return Math.max(0, Math.min(127, pitch));
	}

	/** Clamp velocity to valid MIDI range. */
	private static int clampVelocity(int velocity) {
		return Math.max(1, Math.min(127, velocity));
	}
}
