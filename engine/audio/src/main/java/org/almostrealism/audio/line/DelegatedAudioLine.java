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

/**
 * A delegating implementation of {@link AudioLine} that forwards input and output operations
 * to separate underlying lines. This enables flexible composition where input and output
 * can be routed to different destinations, or switched dynamically at runtime.
 * <p>
 * The delegate pattern allows for layering additional behavior (logging, monitoring, etc.)
 * or routing audio through different hardware or virtual devices without modifying the
 * core processing logic.
 * </p>
 *
 * @see AudioLine
 * @see InputLine
 * @see OutputLine
 */
public class DelegatedAudioLine implements AudioLine, Delegated<AudioLine> {
	/** The input line used for reading audio data; may be null if no input is configured. */
	private InputLine inputDelegate;

	/** The output line used for writing audio data; may be null if no output is configured. */
	private OutputLine outputDelegate;

	/** Nominal buffer size in frames reported by {@link #getBufferSize()}. */
	private final int bufferSize;

	/** Current passthrough level (0.0–1.0) applied to audio passing through this line. */
	private double passthrough;

	/** Creates a DelegatedAudioLine with no delegate and the default buffer size. */
	public DelegatedAudioLine() { this(null, BufferDefaults.defaultBufferSize); }

	/**
	 * Creates a DelegatedAudioLine backed by a single AudioLine for both input and output.
	 *
	 * @param line       the AudioLine to use for both input and output
	 * @param bufferSize the nominal buffer size in frames
	 */
	public DelegatedAudioLine(AudioLine line, int bufferSize) {
		this(line, line, bufferSize);
	}

	/**
	 * Creates a DelegatedAudioLine with separate input and output delegates.
	 *
	 * @param inputDelegate  the line to delegate read operations to
	 * @param outputDelegate the line to delegate write operations to
	 * @param bufferSize     the nominal buffer size in frames
	 */
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

	/**
	 * Sets both the input and output delegates to the given AudioLine and propagates settings.
	 *
	 * @param delegate the AudioLine to use for both input and output
	 */
	public void setDelegate(AudioLine delegate) {
		setInputDelegate(delegate);
		setOutputDelegate(delegate);
		updateDelegateSettings();
	}

	/** Returns the delegate used for read (input) operations, or null if none is configured. */
	public InputLine getInputDelegate() {
		return inputDelegate;
	}

	/**
	 * Sets the input delegate used for read operations.
	 *
	 * @param delegate the new input delegate
	 */
	public void setInputDelegate(InputLine delegate) {
		this.inputDelegate = delegate;
	}

	/** Returns the delegate used for write (output) operations, or null if none is configured. */
	public OutputLine getOutputDelegate() {
		return outputDelegate;
	}

	/**
	 * Sets the output delegate used for write operations.
	 *
	 * @param delegate the new output delegate
	 */
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

	/**
	 * Propagates the current passthrough level to the delegate if it provides a single unified line.
	 */
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
