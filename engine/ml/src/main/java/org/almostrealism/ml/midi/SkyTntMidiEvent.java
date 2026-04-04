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

/**
 * Represents a MIDI event in the SkyTNT V2 tokenization scheme.
 *
 * <p>Unlike {@link MidiNoteEvent}, which models only note-on/off events,
 * this class supports all six SkyTNT V2 event types: {@link EventType#NOTE},
 * {@link EventType#PATCH_CHANGE}, {@link EventType#CONTROL_CHANGE},
 * {@link EventType#SET_TEMPO}, {@link EventType#TIME_SIGNATURE}, and
 * {@link EventType#KEY_SIGNATURE}.</p>
 *
 * <p>Timing is stored as an absolute tick offset from the beginning of the
 * sequence. The tokenizer converts ticks to the V2 time representation
 * (time1 coarse-beat delta + time2 fine 1/16th-beat position) using a
 * caller-supplied {@code ticksPerBeat} value.</p>
 *
 * <p>Use the static factory methods to construct events of each type:</p>
 * <pre>{@code
 * SkyTntMidiEvent note = SkyTntMidiEvent.note(tick, track, channel, pitch, velocity, durationTicks);
 * SkyTntMidiEvent tempo = SkyTntMidiEvent.setTempo(tick, track, 120);
 * }</pre>
 *
 * @see SkyTntTokenizerV2
 */
public class SkyTntMidiEvent implements Comparable<SkyTntMidiEvent> {

    /**
     * The six SkyTNT V2 event types, corresponding to token IDs 3–8
     * in the flat vocabulary.
     */
    public enum EventType {
        /** Standard MIDI note (note-on + duration). */
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

    /** MIDI channel (note, patch_change, control_change). */
    private final int channel;

    /** MIDI pitch number, 0–127 (note events only). */
    private final int pitch;

    /** MIDI velocity, 0–127 (note events only). */
    private final int velocity;

    /** Note duration in ticks (note events only). */
    private final long durationTicks;

    /** GM program number, 0–127 (patch_change events only). */
    private final int patch;

    /** CC controller number, 0–127 (control_change events only). */
    private final int controller;

    /** CC value, 0–127 (control_change events only). */
    private final int ccValue;

    /** Tempo in BPM, 0–383 (set_tempo events only). */
    private final int bpm;

    /** Time-signature numerator minus 1, 0–15 (time_signature events only). */
    private final int nn;

    /** Time-signature denominator minus 1, 0–3 (time_signature events only). */
    private final int dd;

    /** Key-signature sharps/flats offset, 0–14 (key_signature events only). */
    private final int sf;

    /** Key-signature mode: 0 = major, 1 = minor (key_signature events only). */
    private final int mi;

    /**
     * Full constructor — use the static factory methods instead.
     */
    private SkyTntMidiEvent(EventType eventType, long tick, int track,
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
    public static SkyTntMidiEvent note(long tick, int track, int channel,
                                        int pitch, int velocity, long durationTicks) {
        return new SkyTntMidiEvent(EventType.NOTE, tick, track,
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
    public static SkyTntMidiEvent patchChange(long tick, int track, int channel, int patch) {
        return new SkyTntMidiEvent(EventType.PATCH_CHANGE, tick, track,
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
    public static SkyTntMidiEvent controlChange(long tick, int track, int channel,
                                                 int controller, int ccValue) {
        return new SkyTntMidiEvent(EventType.CONTROL_CHANGE, tick, track,
                channel, 0, 0, 0,
                0, controller, ccValue, 0, 0, 0, 0, 0);
    }

    /**
     * Creates a SET_TEMPO event.
     *
     * @param tick  absolute tick offset
     * @param track MIDI track index (0-based)
     * @param bpm   tempo in beats per minute (clamped to 0–383)
     * @return the tempo event
     */
    public static SkyTntMidiEvent setTempo(long tick, int track, int bpm) {
        return new SkyTntMidiEvent(EventType.SET_TEMPO, tick, track,
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
    public static SkyTntMidiEvent timeSignature(long tick, int track, int nn, int dd) {
        return new SkyTntMidiEvent(EventType.TIME_SIGNATURE, tick, track,
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
    public static SkyTntMidiEvent keySignature(long tick, int track, int sf, int mi) {
        return new SkyTntMidiEvent(EventType.KEY_SIGNATURE, tick, track,
                0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, sf, mi);
    }

    /** Returns the event type. */
    public EventType getEventType() { return eventType; }

    /** Returns the absolute tick offset. */
    public long getTick() { return tick; }

    /** Returns the MIDI track index. */
    public int getTrack() { return track; }

    /** Returns the MIDI channel (note / patch_change / control_change). */
    public int getChannel() { return channel; }

    /** Returns the MIDI pitch (note events only). */
    public int getPitch() { return pitch; }

    /** Returns the MIDI velocity (note events only). */
    public int getVelocity() { return velocity; }

    /** Returns the note duration in ticks (note events only). */
    public long getDurationTicks() { return durationTicks; }

    /** Returns the GM program number (patch_change events only). */
    public int getPatch() { return patch; }

    /** Returns the CC controller number (control_change events only). */
    public int getController() { return controller; }

    /** Returns the CC value (control_change events only). */
    public int getCcValue() { return ccValue; }

    /** Returns the BPM value (set_tempo events only). */
    public int getBpm() { return bpm; }

    /** Returns the time-signature numerator minus 1 (time_signature events only). */
    public int getNn() { return nn; }

    /** Returns the time-signature denominator minus 1 (time_signature events only). */
    public int getDd() { return dd; }

    /** Returns the key-signature sharps/flats offset (key_signature events only). */
    public int getSf() { return sf; }

    /** Returns the key-signature mode: 0 = major, 1 = minor (key_signature events only). */
    public int getMi() { return mi; }

    @Override
    public int compareTo(SkyTntMidiEvent other) {
        int cmp = Long.compare(this.tick, other.tick);
        if (cmp != 0) return cmp;
        return this.eventType.compareTo(other.eventType);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SkyTntMidiEvent)) return false;
        SkyTntMidiEvent o = (SkyTntMidiEvent) obj;
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
        return String.format("SkyTntMidiEvent{type=%s, tick=%d, track=%d}", eventType, tick, track);
    }
}
