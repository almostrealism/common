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

import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.studio.midi.SkyTntTokenizerV2;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for {@link SkyTntTokenizerV2}: token ID allocation, round-trip
 * correctness, BOS/EOS injection, padding, and validity-mask lookup.
 */
public class SkyTntTokenizerV2Test extends TestSuiteBase {

    /** Standard MIDI PPQ resolution used throughout these tests. */
    private static final int TICKS_PER_BEAT = 480;

    // -----------------------------------------------------------------------
    // Token ID allocation
    // -----------------------------------------------------------------------

    /**
     * Verify total vocabulary size is exactly 3406.
     */
    @Test(timeout = 60000)
    public void testVocabSize() {
        assertEquals("VOCAB_SIZE", 3406, SkyTntTokenizerV2.VOCAB_SIZE);
    }

    /**
     * Verify special token IDs match the V2 specification.
     */
    @Test(timeout = 60000)
    public void testSpecialTokenIds() {
        assertEquals("PAD_ID", 0, SkyTntTokenizerV2.PAD_ID);
        assertEquals("BOS_ID", 1, SkyTntTokenizerV2.BOS_ID);
        assertEquals("EOS_ID", 2, SkyTntTokenizerV2.EOS_ID);
    }

    /**
     * Verify event-type token IDs are 3–8 in the declared order.
     */
    @Test(timeout = 60000)
    public void testEventTypeTokenIds() {
        assertEquals("EVENT_NOTE", 3, SkyTntTokenizerV2.EVENT_NOTE);
        assertEquals("EVENT_PATCH_CHANGE", 4, SkyTntTokenizerV2.EVENT_PATCH_CHANGE);
        assertEquals("EVENT_CONTROL_CHANGE", 5, SkyTntTokenizerV2.EVENT_CONTROL_CHANGE);
        assertEquals("EVENT_SET_TEMPO", 6, SkyTntTokenizerV2.EVENT_SET_TEMPO);
        assertEquals("EVENT_TIME_SIGNATURE", 7, SkyTntTokenizerV2.EVENT_TIME_SIGNATURE);
        assertEquals("EVENT_KEY_SIGNATURE", 8, SkyTntTokenizerV2.EVENT_KEY_SIGNATURE);
    }

    /**
     * Verify every parameter range starts at the correct offset and is
     * contiguous with the preceding range.
     */
    @Test(timeout = 60000)
    public void testParameterRangeOffsets() {
        assertEquals("TIME1_OFFSET",      9,    SkyTntTokenizerV2.TIME1_OFFSET);
        assertEquals("TIME2_OFFSET",      137,  SkyTntTokenizerV2.TIME2_OFFSET);
        assertEquals("DURATION_OFFSET",   153,  SkyTntTokenizerV2.DURATION_OFFSET);
        assertEquals("TRACK_OFFSET",      2201, SkyTntTokenizerV2.TRACK_OFFSET);
        assertEquals("CHANNEL_OFFSET",    2329, SkyTntTokenizerV2.CHANNEL_OFFSET);
        assertEquals("PITCH_OFFSET",      2345, SkyTntTokenizerV2.PITCH_OFFSET);
        assertEquals("VELOCITY_OFFSET",   2473, SkyTntTokenizerV2.VELOCITY_OFFSET);
        assertEquals("PATCH_OFFSET",      2601, SkyTntTokenizerV2.PATCH_OFFSET);
        assertEquals("CONTROLLER_OFFSET", 2729, SkyTntTokenizerV2.CONTROLLER_OFFSET);
        assertEquals("VALUE_OFFSET",      2857, SkyTntTokenizerV2.VALUE_OFFSET);
        assertEquals("BPM_OFFSET",        2985, SkyTntTokenizerV2.BPM_OFFSET);
        assertEquals("NN_OFFSET",         3369, SkyTntTokenizerV2.NN_OFFSET);
        assertEquals("DD_OFFSET",         3385, SkyTntTokenizerV2.DD_OFFSET);
        assertEquals("SF_OFFSET",         3389, SkyTntTokenizerV2.SF_OFFSET);
        assertEquals("MI_OFFSET",         3404, SkyTntTokenizerV2.MI_OFFSET);
    }

