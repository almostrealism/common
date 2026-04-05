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

package org.almostrealism.studio.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.studio.midi.MidiFileReader;
import org.almostrealism.studio.midi.SkyTntConfig;
import org.almostrealism.studio.midi.SkyTntMidi;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.studio.midi.SkyTntTokenizerV2;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * End-to-end MIDI generation demo for the SkyTNT midi-model.
 *
 * <p>This demo generates two MIDI files and writes them to
 * {@code ~/skytnt-midi-samples/}:</p>
 * <ol>
 *   <li>{@code unconditional-001.mid} — generated from BOS with no prompt</li>
 *   <li>{@code prompted-Cmaj-001.mid} — generated from a C major chord prompt</li>
 * </ol>
 *
 * <p>The demo first checks for real model weights at
 * {@value #WEIGHTS_DIR}. If weights are absent it falls back to
 * small synthetic random weights, which prove the pipeline is correct
 * end-to-end even though the output will be musically random.</p>
 *
 * <h2>Running the demo</h2>
 * <pre>
 *   mvn test -pl engine/ml -Dtest=SkyTntGenerationDemo -DAR_TEST_PROFILE=pipeline
 * </pre>
 *
 * @see SkyTntMidi
 * @see SkyTntTokenizerV2
 * @see MidiFileReader
 */
public class SkyTntGenerationDemo extends TestSuiteBase implements ConsoleFeatures {

    /** Path checked for real pretrained weights. */
    private static final String WEIGHTS_DIR = "/Users/Shared/models/skytnt-weights-protobuf";

    /** Maximum events to generate per file. */
    private static final int MAX_NEW_EVENTS = 100;

    /** Output directory for the generated .mid files. */
    private static final String OUTPUT_DIR = System.getProperty("user.home") + "/skytnt-midi-samples";

    // -----------------------------------------------------------------------
    //  Synthetic config constants (used when real weights are absent)
    // -----------------------------------------------------------------------

    private static final int VOCAB = SkyTntTokenizerV2.VOCAB_SIZE;
    private static final int DIM = 64;
    private static final int FFN = 256;
    private static final int FFN_TOKEN = 64;
    private static final int HEADS = 4;
    private static final int HEADS_TOKEN = 2;
    private static final int SEQ_LEN = 128;
    private static final int NET_LAYERS = 1;
    private static final int NET_TOKEN_LAYERS = 1;
    private static final double EPSILON = 1e-5;

    /**
     * Generate {@code unconditional-001.mid} and {@code prompted-Cmaj-001.mid}.
     *
     * <p>The test always runs when explicitly targeted. Both files are written
     * to {@value #OUTPUT_DIR}. The method logs which weight mode was used so
     * the caller can assess the quality of the output.</p>
     */
    @Test
    public void generateMidiFiles() throws Exception {
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Cannot create output directory: " + OUTPUT_DIR);
        }

        String logFile = OUTPUT_DIR + "/generation.log";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("=== SkyTNT MIDI Generation Demo ===");
        log("Output directory: " + OUTPUT_DIR);

        boolean realWeights = new File(WEIGHTS_DIR).isDirectory();
        log("Weights mode: " + (realWeights ? "REAL (" + WEIGHTS_DIR + ")" : "SYNTHETIC (random)"));

        SkyTntMidi model = realWeights ? buildRealModel() : buildSyntheticModel();
        SkyTntTokenizerV2 tokenizer = model.getTokenizer();
        SkyTntConfig config = model.getConfig();

        log("Model loaded. vocabSize=" + config.vocabSize
                + " hiddenSize=" + config.hiddenSize
                + " netLayers=" + config.netLayers
                + " netTokenLayers=" + config.netTokenLayers);

        // ---- 1. Unconditional generation from BOS ----
        log("--- Generating unconditional MIDI from BOS ---");
        int[][] bosPrompt = buildBosPrompt(config);
        int[][] unconditional = model.generate(bosPrompt, MAX_NEW_EVENTS,
                SkyTntMidi.DEFAULT_TEMPERATURE,
                SkyTntMidi.DEFAULT_TOP_P,
                SkyTntMidi.DEFAULT_TOP_K);

        log("Generated " + (unconditional.length - 1) + " events (BOS + "
                + (unconditional.length - 1) + " generated)");

        List<MidiNoteEvent> unconditionalEvents =
                tokenizer.detokenize(
                        Arrays.copyOfRange(unconditional, 1, unconditional.length),
                        SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
        logEventSummary("unconditional", unconditionalEvents);

        File unconditionalFile = new File(outputDir, "unconditional-001.mid");
        writeMidiFile(unconditionalEvents, unconditionalFile);
        log("Written: " + unconditionalFile.getAbsolutePath());

        // ---- 2. C major chord prompt ----
        log("--- Generating MIDI from C major chord prompt ---");
        List<MidiNoteEvent> promptEvents = buildCMajorPrompt(SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
        log("Prompt contains " + promptEvents.size() + " events");

        List<MidiNoteEvent> promptedEvents = model.generateFromEvents(
                promptEvents, MAX_NEW_EVENTS,
                SkyTntMidi.DEFAULT_TEMPERATURE,
                SkyTntMidi.DEFAULT_TOP_P,
                SkyTntMidi.DEFAULT_TOP_K,
                SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
        logEventSummary("prompted-Cmaj", promptedEvents);

        // Write the full sequence: prompt + generated
        List<MidiNoteEvent> fullCmaj = new ArrayList<>(promptEvents);
        fullCmaj.addAll(promptedEvents);

        File promptedFile = new File(outputDir, "prompted-Cmaj-001.mid");
        writeMidiFile(fullCmaj, promptedFile);
        log("Written: " + promptedFile.getAbsolutePath());

        log("=== Generation complete ===");
        log("Files:");
        log("  " + unconditionalFile.getAbsolutePath() + " (" + unconditionalFile.length() + " bytes)");
        log("  " + promptedFile.getAbsolutePath() + " (" + promptedFile.length() + " bytes)");
        log("Log: " + logFile);
    }

    // -----------------------------------------------------------------------
    //  Model construction
    // -----------------------------------------------------------------------

    /**
     * Load the SkyTNT model from real pretrained weights.
     *
     * @return loaded SkyTntMidi model
     * @throws IOException if weights cannot be read
     */
    private SkyTntMidi buildRealModel() throws IOException {
        log("Loading real weights from: " + WEIGHTS_DIR);
        return new SkyTntMidi(WEIGHTS_DIR);
    }

    /**
     * Build a SkyTNT model with small random synthetic weights for testing the pipeline.
     *
     * @return SkyTntMidi model backed by random weights
     */
    private SkyTntMidi buildSyntheticModel() {
        log("Building synthetic model (random weights, dim=" + DIM + ")");
        SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
                NET_LAYERS, HEADS, FFN, SEQ_LEN,
                NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

        Random rng = new Random(42);
        StateDictionary stateDict = SkyTntMidiTest.createSyntheticWeights(config, rng);

        PdslLoader loader = new PdslLoader();
        PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
        PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

        int netHeadSize = DIM / HEADS;
        int tokenHeadSize = DIM / HEADS_TOKEN;

        PackedCollection netPos = new PackedCollection(1);
        PackedCollection tokenPos = new PackedCollection(1);
        PackedCollection netFreqCis = RotationFeatures.computeRopeFreqs(
                config.ropeTheta, netHeadSize, SEQ_LEN);
        PackedCollection tokenFreqCis = RotationFeatures.computeRopeFreqs(
                config.ropeTheta, tokenHeadSize, SEQ_LEN);
        PackedCollection lmHeadWeight = stateDict.get("lm_head");

        CompiledModel netModel = SkyTntMidi.buildTransformerModel(
                "net", stateDict, blockProgram, lmHeadProgram,
                config.netLayers, config.netHeads, netFreqCis, netPos, false, EPSILON, null);

        CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
                "net_token", stateDict, blockProgram, lmHeadProgram,
                config.netTokenLayers, config.netTokenHeads,
                tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);

        // Allocate fresh hardware-backed collections for the embedding tables.
        // StateDictionary-backed collections may lack hardware memory for
        // direct setMem copy operations at inference time.
        PackedCollection netEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
        PackedCollection tokenEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));

        return new SkyTntMidi(config, netEmbed, tokenEmbed, netModel, netTokenModel, new Random(99));
    }

    // -----------------------------------------------------------------------
    //  Prompt helpers
    // -----------------------------------------------------------------------

    /**
     * Build a BOS-only prompt row (unconditional generation seed).
     *
     * @param config model configuration
     * @return single-row token array with BOS in position 0
     */
    private static int[][] buildBosPrompt(SkyTntConfig config) {
        int[][] prompt = new int[1][config.maxTokenSeq];
        prompt[0][0] = config.bosId;
        return prompt;
    }

    /**
     * Build a C major chord prompt: C4-E4-G4 simultaneous, 1 beat each.
     *
     * @param ticksPerBeat MIDI PPQ resolution
     * @return list of note events forming a C major chord
     */
    private static List<MidiNoteEvent> buildCMajorPrompt(int ticksPerBeat) {
        List<MidiNoteEvent> events = new ArrayList<>();
        // C4=60, E4=64, G4=67, all on track 0 channel 0, velocity 80, duration 1 beat
        long duration = ticksPerBeat;
        events.add(MidiNoteEvent.note(0, 0, 0, 60, 80, duration));  // C4
        events.add(MidiNoteEvent.note(0, 0, 0, 64, 80, duration));  // E4
        events.add(MidiNoteEvent.note(0, 0, 0, 67, 80, duration));  // G4
        return events;
    }

    // -----------------------------------------------------------------------
    //  MIDI file output
    // -----------------------------------------------------------------------

    /**
     * Write a list of SkyTNT MIDI events to a {@code .mid} file.
     *
     * <p>If the event list is empty (which can happen with synthetic weights
     * that generate only non-note events), a minimal silence file is still
     * created so the output path always exists.</p>
     *
     * @param events       events to write
     * @param outputFile   destination file
     * @throws Exception if writing fails
     */
    private void writeMidiFile(List<MidiNoteEvent> events, File outputFile) throws Exception {
        MidiFileReader writer = new MidiFileReader();

        if (events.isEmpty()) {
            log("Warning: event list is empty for " + outputFile.getName()
                    + " — writing silence placeholder");
            // Write a minimal file with a single short note so the file is valid MIDI
            List<MidiNoteEvent> placeholder = new ArrayList<>();
            placeholder.add(MidiNoteEvent.note(0, 0, 0, 60, 64, SkyTntMidi.DEFAULT_TICKS_PER_BEAT));
            writer.write(placeholder, outputFile, SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
        } else {
            writer.write(events, outputFile, SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
        }
    }

    // -----------------------------------------------------------------------
    //  Logging helpers
    // -----------------------------------------------------------------------

    /**
     * Log a breakdown of event types in a generated sequence.
     *
     * @param label  label to include in the log
     * @param events generated events
     */
    private void logEventSummary(String label, List<MidiNoteEvent> events) {
        int notes = 0;
        int other = 0;
        for (MidiNoteEvent e : events) {
            if (e.getEventType() == MidiNoteEvent.EventType.NOTE) {
                notes++;
            } else {
                other++;
            }
        }
        log("[" + label + "] total=" + events.size()
                + " notes=" + notes + " other=" + other);
    }

}
