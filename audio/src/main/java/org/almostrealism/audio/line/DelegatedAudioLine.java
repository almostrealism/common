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

package org.almostrealism.audio.line;

import io.almostrealism.relation.Delegated;
import org.almostrealism.collect.PackedCollection;

public class DelegatedAudioLine implements AudioLine, Delegated<AudioLine> {
	private InputLine inputDelegate;
	private OutputLine outputDelegate;
	private final int bufferSize;

	private double passthrough;

	public DelegatedAudioLine() { this(null, BufferDefaults.defaultBufferSize); }

	public DelegatedAudioLine(AudioLine line, int bufferSize) {
		this(line, line, bufferSize);
	}

	public DelegatedAudioLine(InputLine inputDelegate,
							  OutputLine outputDelegate,
							  int bufferSize) {
		this.inputDelegate = inputDelegate;
		this.outputDelegate = outputDelegate;
		this.bufferSize = bufferSize;
	}

	@Override
	public AudioLine getDelegate() {
		return outputDelegate == inputDelegate ? (AudioLine) outputDelegate : null;
	}

	public void setDelegate(AudioLine delegate) {
		setInputDelegate(delegate);
		setOutputDelegate(delegate);
		updateDelegateSettings();
	}

	public void setInputDelegate(InputLine delegate) {
		this.inputDelegate = delegate;
	}

	public void setOutputDelegate(OutputLine delegate) {
		this.outputDelegate = delegate;
	}

	@Override
	public int getWritePosition() {
		return inputDelegate == null ? 0 : inputDelegate.getWritePosition();
	}

	@Override
	public int getReadPosition() {
		return outputDelegate == null ? 0 : outputDelegate.getReadPosition();
	}

	@Override
	public int getBufferSize() {
		if (outputDelegate != null && bufferSize != outputDelegate.getBufferSize()) {
			throw new UnsupportedOperationException();
		} else if (inputDelegate != null && bufferSize != inputDelegate.getBufferSize()) {
			throw new UnsupportedOperationException();
		}

		return bufferSize;
	}

	@Override
	public void setPassthroughLevel(double level) {
		this.passthrough = level;
		updateDelegateSettings();
	}

	@Override
	public double getPassthroughLevel() { return passthrough; }

	protected void updateDelegateSettings() {
		if (getDelegate() != null) {
			getDelegate().setPassthroughLevel(getPassthroughLevel());
		}
	}

	@Override
	public void read(PackedCollection sample) {
		if (inputDelegate != null) {
			inputDelegate.read(sample);
		}
	}

	@Override
	public void write(PackedCollection sample) {
		if (outputDelegate != null) {
			outputDelegate.write(sample);
		}
	}
}
