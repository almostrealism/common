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

package org.almostrealism.ml.midi;

import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SkyTNT V2 MIDI tokenizer: converts between {@link SkyTntMidiEvent} sequences
 * and the flat integer token sequences consumed by the SkyTNT midi-model.
 *
 * <h2>Vocabulary layout (3406 tokens)</h2>
 * <pre>
 *  0          – PAD
 *  1          – BOS
 *  2          – EOS
 *  3 – 8      – event-type tokens (note, patch_change, control_change,
 *                set_tempo, time_signature, key_signature)
 *  9 – 136    – time1 parameter values (0–127, delta beats)
 *  137 – 152  – time2 parameter values (0–15, 1/16th-beat offset)
 *  153 – 2200 – duration values (0–2047, in 1/16th-beat units)
 *  2201 – 2328 – track values (0–127)
 *  2329 – 2344 – channel values (0–15)
 *  2345 – 2472 – pitch values (0–127)
 *  2473 – 2600 – velocity values (0–127)
 *  2601 – 2728 – patch values (0–127)
 *  2729 – 2856 – controller values (0–127)
 *  2857 – 2984 – CC value (0–127)
 *  2985 – 3368 – BPM values (0–383)
 *  3369 – 3384 – nn (time-sig numerator − 1, 0–15)
 *  3385 – 3388 – dd (time-sig denominator − 1, 0–3)
 *  3389 – 3403 – sf (key-sig sharp/flat offset, 0–14)
 *  3404 – 3405 – mi (0 = major, 1 = minor)
 * </pre>
 *
 * <h2>Event token layout (max_token_seq = 8)</h2>
 * <pre>
 *  note           : [event_id, time1, time2, track, channel, pitch, velocity, duration]
 *  patch_change   : [event_id, time1, time2, track, channel, patch, PAD, PAD]
 *  control_change : [event_id, time1, time2, track, channel, controller, value, PAD]
 *  set_tempo      : [event_id, time1, time2, track, bpm, PAD, PAD, PAD]
 *  time_signature : [event_id, time1, time2, track, nn, dd, PAD, PAD]
 *  key_signature  : [event_id, time1, time2, track, sf, mi, PAD, PAD]
 *  BOS            : [BOS_ID, PAD, PAD, PAD, PAD, PAD, PAD, PAD]
 *  EOS            : [EOS_ID, PAD, PAD, PAD, PAD, PAD, PAD, PAD]
 * </pre>
 *
 * <h2>Timing quantization</h2>
 * <p>Given {@code ticksPerBeat} (the MIDI PPQ resolution):</p>
 * <pre>
 *  t       = round(16 * tick / ticksPerBeat)   // absolute 1/16th-beat units
 *  time1   = t / 16                            // absolute beat (coarse)
 *  time2   = t % 16                            // offset within beat (fine)
 * </pre>
 * <p>time1 is stored as a delta from the previous event's time1. Duration is
 * stored in 1/16th-beat units (clamped to 1–2047).</p>
 *
 * @see SkyTntMidiEvent
 */
public class SkyTntTokenizerV2 implements ConsoleFeatures {

    // -----------------------------------------------------------------------
    // Special token IDs
    // -----------------------------------------------------------------------

    /** PAD token ID (0). */
    public static final int PAD_ID = 0;

    /** BOS token ID (1). */
    public static final int BOS_ID = 1;

    /** EOS token ID (2). */
    public static final int EOS_ID = 2;

    // -----------------------------------------------------------------------
    // Event-type token IDs (3–8)
    // -----------------------------------------------------------------------

    /** Token ID for the {@code note} event type. */
    public static final int EVENT_NOTE = 3;

    /** Token ID for the {@code patch_change} event type. */
    public static final int EVENT_PATCH_CHANGE = 4;

    /** Token ID for the {@code control_change} event type. */
    public static final int EVENT_CONTROL_CHANGE = 5;

    /** Token ID for the {@code set_tempo} event type. */
    public static final int EVENT_SET_TEMPO = 6;

    /** Token ID for the {@code time_signature} event type. */
    public static final int EVENT_TIME_SIGNATURE = 7;

    /** Token ID for the {@code key_signature} event type. */
    public static final int EVENT_KEY_SIGNATURE = 8;

