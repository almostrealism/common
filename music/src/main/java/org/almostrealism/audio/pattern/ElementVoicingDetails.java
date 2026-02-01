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

package org.almostrealism.audio.pattern;

import org.almostrealism.audio.data.ChannelInfo;
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
	private ChannelInfo.Voicing voicing;
	private ChannelInfo.StereoChannel stereoChannel;
	private boolean melodic;
	private KeyPosition<?> target;
	private double position;
	private double nextNotePosition;

	public ElementVoicingDetails() {
		this(ChannelInfo.Voicing.MAIN);
	}

	public ElementVoicingDetails(ChannelInfo.Voicing voicing) {
		this(voicing, null, false, null, 0, 0);
	}

	public ElementVoicingDetails(ChannelInfo.Voicing voicing, boolean melodic,
									 KeyPosition<?> target) {
		this(voicing, null, melodic, target, 0, 0);
	}

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

	public ChannelInfo.Voicing getVoicing() {
		return voicing;
	}

	public void setVoicing(ChannelInfo.Voicing voicing) {
		this.voicing = voicing;
	}

	public ChannelInfo.StereoChannel getStereoChannel() {
		return stereoChannel;
	}

	public void setStereoChannel(ChannelInfo.StereoChannel stereoChannel) {
		this.stereoChannel = stereoChannel;
	}

	public boolean isMelodic() {
		return melodic;
	}

	public void setMelodic(boolean melodic) {
		this.melodic = melodic;
	}

	public KeyPosition<?> getTarget() {
		return target;
	}

	public void setTarget(KeyPosition<?> target) {
		this.target = target;
	}

	public double getPosition() {
		return position;
	}

	public void setPosition(double position) {
		this.position = position;
	}

	public double getNextNotePosition() {
		return nextNotePosition;
	}

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