    /**
     * Verify the ranges are contiguous: no gaps and no overlap.
     */
    @Test(timeout = 60000)
    public void testRangesAreContiguous() {
        assertEquals("time1 end = time2 start",
                SkyTntTokenizerV2.TIME1_OFFSET + 128, SkyTntTokenizerV2.TIME2_OFFSET);
        assertEquals("time2 end = duration start",
                SkyTntTokenizerV2.TIME2_OFFSET + 16, SkyTntTokenizerV2.DURATION_OFFSET);
        assertEquals("duration end = track start",
                SkyTntTokenizerV2.DURATION_OFFSET + 2048, SkyTntTokenizerV2.TRACK_OFFSET);
        assertEquals("track end = channel start",
                SkyTntTokenizerV2.TRACK_OFFSET + 128, SkyTntTokenizerV2.CHANNEL_OFFSET);
        assertEquals("channel end = pitch start",
                SkyTntTokenizerV2.CHANNEL_OFFSET + 16, SkyTntTokenizerV2.PITCH_OFFSET);
        assertEquals("pitch end = velocity start",
                SkyTntTokenizerV2.PITCH_OFFSET + 128, SkyTntTokenizerV2.VELOCITY_OFFSET);
        assertEquals("velocity end = patch start",
                SkyTntTokenizerV2.VELOCITY_OFFSET + 128, SkyTntTokenizerV2.PATCH_OFFSET);
        assertEquals("patch end = controller start",
                SkyTntTokenizerV2.PATCH_OFFSET + 128, SkyTntTokenizerV2.CONTROLLER_OFFSET);
        assertEquals("controller end = value start",
                SkyTntTokenizerV2.CONTROLLER_OFFSET + 128, SkyTntTokenizerV2.VALUE_OFFSET);
        assertEquals("value end = bpm start",
                SkyTntTokenizerV2.VALUE_OFFSET + 128, SkyTntTokenizerV2.BPM_OFFSET);
        assertEquals("bpm end = nn start",
                SkyTntTokenizerV2.BPM_OFFSET + 384, SkyTntTokenizerV2.NN_OFFSET);
        assertEquals("nn end = dd start",
                SkyTntTokenizerV2.NN_OFFSET + 16, SkyTntTokenizerV2.DD_OFFSET);
        assertEquals("dd end = sf start",
                SkyTntTokenizerV2.DD_OFFSET + 4, SkyTntTokenizerV2.SF_OFFSET);
        assertEquals("sf end = mi start",
                SkyTntTokenizerV2.SF_OFFSET + 15, SkyTntTokenizerV2.MI_OFFSET);
        assertEquals("mi end = VOCAB_SIZE",
                SkyTntTokenizerV2.MI_OFFSET + 2, SkyTntTokenizerV2.VOCAB_SIZE);
    }

    // -----------------------------------------------------------------------
    // BOS / EOS injection
    // -----------------------------------------------------------------------