    // -----------------------------------------------------------------------
    // Parameter range offsets and sizes
    // -----------------------------------------------------------------------

    /** Base token ID for time1 parameter (128 values). */
    public static final int TIME1_OFFSET = 9;

    /** Base token ID for time2 parameter (16 values). */
    public static final int TIME2_OFFSET = 137;

    /** Base token ID for duration parameter (2048 values). */
    public static final int DURATION_OFFSET = 153;

    /** Base token ID for track parameter (128 values). */
    public static final int TRACK_OFFSET = 2201;

    /** Base token ID for channel parameter (16 values). */
    public static final int CHANNEL_OFFSET = 2329;

    /** Base token ID for pitch parameter (128 values). */
    public static final int PITCH_OFFSET = 2345;

    /** Base token ID for velocity parameter (128 values). */
    public static final int VELOCITY_OFFSET = 2473;

    /** Base token ID for patch parameter (128 values). */
    public static final int PATCH_OFFSET = 2601;

    /** Base token ID for controller parameter (128 values). */
    public static final int CONTROLLER_OFFSET = 2729;

    /** Base token ID for CC value parameter (128 values). */
    public static final int VALUE_OFFSET = 2857;

    /** Base token ID for BPM parameter (384 values). */
    public static final int BPM_OFFSET = 2985;

    /** Base token ID for nn (time-sig numerator minus 1) parameter (16 values). */
    public static final int NN_OFFSET = 3369;

    /** Base token ID for dd (time-sig denominator minus 1) parameter (4 values). */
    public static final int DD_OFFSET = 3385;

    /** Base token ID for sf (key-sig sharps/flats offset) parameter (15 values). */
    public static final int SF_OFFSET = 3389;

    /** Base token ID for mi (key-sig mode) parameter (2 values). */
    public static final int MI_OFFSET = 3404;

    /** Total vocabulary size. */
    public static final int VOCAB_SIZE = 3406;

    /** Maximum number of tokens per event (the sequence slot width). */
    public static final int MAX_TOKEN_SEQ = 8;

    // -----------------------------------------------------------------------
    // Precomputed validity masks
    //
    // validMasks[eventTypeId][step] is an int[] of token IDs that are valid
    // at the given step position when the event type at step 0 is eventTypeId.
    //
    // eventTypeId ranges 0..8, where 0–2 are unused (PAD/BOS/EOS),
    // 3 = note, 4 = patch_change, 5 = control_change,
    // 6 = set_tempo, 7 = time_signature, 8 = key_signature.
    //
    // step 0 is the same for all event types: valid tokens are EOS + event types.
    // -----------------------------------------------------------------------

    /** Validity mask for step 0 (same for all event types): EOS + event-type tokens. */
    private final int[][] step0Mask;

    /** Per-event-type, per-step validity masks. Index: [eventTypeId][step]. */
    private final int[][][] validMasks;

