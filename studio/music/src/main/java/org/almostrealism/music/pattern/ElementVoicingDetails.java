/*
 * Copyright 2024 Michael Murray
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

import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.tone.KeyPosition;

import java.util.Objects;

/**
 * Context information for rendering a pattern element at a specific voicing.
 *
 * <p>{@code ElementVoicingDetails} encapsulates all the information needed to
 * render a single note from a pattern element:</p>
 * <ul>
 *   <li><strong>voicing</strong>: MAIN or WET signal path</li>
 *   <li><strong>stereoChannel</strong>: LEFT or RIGHT audio channel</li>
 *   <li><strong>melodic</strong>: Whether this is pitched (melodic) or unpitched (percussive)</li>
 *   <li><strong>target</strong>: The target key position for melodic content</li>
 *   <li><strong>position</strong>: Current note position in measures</li>
 *   <li><strong>nextNotePosition</strong>: Position of the following note (for duration calculation)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>Created by {@link NoteAudioContext#createVoicingDetails} during the note destination
 * generation process in {@link ScaleTraversalStrategy#getNoteDestinations}.</p>
 *
 * <p>Used by {@link PatternElement#getNoteAudio} to retrieve the appropriate audio
 * sample with the correct pitch, duration, and channel routing.</p>
 *
 * @see PatternElement#getNoteAudio
 * @see NoteAudioContext
 * @see ScaleTraversalStrategy
 *
 * @author Michael Murray
 */
public class ElementVoicingDetails {
	/** The signal path voicing (MAIN or WET). */
	private ChannelInfo.Voicing voicing;

	/** The stereo channel (LEFT or RIGHT). */
	private ChannelInfo.StereoChannel stereoChannel;

	/** Whether this element is melodic (pitched). */
	private boolean melodic;

	/** The key position target for melodic content. */
	private KeyPosition<?> target;

	/** The current note position in measures. */
	private double position;

	/** The position of the next note (for duration calculation). */
	private double nextNotePosition;

	/** Creates an {@code ElementVoicingDetails} in the MAIN voicing with all other defaults. */
	public ElementVoicingDetails() {
		this(ChannelInfo.Voicing.MAIN);
	}

	/**
	 * Creates an {@code ElementVoicingDetails} with the given voicing and all other defaults.
	 *
	 * @param voicing the signal path voicing
	 */
	public ElementVoicingDetails(ChannelInfo.Voicing voicing) {
		this(voicing, null, false, null, 0, 0);
	}

	/**
	 * Creates an {@code ElementVoicingDetails} with the given voicing, melodic flag, and target.
	 *
	 * @param voicing  the signal path voicing
	 * @param melodic  whether the element is melodic
	 * @param target   the key position target
	 */
	public ElementVoicingDetails(ChannelInfo.Voicing voicing, boolean melodic,
									 KeyPosition<?> target) {
		this(voicing, null, melodic, target, 0, 0);
	}

	/**
	 * Creates a fully specified {@code ElementVoicingDetails}.
	 *
	 * @param voicing          the signal path voicing
	 * @param stereoChannel    the stereo channel
	 * @param melodic          whether the element is melodic
	 * @param target           the key position target
	 * @param position         the current note position
	 * @param nextNotePosition the next note position
	 */
	public ElementVoicingDetails(ChannelInfo.Voicing voicing,
								 ChannelInfo.StereoChannel stereoChannel,
								 boolean melodic, KeyPosition<?> target,
								 double position, double nextNotePosition) {
		this.voicing = voicing;
		this.stereoChannel = stereoChannel;
		this.melodic = melodic;
		this.target = target;
		this.position = position;
		this.nextNotePosition = nextNotePosition;
	}

	/** Returns the signal path voicing. */
	public ChannelInfo.Voicing getVoicing() {
		return voicing;
	}

	/** Sets the signal path voicing. */
	public void setVoicing(ChannelInfo.Voicing voicing) {
		this.voicing = voicing;
	}

	/** Returns the stereo channel. */
	public ChannelInfo.StereoChannel getStereoChannel() {
		return stereoChannel;
	}

	/** Sets the stereo channel. */
	public void setStereoChannel(ChannelInfo.StereoChannel stereoChannel) {
		this.stereoChannel = stereoChannel;
	}

	/** Returns {@code true} if this element is melodic (pitched). */
	public boolean isMelodic() {
		return melodic;
	}

	/** Sets whether this element is melodic. */
	public void setMelodic(boolean melodic) {
		this.melodic = melodic;
	}

	/** Returns the key position target for melodic content. */
	public KeyPosition<?> getTarget() {
		return target;
	}

	/** Sets the key position target. */
	public void setTarget(KeyPosition<?> target) {
		this.target = target;
	}

	/** Returns the current note position in measures. */
	public double getPosition() {
		return position;
	}

	/** Sets the current note position. */
	public void setPosition(double position) {
		this.position = position;
	}

	/** Returns the position of the next note. */
	public double getNextNotePosition() {
		return nextNotePosition;
	}

	/** Sets the position of the next note. */
	public void setNextNotePosition(double nextNotePosition) {
		this.nextNotePosition = nextNotePosition;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ElementVoicingDetails that)) return false;
		return isMelodic() == that.isMelodic() &&
				Double.compare(getPosition(), that.getPosition()) == 0 &&
				Double.compare(getNextNotePosition(), that.getNextNotePosition()) == 0 &&
				getVoicing() == that.getVoicing() &&
				Objects.equals(getTarget(), that.getTarget());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getVoicing(), isMelodic(), getTarget(), getPosition(), getNextNotePosition());
	}
}
