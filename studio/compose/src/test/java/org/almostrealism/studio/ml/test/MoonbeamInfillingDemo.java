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

import org.almostrealism.ml.midi.MoonbeamMidiGenerator;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MidiFileReader;
import org.almostrealism.ml.midi.MidiNoteEvent;
import org.almostrealism.ml.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Prototype demonstrating Moonbeam infilling on algorithmically generated
 * chord patterns. Generates baseline MIDI files and infilled variants.
 *
 * <p>This demo loads the pretrained 309M checkpoint from
 * {@code /Users/Shared/models/moonbeam-weights-protobuf} and uses
 * {@link MoonbeamConfig#checkpoint309M()} for the real model dimensions.</p>
 *
 * <h2>Output Files</h2>
 * <p>Files are written to {@code ~/moonbeam-infill-samples/}:</p>
 * <ul>
 *   <li>{@code baseline-Cmaj-I-IV-V-I.mid} — original pattern</li>
 *   <li>{@code infilled-region-Cmaj-I-IV-V-I.mid} — measures 2-3 infilled</li>
 *   <li>{@code infilled-melody-Cmaj-I-IV-V-I.mid} — melody track infilled</li>
 * </ul>
 *
 * @see MoonbeamMidiGenerator#generateInfill
 * @see MidiCompoundToken#fillStart()
 * @see MidiCompoundToken#fillEnd()
 */
public class MoonbeamInfillingDemo extends TestSuiteBase implements ConsoleFeatures {

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
	private static final int MAX_FILL_TOKENS = 25;

	/** Directory containing pretrained protobuf weights for the 309M checkpoint. */
	private static final String WEIGHTS_DIR = "/Users/Shared/models/moonbeam-weights-protobuf";

	/** Output directory for generated MIDI files. */
	private static final String OUTPUT_DIR = System.getProperty("user.home") + "/moonbeam-infill-samples";

	/**
	 * Generate all infilling samples: baseline patterns and infilled variants.
	 *
	 * <p>Creates a C major I-IV-V-I chord progression over 4 measures, exports
	 * baseline MIDI, then generates infilled versions with region masking and
	 * melody masking strategies.</p>
	 *
	 * <p>Requires pretrained weights at {@value WEIGHTS_DIR} and significant
	 * memory for the full 309M checkpoint. Skipped automatically in CI.</p>
	 */
	@Test
	@TestDepth(2)
	public void generateInfillingSamples() throws Exception {
		Assume.assumeTrue("Moonbeam weights not found at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());
		File outputDir = new File(OUTPUT_DIR);
		outputDir.mkdirs();

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		MoonbeamMidi model = new MoonbeamMidi(WEIGHTS_DIR, config);
		MoonbeamMidiGenerator autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.8);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(42);

		MidiTokenizer tokenizer = new MidiTokenizer();
		MidiFileReader fileWriter = new MidiFileReader();

		List<MidiNoteEvent> baselineEvents = generateCmajProgression();

		File baselineFile = new File(outputDir, "baseline-Cmaj-I-IV-V-I.mid");
		fileWriter.write(baselineEvents, baselineFile);
		log("Wrote baseline: " + baselineFile.getAbsolutePath()
				+ " (" + baselineEvents.size() + " notes)");

		List<MidiCompoundToken> tokens = tokenizer.tokenize(baselineEvents);
		log("Tokenized baseline: " + tokens.size() + " compound tokens");

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
	private List<MidiNoteEvent> generateCmajProgression() {
		List<MidiNoteEvent> events = new ArrayList<>();

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
				events.add(new MidiNoteEvent(
						pitch, measureOnset, TICKS_PER_MEASURE, 80, 0));
			}

			long beatDuration = TICKS_PER_MEASURE / BEATS_PER_MEASURE;
			for (int beat = 0; beat < BEATS_PER_MEASURE; beat++) {
				long noteOnset = measureOnset + beat * beatDuration;
				events.add(new MidiNoteEvent(
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
									  List<MidiNoteEvent> baselineEvents,
									  File outputDir, String filename) throws Exception {
		long maskStart = TICKS_PER_MEASURE;
		long maskEnd = 3 * TICKS_PER_MEASURE;

		List<MidiCompoundToken> masked = maskTimeRegion(tokens, baselineEvents,
				maskStart, maskEnd);

		MoonbeamMidiGenerator autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.8);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(42);

		List<MidiCompoundToken> fillTokens = autoregressive.generateInfill(masked, MAX_FILL_TOKENS);
		log("Region infill generated " + fillTokens.size() + " tokens");

		List<MidiNoteEvent> result = assembleInfilledEvents(
				baselineEvents, fillTokens, tokenizer, maskStart, maskEnd);

		File outputFile = new File(outputDir, filename);
		fileWriter.write(result, outputFile);
		log("Wrote region-infilled: " + outputFile.getAbsolutePath()
				+ " (" + result.size() + " notes)");
	}

	/**
	 * Melody-track infilling: keep chord notes (instrument 0), mask melody
	 * (instrument 1) across all measures.
	 */
	private void generateMelodyInfill(MoonbeamMidi model, MidiTokenizer tokenizer,
									  MidiFileReader fileWriter, List<MidiCompoundToken> tokens,
									  List<MidiNoteEvent> baselineEvents,
									  File outputDir, String filename) throws Exception {
		List<MidiCompoundToken> masked = maskInstrument(tokens, baselineEvents, 1);

		MoonbeamMidiGenerator autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.8);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(123);

		List<MidiCompoundToken> fillTokens = autoregressive.generateInfill(masked, MAX_FILL_TOKENS);
		log("Melody infill generated " + fillTokens.size() + " tokens");

		List<MidiNoteEvent> chordEvents = new ArrayList<>();
		for (MidiNoteEvent event : baselineEvents) {
			if (event.getInstrument() != 1) {
				chordEvents.add(event);
			}
		}

		List<MidiNoteEvent> fillEvents = tokenizer.detokenize(fillTokens);
		List<MidiNoteEvent> result = new ArrayList<>(chordEvents);
		result.addAll(fillEvents);
		result.sort(null);

		File outputFile = new File(outputDir, filename);
		fileWriter.write(result, outputFile);
		log("Wrote melody-infilled: " + outputFile.getAbsolutePath()
				+ " (" + result.size() + " notes)");
	}

	/**
	 * Dense region infilling: shorter masked region (just measure 3),
	 * with more surrounding context.
	 */
	private void generateDenseRegionInfill(MoonbeamMidi model, MidiTokenizer tokenizer,
										   MidiFileReader fileWriter, List<MidiCompoundToken> tokens,
										   List<MidiNoteEvent> baselineEvents,
										   File outputDir, String filename) throws Exception {
		long maskStart = 2 * TICKS_PER_MEASURE;
		long maskEnd = 3 * TICKS_PER_MEASURE;

		List<MidiCompoundToken> masked = maskTimeRegion(tokens, baselineEvents,
				maskStart, maskEnd);

		MoonbeamMidiGenerator autoregressive = model.createAutoregressiveModel();
		autoregressive.setTemperature(0.9);
		autoregressive.setTopP(0.95);
		autoregressive.setSeed(999);

		List<MidiCompoundToken> fillTokens = autoregressive.generateInfill(masked, MAX_FILL_TOKENS);
		log("Dense region infill generated " + fillTokens.size() + " tokens");

		List<MidiNoteEvent> result = assembleInfilledEvents(
				baselineEvents, fillTokens, tokenizer, maskStart, maskEnd);

		File outputFile = new File(outputDir, filename);
		fileWriter.write(result, outputFile);
		log("Wrote dense-infilled: " + outputFile.getAbsolutePath()
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
												   List<MidiNoteEvent> events,
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
												   List<MidiNoteEvent> events,
												   int targetInstrument) {
		List<MidiCompoundToken> contextTokens = new ArrayList<>();
		contextTokens.add(MidiCompoundToken.sos());

		int eventIdx = 0;
		boolean hasFilledRegion = false;

		for (MidiCompoundToken token : tokens) {
			if (token.isSpecial()) continue;

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
	private List<MidiNoteEvent> assembleInfilledEvents(
			List<MidiNoteEvent> originalEvents,
			List<MidiCompoundToken> fillTokens,
			MidiTokenizer tokenizer,
			long maskStart, long maskEnd) {
		List<MidiNoteEvent> result = new ArrayList<>();

		for (MidiNoteEvent event : originalEvents) {
			if (event.getOnset() < maskStart || event.getOnset() >= maskEnd) {
				result.add(event);
			}
		}

		List<MidiNoteEvent> fillEvents = tokenizer.detokenize(fillTokens);
		for (MidiNoteEvent event : fillEvents) {
			result.add(new MidiNoteEvent(
					clampPitch(event.getPitch()),
					maskStart + event.getOnset(),
					Math.max(1, event.getDuration()),
					clampVelocity(event.getVelocity()),
					Math.max(0, Math.min(128, event.getInstrument()))));
		}

		result.sort(null);
		return result;
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