    /**
     * Constructs a new tokenizer and precomputes all validity masks.
     */
    public SkyTntTokenizerV2() {
        step0Mask = buildStep0Mask();
        validMasks = buildValidMasks();
        log("SkyTntTokenizerV2 initialized; vocab_size=" + VOCAB_SIZE);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Tokenizes a list of MIDI events into a 2D token array of shape
     * {@code (seq_len × MAX_TOKEN_SEQ)}, where seq_len includes the BOS
     * and EOS frame rows.
     *
     * <p>Events are sorted by tick before encoding. Each row has exactly
     * {@link #MAX_TOKEN_SEQ} token IDs; unused slots are padded with
     * {@link #PAD_ID}.</p>
     *
     * @param events       the MIDI events to tokenize
     * @param ticksPerBeat MIDI PPQ resolution (ticks per quarter-note beat)
     * @return token array of shape (events.size() + 2) × {@value #MAX_TOKEN_SEQ}
     */
    public int[][] tokenize(List<SkyTntMidiEvent> events, int ticksPerBeat) {
        List<SkyTntMidiEvent> sorted = new ArrayList<>(events);
        Collections.sort(sorted);

        int seqLen = sorted.size() + 2; // +2 for BOS and EOS
        int[][] tokens = new int[seqLen][MAX_TOKEN_SEQ];

        // Row 0: BOS
        tokens[0][0] = BOS_ID;
        // Remaining slots already 0 (PAD_ID)

        int prevTime1 = 0;

        for (int i = 0; i < sorted.size(); i++) {
            SkyTntMidiEvent event = sorted.get(i);
            int[] row = tokens[i + 1];

            int[] timing = quantizeTick(event.getTick(), prevTime1, ticksPerBeat);
            int time1Delta = timing[0];
            int time2 = timing[1];
            prevTime1 = prevTime1 + time1Delta;

            encodeEvent(event, time1Delta, time2, row);
        }

        // Last row: EOS
        tokens[seqLen - 1][0] = EOS_ID;
        // Remaining slots already 0 (PAD_ID)

        log("Tokenized " + sorted.size() + " events -> seq_len=" + seqLen);
        return tokens;
    }

    /**
     * Reconstructs a list of {@link SkyTntMidiEvent}s from a token array.
     *
     * <p>BOS, EOS, and fully-padded rows are stripped. Timing is reconstructed
     * by accumulating time1 deltas and combining with time2.</p>
     *
     * @param tokens       token array of shape (seq_len × MAX_TOKEN_SEQ)
     * @param ticksPerBeat MIDI PPQ resolution (ticks per quarter-note beat)
     * @return reconstructed MIDI events
     */
    public List<SkyTntMidiEvent> detokenize(int[][] tokens, int ticksPerBeat) {
        List<SkyTntMidiEvent> events = new ArrayList<>();
        int accumulatedTime1 = 0;

        for (int[] row : tokens) {
            int eventToken = row[0];

            if (eventToken == PAD_ID || eventToken == BOS_ID || eventToken == EOS_ID) {
                continue;
            }

            SkyTntMidiEvent event = decodeEvent(row, accumulatedTime1, ticksPerBeat);
            if (event == null) {
                warn("Skipping unrecognized event token " + eventToken);
                continue;
            }

            // Accumulate time1 from this event's delta
            int time1Delta = row[1] - TIME1_OFFSET;
            accumulatedTime1 += time1Delta;

            events.add(event);
        }

        log("Detokenized " + events.size() + " events from " + tokens.length + " rows");
        return events;
    }

    /**
     * Returns the set of valid token IDs at a given generation step.
     *
     * <p>At step 0 the result is independent of {@code eventTypeId}: all
     * event-type tokens (3–8) plus EOS (2) are valid. At steps 1–7 the valid
     * set is determined by the event type chosen at step 0.</p>
     *
     * @param step        current token position within the event (0–7)
     * @param eventTypeId event-type token ID selected at step 0 (3–8);
     *                    ignored when {@code step == 0}
     * @return array of valid token IDs for this step
     * @throws IllegalArgumentException if step is out of range [0, MAX_TOKEN_SEQ)
     */
    public int[] getValidTokenIds(int step, int eventTypeId) {
        if (step < 0 || step >= MAX_TOKEN_SEQ) {
            throw new IllegalArgumentException(
                    "step must be in [0, " + MAX_TOKEN_SEQ + "), got " + step);
        }
        if (step == 0) {
            return Arrays.copyOf(step0Mask[0], step0Mask[0].length);
        }
        if (eventTypeId < EVENT_NOTE || eventTypeId > EVENT_KEY_SIGNATURE) {
            throw new IllegalArgumentException(
                    "eventTypeId must be in [" + EVENT_NOTE + ", " + EVENT_KEY_SIGNATURE
                            + "], got " + eventTypeId);
        }
        int[] mask = validMasks[eventTypeId][step];
        return Arrays.copyOf(mask, mask.length);
    }

    // -----------------------------------------------------------------------
    // Encoding helpers
    // -----------------------------------------------------------------------

    /**
     * Quantizes an absolute tick to (time1_delta, time2).
     *
     * <p>time1 = absolute_beat, stored as delta from prevTime1.
     * time2 = 1/16th-beat position within the beat.
     * Both are clamped to their respective parameter ranges.</p>
     */
    private static int[] quantizeTick(long tick, int prevTime1, int ticksPerBeat) {
        long t = Math.round(16.0 * tick / ticksPerBeat);
        int time1Abs = (int) (t / 16);
        int time2 = (int) (t % 16);
        int time1Delta = Math.max(0, Math.min(127, time1Abs - prevTime1));
        time2 = Math.max(0, time2);
        return new int[]{time1Delta, time2};
    }

    /**
     * Converts a tick back from accumulated time1 + time2 representation.
     */
    private static long reconstructTick(int accumulatedTime1, int time2, int ticksPerBeat) {
        long t = (long) accumulatedTime1 * 16 + time2;
        return Math.round((double) t * ticksPerBeat / 16.0);
    }

    /**
     * Fills {@code row} with the token IDs for the given event using the
     * pre-computed time1 delta and time2 fine position.
     */
    private static void encodeEvent(SkyTntMidiEvent event, int time1Delta, int time2,
                                     int[] row) {
        switch (event.getEventType()) {
            case NOTE:
                row[0] = EVENT_NOTE;
                row[1] = TIME1_OFFSET + time1Delta;
                row[2] = TIME2_OFFSET + time2;
                row[3] = TRACK_OFFSET + MidiTokenizer.clamp(event.getTrack(), 0, 127);
                row[4] = CHANNEL_OFFSET + MidiTokenizer.clamp(event.getChannel(), 0, 15);
                row[5] = PITCH_OFFSET + MidiTokenizer.clamp(event.getPitch(), 0, 127);
                row[6] = VELOCITY_OFFSET + MidiTokenizer.clamp(event.getVelocity(), 0, 127);
                row[7] = DURATION_OFFSET + clampDuration(event.getDurationTicks());
                break;

            case PATCH_CHANGE:
                row[0] = EVENT_PATCH_CHANGE;
                row[1] = TIME1_OFFSET + time1Delta;
                row[2] = TIME2_OFFSET + time2;
                row[3] = TRACK_OFFSET + MidiTokenizer.clamp(event.getTrack(), 0, 127);
                row[4] = CHANNEL_OFFSET + MidiTokenizer.clamp(event.getChannel(), 0, 15);
                row[5] = PATCH_OFFSET + MidiTokenizer.clamp(event.getPatch(), 0, 127);
                // row[6] and row[7] remain PAD_ID (0)
                break;

            case CONTROL_CHANGE:
                row[0] = EVENT_CONTROL_CHANGE;
                row[1] = TIME1_OFFSET + time1Delta;
                row[2] = TIME2_OFFSET + time2;
                row[3] = TRACK_OFFSET + MidiTokenizer.clamp(event.getTrack(), 0, 127);
                row[4] = CHANNEL_OFFSET + MidiTokenizer.clamp(event.getChannel(), 0, 15);
                row[5] = CONTROLLER_OFFSET + MidiTokenizer.clamp(event.getController(), 0, 127);
                row[6] = VALUE_OFFSET + MidiTokenizer.clamp(event.getCcValue(), 0, 127);
                // row[7] remains PAD_ID (0)
                break;

            case SET_TEMPO:
                row[0] = EVENT_SET_TEMPO;
                row[1] = TIME1_OFFSET + time1Delta;
                row[2] = TIME2_OFFSET + time2;
                row[3] = TRACK_OFFSET + MidiTokenizer.clamp(event.getTrack(), 0, 127);
                row[4] = BPM_OFFSET + MidiTokenizer.clamp(event.getBpm(), 0, 383);
                // row[5], row[6], row[7] remain PAD_ID (0)
                break;

            case TIME_SIGNATURE:
                row[0] = EVENT_TIME_SIGNATURE;
                row[1] = TIME1_OFFSET + time1Delta;
                row[2] = TIME2_OFFSET + time2;
                row[3] = TRACK_OFFSET + MidiTokenizer.clamp(event.getTrack(), 0, 127);
                row[4] = NN_OFFSET + MidiTokenizer.clamp(event.getNn(), 0, 15);
                row[5] = DD_OFFSET + MidiTokenizer.clamp(event.getDd(), 0, 3);
                // row[6] and row[7] remain PAD_ID (0)
                break;

            case KEY_SIGNATURE:
                row[0] = EVENT_KEY_SIGNATURE;
                row[1] = TIME1_OFFSET + time1Delta;
                row[2] = TIME2_OFFSET + time2;
                row[3] = TRACK_OFFSET + MidiTokenizer.clamp(event.getTrack(), 0, 127);
                row[4] = SF_OFFSET + MidiTokenizer.clamp(event.getSf(), 0, 14);
                row[5] = MI_OFFSET + MidiTokenizer.clamp(event.getMi(), 0, 1);
                // row[6] and row[7] remain PAD_ID (0)
                break;

            default:
                throw new IllegalArgumentException("Unknown event type: " + event.getEventType());
        }
    }

    // -----------------------------------------------------------------------
    // Decoding helpers
    // -----------------------------------------------------------------------

    /**
     * Decodes a single token row into a {@link SkyTntMidiEvent}, or returns
     * {@code null} if the row contains invalid token IDs.
     */
    private static SkyTntMidiEvent decodeEvent(int[] row, int accumulatedTime1,
                                                int ticksPerBeat) {
        int eventToken = row[0];
        if (row[1] < TIME1_OFFSET || row[1] >= TIME1_OFFSET + 128) return null;
        if (row[2] < TIME2_OFFSET || row[2] >= TIME2_OFFSET + 16) return null;

        int time1Delta = row[1] - TIME1_OFFSET;
        int time2 = row[2] - TIME2_OFFSET;
        int time1Abs = accumulatedTime1 + time1Delta;
        long tick = reconstructTick(time1Abs, time2, ticksPerBeat);

        if (eventToken == EVENT_NOTE) {
            return decodeNote(row, tick);
        } else if (eventToken == EVENT_PATCH_CHANGE) {
            return decodePatchChange(row, tick);
        } else if (eventToken == EVENT_CONTROL_CHANGE) {
            return decodeControlChange(row, tick);
        } else if (eventToken == EVENT_SET_TEMPO) {
            return decodeSetTempo(row, tick);
        } else if (eventToken == EVENT_TIME_SIGNATURE) {
            return decodeTimeSignature(row, tick);
        } else if (eventToken == EVENT_KEY_SIGNATURE) {
            return decodeKeySignature(row, tick);
        }
        return null;
    }

    /** Decodes a NOTE row, or returns {@code null} on invalid token range. */
    private static SkyTntMidiEvent decodeNote(int[] row, long tick) {
        if (row[3] < TRACK_OFFSET || row[3] >= TRACK_OFFSET + 128) return null;
        if (row[4] < CHANNEL_OFFSET || row[4] >= CHANNEL_OFFSET + 16) return null;
        if (row[5] < PITCH_OFFSET || row[5] >= PITCH_OFFSET + 128) return null;
        if (row[6] < VELOCITY_OFFSET || row[6] >= VELOCITY_OFFSET + 128) return null;
        if (row[7] < DURATION_OFFSET || row[7] >= DURATION_OFFSET + 2048) return null;

        int track = row[3] - TRACK_OFFSET;
        int channel = row[4] - CHANNEL_OFFSET;
        int pitch = row[5] - PITCH_OFFSET;
        int velocity = row[6] - VELOCITY_OFFSET;
        int durationUnits = row[7] - DURATION_OFFSET;
        return SkyTntMidiEvent.note(tick, track, channel, pitch, velocity, durationUnits);
    }

    /** Decodes a PATCH_CHANGE row, or returns {@code null} on invalid token range. */
    private static SkyTntMidiEvent decodePatchChange(int[] row, long tick) {
        if (row[3] < TRACK_OFFSET || row[3] >= TRACK_OFFSET + 128) return null;
        if (row[4] < CHANNEL_OFFSET || row[4] >= CHANNEL_OFFSET + 16) return null;
        if (row[5] < PATCH_OFFSET || row[5] >= PATCH_OFFSET + 128) return null;

        int track = row[3] - TRACK_OFFSET;
        int channel = row[4] - CHANNEL_OFFSET;
        int patch = row[5] - PATCH_OFFSET;
        return SkyTntMidiEvent.patchChange(tick, track, channel, patch);
    }

    /** Decodes a CONTROL_CHANGE row, or returns {@code null} on invalid token range. */
    private static SkyTntMidiEvent decodeControlChange(int[] row, long tick) {
        if (row[3] < TRACK_OFFSET || row[3] >= TRACK_OFFSET + 128) return null;
        if (row[4] < CHANNEL_OFFSET || row[4] >= CHANNEL_OFFSET + 16) return null;
        if (row[5] < CONTROLLER_OFFSET || row[5] >= CONTROLLER_OFFSET + 128) return null;
        if (row[6] < VALUE_OFFSET || row[6] >= VALUE_OFFSET + 128) return null;

        int track = row[3] - TRACK_OFFSET;
        int channel = row[4] - CHANNEL_OFFSET;
        int controller = row[5] - CONTROLLER_OFFSET;
        int value = row[6] - VALUE_OFFSET;
        return SkyTntMidiEvent.controlChange(tick, track, channel, controller, value);
    }

    /** Decodes a SET_TEMPO row, or returns {@code null} on invalid token range. */
    private static SkyTntMidiEvent decodeSetTempo(int[] row, long tick) {
        if (row[3] < TRACK_OFFSET || row[3] >= TRACK_OFFSET + 128) return null;
        if (row[4] < BPM_OFFSET || row[4] >= BPM_OFFSET + 384) return null;

        int track = row[3] - TRACK_OFFSET;
        int bpm = row[4] - BPM_OFFSET;
        return SkyTntMidiEvent.setTempo(tick, track, bpm);
    }

    /** Decodes a TIME_SIGNATURE row, or returns {@code null} on invalid token range. */
    private static SkyTntMidiEvent decodeTimeSignature(int[] row, long tick) {
        if (row[3] < TRACK_OFFSET || row[3] >= TRACK_OFFSET + 128) return null;
        if (row[4] < NN_OFFSET || row[4] >= NN_OFFSET + 16) return null;
        if (row[5] < DD_OFFSET || row[5] >= DD_OFFSET + 4) return null;

        int track = row[3] - TRACK_OFFSET;
        int nn = row[4] - NN_OFFSET;
        int dd = row[5] - DD_OFFSET;
        return SkyTntMidiEvent.timeSignature(tick, track, nn, dd);
    }

    /** Decodes a KEY_SIGNATURE row, or returns {@code null} on invalid token range. */
    private static SkyTntMidiEvent decodeKeySignature(int[] row, long tick) {
        if (row[3] < TRACK_OFFSET || row[3] >= TRACK_OFFSET + 128) return null;
        if (row[4] < SF_OFFSET || row[4] >= SF_OFFSET + 15) return null;
        if (row[5] < MI_OFFSET || row[5] >= MI_OFFSET + 2) return null;

        int track = row[3] - TRACK_OFFSET;
        int sf = row[4] - SF_OFFSET;
        int mi = row[5] - MI_OFFSET;
        return SkyTntMidiEvent.keySignature(tick, track, sf, mi);
    }

    // -----------------------------------------------------------------------
    // Validity mask construction
    // -----------------------------------------------------------------------

    /**
     * Builds the step-0 mask: EOS plus all six event-type tokens.
     */
    private static int[][] buildStep0Mask() {
        int[] mask = new int[]{EOS_ID,
                EVENT_NOTE, EVENT_PATCH_CHANGE, EVENT_CONTROL_CHANGE,
                EVENT_SET_TEMPO, EVENT_TIME_SIGNATURE, EVENT_KEY_SIGNATURE};
        return new int[][]{mask};
    }

    /**
     * Builds the complete validity mask table.
     *
     * <p>Layout: {@code validMasks[eventTypeId][step]} is the int[] of valid
     * token IDs for that (eventType, step) pair. Index 0 is step 0 (shared),
     * indices 1–7 are the event-specific parameter steps.</p>
     */
    private static int[][][] buildValidMasks() {
        // Index 0..8; indices 0-2 (PAD/BOS/EOS) unused.
        int[][][] masks = new int[EVENT_KEY_SIGNATURE + 1][MAX_TOKEN_SEQ][];

        for (int evId = EVENT_NOTE; evId <= EVENT_KEY_SIGNATURE; evId++) {
            // Step 0 is always: EOS + event-type tokens
            masks[evId][0] = new int[]{EOS_ID,
                    EVENT_NOTE, EVENT_PATCH_CHANGE, EVENT_CONTROL_CHANGE,
                    EVENT_SET_TEMPO, EVENT_TIME_SIGNATURE, EVENT_KEY_SIGNATURE};

            // Steps 1-2 are shared: time1, time2
            masks[evId][1] = range(TIME1_OFFSET, 128);
            masks[evId][2] = range(TIME2_OFFSET, 16);
        }

        // note: step 3=track, 4=channel, 5=pitch, 6=velocity, 7=duration
        masks[EVENT_NOTE][3] = range(TRACK_OFFSET, 128);
        masks[EVENT_NOTE][4] = range(CHANNEL_OFFSET, 16);
        masks[EVENT_NOTE][5] = range(PITCH_OFFSET, 128);
        masks[EVENT_NOTE][6] = range(VELOCITY_OFFSET, 128);
        masks[EVENT_NOTE][7] = range(DURATION_OFFSET, 2048);

        // patch_change: step 3=track, 4=channel, 5=patch, 6=PAD, 7=PAD
        masks[EVENT_PATCH_CHANGE][3] = range(TRACK_OFFSET, 128);
        masks[EVENT_PATCH_CHANGE][4] = range(CHANNEL_OFFSET, 16);
        masks[EVENT_PATCH_CHANGE][5] = range(PATCH_OFFSET, 128);
        masks[EVENT_PATCH_CHANGE][6] = new int[]{PAD_ID};
        masks[EVENT_PATCH_CHANGE][7] = new int[]{PAD_ID};

        // control_change: step 3=track, 4=channel, 5=controller, 6=value, 7=PAD
        masks[EVENT_CONTROL_CHANGE][3] = range(TRACK_OFFSET, 128);
        masks[EVENT_CONTROL_CHANGE][4] = range(CHANNEL_OFFSET, 16);
        masks[EVENT_CONTROL_CHANGE][5] = range(CONTROLLER_OFFSET, 128);
        masks[EVENT_CONTROL_CHANGE][6] = range(VALUE_OFFSET, 128);
        masks[EVENT_CONTROL_CHANGE][7] = new int[]{PAD_ID};

        // set_tempo: step 3=track, 4=bpm, 5=PAD, 6=PAD, 7=PAD
        masks[EVENT_SET_TEMPO][3] = range(TRACK_OFFSET, 128);
        masks[EVENT_SET_TEMPO][4] = range(BPM_OFFSET, 384);
        masks[EVENT_SET_TEMPO][5] = new int[]{PAD_ID};
        masks[EVENT_SET_TEMPO][6] = new int[]{PAD_ID};
        masks[EVENT_SET_TEMPO][7] = new int[]{PAD_ID};

        // time_signature: step 3=track, 4=nn, 5=dd, 6=PAD, 7=PAD
        masks[EVENT_TIME_SIGNATURE][3] = range(TRACK_OFFSET, 128);
        masks[EVENT_TIME_SIGNATURE][4] = range(NN_OFFSET, 16);
        masks[EVENT_TIME_SIGNATURE][5] = range(DD_OFFSET, 4);
        masks[EVENT_TIME_SIGNATURE][6] = new int[]{PAD_ID};
        masks[EVENT_TIME_SIGNATURE][7] = new int[]{PAD_ID};

        // key_signature: step 3=track, 4=sf, 5=mi, 6=PAD, 7=PAD
        masks[EVENT_KEY_SIGNATURE][3] = range(TRACK_OFFSET, 128);
        masks[EVENT_KEY_SIGNATURE][4] = range(SF_OFFSET, 15);
        masks[EVENT_KEY_SIGNATURE][5] = range(MI_OFFSET, 2);
        masks[EVENT_KEY_SIGNATURE][6] = new int[]{PAD_ID};
        masks[EVENT_KEY_SIGNATURE][7] = new int[]{PAD_ID};

        return masks;
    }

    /** Builds a contiguous int[] array [offset, offset+1, ..., offset+count-1]. */
    private static int[] range(int offset, int count) {
        int[] arr = new int[count];
        for (int i = 0; i < count; i++) arr[i] = offset + i;
        return arr;
    }

    // -----------------------------------------------------------------------
    // Value helpers
    // -----------------------------------------------------------------------

    /**
     * Clamps a note duration (in ticks or 1/16th-beat units) to the valid
     * duration token range [1, 2047], defaulting to 1 if the input is zero.
     */
    private static int clampDuration(long duration) {
        int d = (int) Math.max(1, Math.min(2047, duration));
        return d;
    }
}