    /**
     * Verify BOS and EOS rows are prepended and appended correctly.
     */
    @Test(timeout = 60000)
    public void testBosEosInjection() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.note(0, 0, 0, 60, 80, 480));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);

        assertEquals("seq_len = events + 2", 3, tokens.length);
        assertEquals("row 0 token 0 = BOS", SkyTntTokenizerV2.BOS_ID, tokens[0][0]);
        for (int i = 1; i < SkyTntTokenizerV2.MAX_TOKEN_SEQ; i++) {
            assertEquals("BOS row padded at " + i, SkyTntTokenizerV2.PAD_ID, tokens[0][i]);
        }
        assertEquals("last row token 0 = EOS", SkyTntTokenizerV2.EOS_ID,
                tokens[tokens.length - 1][0]);
        for (int i = 1; i < SkyTntTokenizerV2.MAX_TOKEN_SEQ; i++) {
            assertEquals("EOS row padded at " + i, SkyTntTokenizerV2.PAD_ID,
                    tokens[tokens.length - 1][i]);
        }
    }

    /**
     * Verify BOS/EOS rows are stripped during detokenization.
     */
    @Test(timeout = 60000)
    public void testBosEosStripping() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.note(0, 0, 0, 60, 80, 480));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("BOS/EOS stripped; 1 event returned", 1, decoded.size());
    }

    // -----------------------------------------------------------------------
    // Padding to MAX_TOKEN_SEQ = 8
    // -----------------------------------------------------------------------

    /**
     * Verify every row has exactly MAX_TOKEN_SEQ = 8 token IDs.
     */
    @Test(timeout = 60000)
    public void testRowWidthAlwaysEight() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.note(0, 0, 0, 60, 80, 480));
        events.add(MidiNoteEvent.patchChange(240, 0, 1, 40));
        events.add(MidiNoteEvent.setTempo(480, 0, 120));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        for (int r = 0; r < tokens.length; r++) {
            assertEquals("row " + r + " width", SkyTntTokenizerV2.MAX_TOKEN_SEQ,
                    tokens[r].length);
        }
    }

    /**
     * Verify events shorter than 8 tokens are padded with PAD_ID.
     */
    @Test(timeout = 60000)
    public void testShortEventsArePadded() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.setTempo(0, 0, 120)); // 5 tokens + 3 PAD

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        // Row 1 = the set_tempo event (row 0 = BOS)
        int[] row = tokens[1];
        assertEquals("set_tempo event_id", SkyTntTokenizerV2.EVENT_SET_TEMPO, row[0]);
        assertEquals("pad at slot 5", SkyTntTokenizerV2.PAD_ID, row[5]);
        assertEquals("pad at slot 6", SkyTntTokenizerV2.PAD_ID, row[6]);
        assertEquals("pad at slot 7", SkyTntTokenizerV2.PAD_ID, row[7]);
    }

    // -----------------------------------------------------------------------
    // Round-trip tests for all 6 event types
    // -----------------------------------------------------------------------

    /**
     * Round-trip test for NOTE events.
     */
    @Test(timeout = 60000)
    public void testNoteRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        // tick=960 → beat=2, time2=0 with 480 ticks/beat
        events.add(MidiNoteEvent.note(960, 1, 3, 72, 100, 960));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("decoded count", 1, decoded.size());
        MidiNoteEvent d = decoded.get(0);
        Assert.assertEquals("event type", MidiNoteEvent.EventType.NOTE, d.getEventType());
        assertEquals("track", 1, d.getTrack());
        assertEquals("channel", 3, d.getChannel());
        assertEquals("pitch", 72, d.getPitch());
        assertEquals("velocity", 100, d.getVelocity());
    }

    /**
     * Round-trip test for PATCH_CHANGE events.
     */
    @Test(timeout = 60000)
    public void testPatchChangeRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.patchChange(0, 0, 2, 40));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("decoded count", 1, decoded.size());
        MidiNoteEvent d = decoded.get(0);
        Assert.assertEquals("event type", MidiNoteEvent.EventType.PATCH_CHANGE, d.getEventType());
        assertEquals("channel", 2, d.getChannel());
        assertEquals("patch", 40, d.getPatch());
    }

    /**
     * Round-trip test for CONTROL_CHANGE events.
     */
    @Test(timeout = 60000)
    public void testControlChangeRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.controlChange(480, 0, 0, 7, 100));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("decoded count", 1, decoded.size());
        MidiNoteEvent d = decoded.get(0);
        Assert.assertEquals("event type", MidiNoteEvent.EventType.CONTROL_CHANGE, d.getEventType());
        assertEquals("controller", 7, d.getController());
        assertEquals("cc value", 100, d.getCcValue());
    }

    /**
     * Round-trip test for SET_TEMPO events.
     */
    @Test(timeout = 60000)
    public void testSetTempoRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.setTempo(0, 0, 140));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("decoded count", 1, decoded.size());
        MidiNoteEvent d = decoded.get(0);
        Assert.assertEquals("event type", MidiNoteEvent.EventType.SET_TEMPO, d.getEventType());
        assertEquals("bpm", 140, d.getBpm());
    }

    /**
     * Round-trip test for TIME_SIGNATURE events.
     */
    @Test(timeout = 60000)
    public void testTimeSignatureRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        // 4/4 time: nn=3 (4-1), dd=1 (4=2^(1+1))
        events.add(MidiNoteEvent.timeSignature(0, 0, 3, 1));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("decoded count", 1, decoded.size());
        MidiNoteEvent d = decoded.get(0);
        Assert.assertEquals("event type", MidiNoteEvent.EventType.TIME_SIGNATURE, d.getEventType());
        assertEquals("nn", 3, d.getNn());
        assertEquals("dd", 1, d.getDd());
    }

    /**
     * Round-trip test for KEY_SIGNATURE events.
     */
    @Test(timeout = 60000)
    public void testKeySignatureRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        // C major: sf=7 (0 sharps/flats + 7 offset), mi=0 (major)
        events.add(MidiNoteEvent.keySignature(0, 0, 7, 0));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("decoded count", 1, decoded.size());
        MidiNoteEvent d = decoded.get(0);
        Assert.assertEquals("event type", MidiNoteEvent.EventType.KEY_SIGNATURE, d.getEventType());
        assertEquals("sf", 7, d.getSf());
        assertEquals("mi", 0, d.getMi());
    }

    // -----------------------------------------------------------------------
    // Multiple events and timing
    // -----------------------------------------------------------------------

    /**
     * Round-trip test with multiple events of mixed types.
     */
    @Test(timeout = 60000)
    public void testMixedEventRoundTrip() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.setTempo(0, 0, 120));
        events.add(MidiNoteEvent.timeSignature(0, 0, 3, 1));
        events.add(MidiNoteEvent.note(480, 0, 0, 60, 80, 480));
        events.add(MidiNoteEvent.note(960, 0, 0, 64, 80, 480));
        events.add(MidiNoteEvent.patchChange(1440, 0, 1, 25));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);

        assertEquals("event count preserved", events.size(), decoded.size());
        for (int i = 0; i < events.size(); i++) {
            Assert.assertEquals("event type at " + i,
                    events.get(i).getEventType(), decoded.get(i).getEventType());
        }
    }

    /**
     * Verify that the time1 delta encoding is monotonically consistent:
     * two notes one beat apart should produce time1_delta=1 for the second.
     */
    @Test(timeout = 60000)
    public void testTime1DeltaEncoding() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.note(0, 0, 0, 60, 80, 480));
        events.add(MidiNoteEvent.note(480, 0, 0, 62, 80, 480)); // 1 beat later

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        // Row 1 = first note, row 2 = second note
        int time1First = tokens[1][1] - SkyTntTokenizerV2.TIME1_OFFSET;
        int time1Second = tokens[2][1] - SkyTntTokenizerV2.TIME1_OFFSET;

        assertEquals("first note time1 delta", 0, time1First);
        assertEquals("second note time1 delta = 1 beat", 1, time1Second);
    }

    /**
     * Verify time2 encodes the fractional-beat position within [0,15].
     */
    @Test(timeout = 60000)
    public void testTime2FineResolution() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        // 480 / 16 = 30 ticks per 1/16th beat.  3 * 30 = 90 ticks = time2=3
        events.add(MidiNoteEvent.note(90, 0, 0, 60, 80, 480));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        int time2 = tokens[1][2] - SkyTntTokenizerV2.TIME2_OFFSET;
        assertEquals("time2 for 90 ticks at 480 ppq", 3, time2);
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    /**
     * Empty input produces exactly BOS + EOS (seq_len = 2).
     */
    @Test(timeout = 60000)
    public void testEmptyInput() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int[][] tokens = tokenizer.tokenize(new ArrayList<>(), TICKS_PER_BEAT);
        assertEquals("empty → 2 rows", 2, tokens.length);
        assertEquals("row 0 = BOS", SkyTntTokenizerV2.BOS_ID, tokens[0][0]);
        assertEquals("row 1 = EOS", SkyTntTokenizerV2.EOS_ID, tokens[1][0]);

        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);
        assertTrue("empty detokenize", decoded.isEmpty());
    }

    /**
     * Single note round-trip.
     */
    @Test(timeout = 60000)
    public void testSingleNote() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        events.add(MidiNoteEvent.note(0, 0, 0, 60, 100, 480));

        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);
        assertEquals("seq_len", 3, tokens.length);

        List<MidiNoteEvent> decoded = tokenizer.detokenize(tokens, TICKS_PER_BEAT);
        assertEquals("1 note decoded", 1, decoded.size());
        assertEquals("pitch", 60, decoded.get(0).getPitch());
        assertEquals("velocity", 100, decoded.get(0).getVelocity());
    }

    /**
     * Maximum parameter values are encoded without crashing or wrapping.
     */
    @Test(timeout = 60000)
    public void testMaxParameterValues() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        List<MidiNoteEvent> events = new ArrayList<>();
        // Maximum ticks = 127 beats (time1 max) * TICKS_PER_BEAT
        long maxTick = 127L * TICKS_PER_BEAT;
        events.add(MidiNoteEvent.note(maxTick, 127, 15, 127, 127, 2047));
        events.add(MidiNoteEvent.setTempo(0, 127, 383));
        events.add(MidiNoteEvent.timeSignature(0, 0, 15, 3));
        events.add(MidiNoteEvent.keySignature(0, 0, 14, 1));
        events.add(MidiNoteEvent.patchChange(0, 127, 15, 127));
        events.add(MidiNoteEvent.controlChange(0, 127, 15, 127, 127));

        // Should not throw
        int[][] tokens = tokenizer.tokenize(events, TICKS_PER_BEAT);

        for (int r = 0; r < tokens.length; r++) {
            for (int c = 0; c < SkyTntTokenizerV2.MAX_TOKEN_SEQ; c++) {
                int id = tokens[r][c];
                assertTrue("token[" + r + "][" + c + "]=" + id + " in [0, VOCAB_SIZE)",
                        id >= 0 && id < SkyTntTokenizerV2.VOCAB_SIZE);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Validity mask
    // -----------------------------------------------------------------------

    /**
     * Step 0 validity mask includes EOS and all 6 event-type tokens.
     */
    @Test(timeout = 60000)
    public void testStep0MaskContainsAllEventTypes() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        // eventTypeId is irrelevant at step 0
        int[] mask = tokenizer.getValidTokenIds(0, SkyTntTokenizerV2.EVENT_NOTE);

        Set<Integer> maskSet = toSet(mask);
        assertTrue("EOS in step-0 mask", maskSet.contains(SkyTntTokenizerV2.EOS_ID));
        assertTrue("EVENT_NOTE in step-0 mask",
                maskSet.contains(SkyTntTokenizerV2.EVENT_NOTE));
        assertTrue("EVENT_PATCH_CHANGE in step-0 mask",
                maskSet.contains(SkyTntTokenizerV2.EVENT_PATCH_CHANGE));
        assertTrue("EVENT_CONTROL_CHANGE in step-0 mask",
                maskSet.contains(SkyTntTokenizerV2.EVENT_CONTROL_CHANGE));
        assertTrue("EVENT_SET_TEMPO in step-0 mask",
                maskSet.contains(SkyTntTokenizerV2.EVENT_SET_TEMPO));
        assertTrue("EVENT_TIME_SIGNATURE in step-0 mask",
                maskSet.contains(SkyTntTokenizerV2.EVENT_TIME_SIGNATURE));
        assertTrue("EVENT_KEY_SIGNATURE in step-0 mask",
                maskSet.contains(SkyTntTokenizerV2.EVENT_KEY_SIGNATURE));
        assertEquals("step-0 mask size = 7", 7, mask.length);
    }

    /**
     * Validity mask for NOTE at steps 1–7 maps to the correct parameter ranges.
     */
    @Test(timeout = 60000)
    public void testNoteMaskSteps() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int evId = SkyTntTokenizerV2.EVENT_NOTE;

        assertRangeEquals("step1=time1",
                SkyTntTokenizerV2.TIME1_OFFSET, 128,
                tokenizer.getValidTokenIds(1, evId));
        assertRangeEquals("step2=time2",
                SkyTntTokenizerV2.TIME2_OFFSET, 16,
                tokenizer.getValidTokenIds(2, evId));
        assertRangeEquals("step3=track",
                SkyTntTokenizerV2.TRACK_OFFSET, 128,
                tokenizer.getValidTokenIds(3, evId));
        assertRangeEquals("step4=channel",
                SkyTntTokenizerV2.CHANNEL_OFFSET, 16,
                tokenizer.getValidTokenIds(4, evId));
        assertRangeEquals("step5=pitch",
                SkyTntTokenizerV2.PITCH_OFFSET, 128,
                tokenizer.getValidTokenIds(5, evId));
        assertRangeEquals("step6=velocity",
                SkyTntTokenizerV2.VELOCITY_OFFSET, 128,
                tokenizer.getValidTokenIds(6, evId));
        assertRangeEquals("step7=duration",
                SkyTntTokenizerV2.DURATION_OFFSET, 2048,
                tokenizer.getValidTokenIds(7, evId));
    }

    /**
     * Validity mask for PATCH_CHANGE: steps 6 and 7 are PAD only.
     */
    @Test(timeout = 60000)
    public void testPatchChangeMaskPaddedSlots() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int evId = SkyTntTokenizerV2.EVENT_PATCH_CHANGE;

        int[] mask6 = tokenizer.getValidTokenIds(6, evId);
        int[] mask7 = tokenizer.getValidTokenIds(7, evId);

        assertEquals("patch_change step6 has 1 valid token", 1, mask6.length);
        assertEquals("patch_change step6 = PAD", SkyTntTokenizerV2.PAD_ID, mask6[0]);
        assertEquals("patch_change step7 has 1 valid token", 1, mask7.length);
        assertEquals("patch_change step7 = PAD", SkyTntTokenizerV2.PAD_ID, mask7[0]);
    }

    /**
     * Validity mask for CONTROL_CHANGE: step 7 is PAD only.
     */
    @Test(timeout = 60000)
    public void testControlChangeMaskStep7Padded() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int[] mask7 = tokenizer.getValidTokenIds(7,
                SkyTntTokenizerV2.EVENT_CONTROL_CHANGE);

        assertEquals("control_change step7 size", 1, mask7.length);
        assertEquals("control_change step7 = PAD", SkyTntTokenizerV2.PAD_ID, mask7[0]);
    }

    /**
     * Validity mask for SET_TEMPO at step 4 covers the full BPM range (0–383).
     */
    @Test(timeout = 60000)
    public void testSetTempoMaskBpmRange() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int[] bpmMask = tokenizer.getValidTokenIds(4,
                SkyTntTokenizerV2.EVENT_SET_TEMPO);

        assertRangeEquals("set_tempo step4=bpm",
                SkyTntTokenizerV2.BPM_OFFSET, 384, bpmMask);
    }

    /**
     * Validity mask for TIME_SIGNATURE: step 4=nn (16 values), step 5=dd (4 values).
     */
    @Test(timeout = 60000)
    public void testTimeSignatureMaskNnDd() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int evId = SkyTntTokenizerV2.EVENT_TIME_SIGNATURE;

        assertRangeEquals("time_sig step4=nn", SkyTntTokenizerV2.NN_OFFSET, 16,
                tokenizer.getValidTokenIds(4, evId));
        assertRangeEquals("time_sig step5=dd", SkyTntTokenizerV2.DD_OFFSET, 4,
                tokenizer.getValidTokenIds(5, evId));
    }

    /**
     * Validity mask for KEY_SIGNATURE: step 4=sf (15 values), step 5=mi (2 values).
     */
    @Test(timeout = 60000)
    public void testKeySignatureMaskSfMi() {
        SkyTntTokenizerV2 tokenizer = new SkyTntTokenizerV2();
        int evId = SkyTntTokenizerV2.EVENT_KEY_SIGNATURE;

        assertRangeEquals("key_sig step4=sf", SkyTntTokenizerV2.SF_OFFSET, 15,
                tokenizer.getValidTokenIds(4, evId));
        assertRangeEquals("key_sig step5=mi", SkyTntTokenizerV2.MI_OFFSET, 2,
                tokenizer.getValidTokenIds(5, evId));
    }

    /**
     * getValidTokenIds throws IllegalArgumentException for an out-of-range step.
     */
    @Test(timeout = 60000, expected = IllegalArgumentException.class)
    public void testInvalidStepThrows() {
        new SkyTntTokenizerV2().getValidTokenIds(8, SkyTntTokenizerV2.EVENT_NOTE);
    }

    /**
     * getValidTokenIds throws IllegalArgumentException for an out-of-range eventTypeId.
     */
    @Test(timeout = 60000, expected = IllegalArgumentException.class)
    public void testInvalidEventTypeThrows() {
        new SkyTntTokenizerV2().getValidTokenIds(1, 99);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Set<Integer> toSet(int[] arr) {
        Set<Integer> s = new HashSet<>();
        for (int v : arr) s.add(v);
        return s;
    }

    private void assertRangeEquals(String msg, int offset, int count, int[] actual) {
        assertEquals(msg + " size", count, actual.length);
        for (int i = 0; i < count; i++) {
            assertEquals(msg + " value[" + i + "]", offset + i, actual[i]);
        }
    }
}
