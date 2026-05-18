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

package org.almostrealism.music.midi;

/**
 * Unified representation of a MIDI event, covering all standard event types.
 *
 * <p>This class serves as the single canonical MIDI event type for both the
 * Moonbeam tokenizer ({@link org.almostrealism.studio.midi.MidiTokenizer}) and the SkyTNT V2 tokenizer
 * ({@link org.almostrealism.studio.midi.SkyTntTokenizerV2}), as well as the pattern-to-MIDI export pipeline.</p>
 *
 * <p>Supported event types are defined by {@link EventType}: {@code NOTE},
 * {@code PATCH_CHANGE}, {@code CONTROL_CHANGE}, {@code SET_TEMPO},
 * {@code TIME_SIGNATURE}, and {@code KEY_SIGNATURE}.</p>
 *
 * <p>For note events, the backward-compatible constructor accepts the note's
 * pitch, absolute onset tick, duration, velocity, and instrument number
 * (0–127 for GM program, 128 for drums):</p>
 * <pre>{@code
 * MidiNoteEvent note = new MidiNoteEvent(60, 100L, 50L, 80, 0);
 * }</pre>
 *
 * <p>For any event type, use the static factory methods:</p>
 * <pre>{@code
 * MidiNoteEvent note   = MidiNoteEvent.note(tick, track, channel, pitch, velocity, durationTicks);
 * MidiNoteEvent tempo  = MidiNoteEvent.setTempo(tick, track, 120);
 * }</pre>
 *
 * <p>Timing is stored as an absolute tick offset from the start of the sequence.
 * The getter {@link #getOnset()} is an alias for {@link #getTick()} provided for
 * backward compatibility with the Moonbeam tokenizer.</p>
 *
 * @see org.almostrealism.studio.midi.MidiTokenizer
 * @see org.almostrealism.studio.midi.SkyTntTokenizerV2
 * @see org.almostrealism.music.midi.MidiFileReader
 */
public class MidiNoteEvent implements Comparable<MidiNoteEvent> {

    /** Ticks per second for onset/duration quantization (Moonbeam tokenizer). */
    public static final int TIME_RESOLUTION = 100;

    /**
     * Offset added to a {@code KeyPosition.position()} value to obtain a standard
     * MIDI pitch number. {@code WesternChromatic.A0} has position 0; MIDI A0 = 21.
     */
    public static final int PITCH_OFFSET = 21;

    /** Default MIDI velocity when no automation data is available. */
    public static final int DEFAULT_VELOCITY = 100;

    /** Instrument number indicating a percussive (drum) channel. */
    public static final int DRUM_INSTRUMENT = 128;

    /** MIDI channel 10 (0-indexed as 9) is reserved for drums. */
    static final int DRUM_CHANNEL = 9;

    /**
     * The six standard MIDI event types supported by this class,
     * corresponding to the SkyTNT V2 tokenizer vocabulary.
     */
    public enum EventType {
        /** Standard MIDI note (note-on + note-off with duration). */
        NOTE,
        /** Program/patch change event. */
        PATCH_CHANGE,
        /** Continuous-controller change event. */
        CONTROL_CHANGE,
        /** Tempo change (microseconds per beat encoded as BPM). */
        SET_TEMPO,
        /** Time-signature change. */
        TIME_SIGNATURE,
        /** Key-signature change. */
        KEY_SIGNATURE
    }

    /** The event type discriminant. */
    private final EventType eventType;

    /** Absolute tick offset from the start of the sequence. */
    private final long tick;

    /** MIDI track index (0-based). */
    private final int track;

    /** MIDI channel (0–15). Relevant for NOTE, PATCH_CHANGE, CONTROL_CHANGE. */
    private final int channel;

    /** MIDI pitch number, 0–127 (NOTE events only). */
    private final int pitch;

    /** MIDI velocity, 0–127 (NOTE events only). */
    private final int velocity;

    /** Note duration in ticks (NOTE events only). */
    private final long durationTicks;

    /** GM program number, 0–127 (PATCH_CHANGE events; derived for backward-compat NOTE events). */
    private final int patch;

    /** CC controller number, 0–127 (CONTROL_CHANGE events only). */
    private final int controller;

    /** CC value, 0–127 (CONTROL_CHANGE events only). */
    private final int ccValue;

    /** Tempo in BPM (SET_TEMPO events only). */
    private final int bpm;

    /** Time-signature numerator minus 1, 0–15 (TIME_SIGNATURE events only). */
    private final int nn;

    /** Time-signature denominator minus 1, 0–3 (TIME_SIGNATURE events only). */
    private final int dd;

    /** Key-signature sharps/flats offset, 0–14 (KEY_SIGNATURE events only). */
    private final int sf;

    /** Key-signature mode: 0 = major, 1 = minor (KEY_SIGNATURE events only). */
    private final int mi;

