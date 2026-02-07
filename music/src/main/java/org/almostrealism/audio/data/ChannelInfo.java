/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.data;

import java.util.Objects;

public class ChannelInfo {
	private final int patternChannel;
	private final Type type;
	private final Voicing voicing;
	private final StereoChannel audioChannel;

	public ChannelInfo(int patternChannel) {
		this(patternChannel, Type.PATTERN, null, null);
	}

	public ChannelInfo(int patternChannel, StereoChannel stereoChannel) {
		this(patternChannel, Type.PATTERN, stereoChannel);
	}

	public ChannelInfo(Voicing voicing, StereoChannel stereoChannel) {
		this(-1, voicing, stereoChannel);
	}

	public ChannelInfo(int patternChannel, Voicing voicing, StereoChannel stereoChannel) {
		this(patternChannel, Type.PATTERN, voicing, stereoChannel);
	}

	public ChannelInfo(int patternChannel, Type type, StereoChannel stereoChannel) {
		this(patternChannel, type, Voicing.MAIN, stereoChannel);
	}

	public ChannelInfo(int patternChannel, Type type, Voicing voicing, StereoChannel audioChannel) {
		this.patternChannel = patternChannel;
		this.type = type;
		this.voicing = voicing;
		this.audioChannel = audioChannel;
	}

	public int getPatternChannel() { return patternChannel; }

	public Type getType() { return type; }

	public Voicing getVoicing() { return voicing; }

	public StereoChannel getAudioChannel() { return audioChannel; }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ChannelInfo that)) return false;
		return getPatternChannel() == that.getPatternChannel() &&
				getType() == that.getType() &&
				getVoicing() == that.getVoicing() &&
				getAudioChannel() == that.getAudioChannel();
	}

	@Override
	public int hashCode() {
		return Objects.hash(getPatternChannel(), getVoicing());
	}

	public enum Voicing {
		MAIN, WET
	}

	public enum StereoChannel {
		LEFT, RIGHT;

		public int getIndex() {
			return this == LEFT ? 0 : 1;
		}
	}

	public enum Type {
		PATTERN, RISE
	}
}