    /**
     * Full private constructor — use the public note constructor or the static factory methods.
     */
    private MidiNoteEvent(EventType eventType, long tick, int track,
                          int channel, int pitch, int velocity, long durationTicks,
                          int patch, int controller, int ccValue,
                          int bpm, int nn, int dd, int sf, int mi) {
        this.eventType = eventType;
        this.tick = tick;
        this.track = track;
        this.channel = channel;
        this.pitch = pitch;
        this.velocity = velocity;
        this.durationTicks = durationTicks;
        this.patch = patch;
        this.controller = controller;
        this.ccValue = ccValue;
        this.bpm = bpm;
        this.nn = nn;
        this.dd = dd;
        this.sf = sf;
        this.mi = mi;
    }

    /**
     * Backward-compatible constructor for note events.
     *
     * <p>Creates a {@link EventType#NOTE} event. Instrument {@link #DRUM_INSTRUMENT} (128)
     * indicates a drum note and maps the channel to {@link #DRUM_CHANNEL} (9); all other
     * instrument values are treated as GM program numbers on channel 0.</p>
     *
     * @param pitch      MIDI pitch number (0–127)
     * @param onset      absolute onset time in ticks
     * @param duration   note duration in ticks
     * @param velocity   MIDI velocity (0–127)
     * @param instrument GM program number (0–127) or {@link #DRUM_INSTRUMENT} (128) for drums
     */
    public MidiNoteEvent(int pitch, long onset, long duration, int velocity, int instrument) {
        this(EventType.NOTE, onset, 0,
                instrument == DRUM_INSTRUMENT ? DRUM_CHANNEL : 0,
                pitch, velocity, duration,
                instrument == DRUM_INSTRUMENT ? 0 : instrument,
                0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Creates a NOTE event.
     *
     * @param tick          absolute tick offset
     * @param track         MIDI track index (0-based)
     * @param channel       MIDI channel (0–15)
     * @param pitch         MIDI pitch (0–127)
     * @param velocity      MIDI velocity (0–127)
     * @param durationTicks note duration in ticks
     * @return the note event
     */
    public static MidiNoteEvent note(long tick, int track, int channel,
                                     int pitch, int velocity, long durationTicks) {
        return new MidiNoteEvent(EventType.NOTE, tick, track,
                channel, pitch, velocity, durationTicks,
                0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Creates a PATCH_CHANGE event.
     *
     * @param tick    absolute tick offset
     * @param track   MIDI track index (0-based)
     * @param channel MIDI channel (0–15)
     * @param patch   GM program number (0–127)
     * @return the patch-change event
     */
    public static MidiNoteEvent patchChange(long tick, int track, int channel, int patch) {
        return new MidiNoteEvent(EventType.PATCH_CHANGE, tick, track,
                channel, 0, 0, 0,
                patch, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Creates a CONTROL_CHANGE event.
     *
     * @param tick       absolute tick offset
     * @param track      MIDI track index (0-based)
     * @param channel    MIDI channel (0–15)
     * @param controller CC controller number (0–127)
     * @param ccValue    CC value (0–127)
     * @return the control-change event
     */
    public static MidiNoteEvent controlChange(long tick, int track, int channel,
                                               int controller, int ccValue) {
        return new MidiNoteEvent(EventType.CONTROL_CHANGE, tick, track,
                channel, 0, 0, 0,
                0, controller, ccValue, 0, 0, 0, 0, 0);
    }

    /**
     * Creates a SET_TEMPO event.
     *
     * @param tick  absolute tick offset
     * @param track MIDI track index (0-based)
     * @param bpm   tempo in beats per minute
     * @return the tempo event
     */
    public static MidiNoteEvent setTempo(long tick, int track, int bpm) {
        return new MidiNoteEvent(EventType.SET_TEMPO, tick, track,
                0, 0, 0, 0,
                0, 0, 0, bpm, 0, 0, 0, 0);
    }

    /**
     * Creates a TIME_SIGNATURE event.
     *
     * @param tick  absolute tick offset
     * @param track MIDI track index (0-based)
     * @param nn    numerator minus 1 (0–15, representing 1–16)
     * @param dd    denominator minus 1 (0–3, representing 2/4/8/16)
     * @return the time-signature event
     */
    public static MidiNoteEvent timeSignature(long tick, int track, int nn, int dd) {
        return new MidiNoteEvent(EventType.TIME_SIGNATURE, tick, track,
                0, 0, 0, 0,
                0, 0, 0, 0, nn, dd, 0, 0);
    }

    /**
     * Creates a KEY_SIGNATURE event.
     *
     * @param tick  absolute tick offset
     * @param track MIDI track index (0-based)
     * @param sf    sharp/flat count offset by 7 (0=7 flats … 7=C … 14=7 sharps)
     * @param mi    mode: 0 = major, 1 = minor
     * @return the key-signature event
     */
    public static MidiNoteEvent keySignature(long tick, int track, int sf, int mi) {
        return new MidiNoteEvent(EventType.KEY_SIGNATURE, tick, track,
                0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, sf, mi);
    }

    /** Returns the event type. */
    public EventType getEventType() { return eventType; }

    /**
     * Returns the absolute tick offset from the start of the sequence.
     *
     * @see #getOnset()
     */
    public long getTick() { return tick; }

    /**
     * Returns the absolute onset time in ticks.
     * This is an alias for {@link #getTick()} provided for backward compatibility
     * with the Moonbeam tokenizer ({@link org.almostrealism.studio.midi.MidiTokenizer}).
     */
    public long getOnset() { return tick; }

    /** Returns the MIDI track index (0-based). */
    public int getTrack() { return track; }

    /** Returns the MIDI channel (0–15). */
    public int getChannel() { return channel; }

    /** Returns the MIDI pitch number (0–127; NOTE events only). */
    public int getPitch() { return pitch; }

    /** Returns the MIDI velocity (0–127; NOTE events only). */
    public int getVelocity() { return velocity; }

    /**
     * Returns the note duration in ticks (NOTE events only).
     *
     * @see #getDuration()
     */
    public long getDurationTicks() { return durationTicks; }

    /**
     * Returns the note duration in ticks.
     * This is an alias for {@link #getDurationTicks()} provided for backward
     * compatibility with the Moonbeam tokenizer ({@link org.almostrealism.studio.midi.MidiTokenizer}).
     */
    public long getDuration() { return durationTicks; }

    /** Returns the GM program number (PATCH_CHANGE events; also used for backward-compat NOTE events). */
    public int getPatch() { return patch; }

    /** Returns the CC controller number (CONTROL_CHANGE events only). */
    public int getController() { return controller; }

    /** Returns the CC value (CONTROL_CHANGE events only). */
    public int getCcValue() { return ccValue; }

    /** Returns the BPM value (SET_TEMPO events only). */
    public int getBpm() { return bpm; }

    /** Returns the time-signature numerator minus 1 (TIME_SIGNATURE events only). */
    public int getNn() { return nn; }

    /** Returns the time-signature denominator minus 1 (TIME_SIGNATURE events only). */
    public int getDd() { return dd; }

    /** Returns the key-signature sharps/flats offset (KEY_SIGNATURE events only). */
    public int getSf() { return sf; }

    /** Returns the key-signature mode: 0 = major, 1 = minor (KEY_SIGNATURE events only). */
    public int getMi() { return mi; }

    /**
     * Returns the MIDI instrument number for NOTE events.
     *
     * <p>Returns {@link #DRUM_INSTRUMENT} (128) when {@link #getChannel()} equals
     * {@link #DRUM_CHANNEL} (9), otherwise returns {@link #getPatch()}. This
     * matches the convention used by {@link org.almostrealism.studio.midi.MidiTokenizer} and {@link org.almostrealism.music.midi.MidiFileReader}.</p>
     */
    public int getInstrument() {
        return channel == DRUM_CHANNEL ? DRUM_INSTRUMENT : patch;
    }

    /**
     * Returns the octave derived from pitch ({@code pitch / 12}).
     * Meaningful for NOTE events only.
     */
    public int getOctave() { return pitch / 12; }

    /**
     * Returns the pitch class derived from pitch ({@code pitch % 12}).
     * Meaningful for NOTE events only.
     */
    public int getPitchClass() { return pitch % 12; }

    @Override
    public int compareTo(MidiNoteEvent other) {
        int cmp = Long.compare(this.tick, other.tick);
        if (cmp != 0) return cmp;
        cmp = this.eventType.compareTo(other.eventType);
        if (cmp != 0) return cmp;
        return Integer.compare(this.pitch, other.pitch);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MidiNoteEvent)) return false;
        MidiNoteEvent o = (MidiNoteEvent) obj;
        return eventType == o.eventType && tick == o.tick && track == o.track
                && channel == o.channel && pitch == o.pitch && velocity == o.velocity
                && durationTicks == o.durationTicks && patch == o.patch
                && controller == o.controller && ccValue == o.ccValue
                && bpm == o.bpm && nn == o.nn && dd == o.dd && sf == o.sf && mi == o.mi;
    }

    @Override
    public int hashCode() {
        int h = eventType.hashCode();
        h = 31 * h + Long.hashCode(tick);
        h = 31 * h + track;
        h = 31 * h + channel;
        h = 31 * h + pitch;
        h = 31 * h + velocity;
        h = 31 * h + Long.hashCode(durationTicks);
        h = 31 * h + patch;
        h = 31 * h + controller;
        h = 31 * h + ccValue;
        h = 31 * h + bpm;
        h = 31 * h + nn;
        h = 31 * h + dd;
        h = 31 * h + sf;
        h = 31 * h + mi;
        return h;
    }

    @Override
    public String toString() {
        return String.format("MidiNoteEvent{type=%s, tick=%d, track=%d}", eventType, tick, track);
    }
}
