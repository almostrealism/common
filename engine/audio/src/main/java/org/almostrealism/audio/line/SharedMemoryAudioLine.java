/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.ComputeContext;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.ConsoleFeatures;

/**
 * An {@link AudioLine} implementation that uses shared memory buffers for inter-process
 * audio communication. This enables audio data to be passed between separate processes
 * or threads efficiently without copying, useful for distributed audio processing architectures.
 * <p>
 * The shared memory layout includes:
 * </p>
 * <ul>
 *   <li>Control buffer: stores read position and passthrough level</li>
 *   <li>Input buffer: receives audio data for reading</li>
 *   <li>Output buffer: provides audio data for writing</li>
 * </ul>
 *
 * @see AudioLine
 * @see PackedCollection
 */
public class SharedMemoryAudioLine implements AudioLine, ConsoleFeatures {
	/** Number of elements in the control buffer; must accommodate all control indices. */
	public static final int controlSize = 8;

	/** Index into the control buffer that stores the hardware read position. */
	public static final int READ_POSITION = 0;

	/** Index into the control buffer that stores the passthrough level. */
	public static final int PASSTHROUGH_LEVEL = 2;

	/** Current write cursor position within the output buffer. */
	private int cursor;

	/** Shared-memory control buffer holding the read position and passthrough level. */
	private PackedCollection controls;

	/** Shared-memory input buffer from which audio is read. */
	private PackedCollection input;

	/** Shared-memory output buffer to which audio is written. */
	private PackedCollection output;

	/**
	 * Creates a SharedMemoryAudioLine backed by shared-memory buffers at the given location.
	 *
	 * @param location shared-memory key or path prefix used to locate the control and data buffers
	 */
	public SharedMemoryAudioLine(String location) {
		this(createControls(location),
				createDestination(location, false),
				createDestination(location, true));
	}

	/**
	 * Creates a SharedMemoryAudioLine backed by the provided control, input, and output buffers.
	 *
	 * @param controls the control buffer holding read position and passthrough level
	 * @param input    the input buffer from which audio is read
	 * @param output   the output buffer to which audio is written
	 */
	public SharedMemoryAudioLine(PackedCollection controls,
								 PackedCollection input,
								 PackedCollection output) {
		this.controls = controls;
		this.input = input;
		this.output = output;
	}

	@Override
	public int getWritePosition() { return cursor; }

	@Override
	public int getReadPosition() {
		return Math.toIntExact((long) controls.toDouble(READ_POSITION));
	}

	@Override
	public void setPassthroughLevel(double level) {
		controls.setMem(PASSTHROUGH_LEVEL, level);
	}

	@Override
	public double getPassthroughLevel() {
		return controls.toDouble(PASSTHROUGH_LEVEL);
	}

	@Override
	public int getBufferSize() { return output.getMemLength(); }

	@Override
	public void read(PackedCollection sample) {
		if (sample.getMemLength() > input.getMemLength() - cursor) {
			throw new IllegalArgumentException("Sample is too large for source");
		}

		sample.setMem(0, input, cursor, sample.getMemLength());
	}

	@Override
	public void write(PackedCollection sample) {
		if (sample.getMemLength() > output.getMemLength() - cursor) {
			throw new IllegalArgumentException("Sample is too large for destination");
		}

		output.setMem(cursor, sample);
		cursor = (cursor + sample.getMemLength()) % output.getMemLength();
	}

	@Override
	public void destroy() {
		AudioLine.super.destroy();
		controls.destroy();
		input.destroy();
		output.destroy();
		controls = null;
		input = null;
		output = null;
	}

	/**
	 * Creates the shared-memory control buffer at the given location.
	 *
	 * @param location shared-memory key prefix
	 * @return a PackedCollection backed by the control shared-memory segment
	 */
	private static PackedCollection createControls(String location) {
		String shared = location + "_ctl";
		return createCollection(shared, controlSize);
	}

	/**
	 * Creates the shared-memory input or output data buffer at the given location.
	 *
	 * @param location shared-memory key prefix
	 * @param output   true to create the output buffer; false to create the input buffer
	 * @return a PackedCollection backed by the appropriate shared-memory segment
	 */
	private static PackedCollection createDestination(String location, boolean output) {
		String shared = location + (output ? "_out" : "_in");
		return createCollection(shared, BufferDefaults.defaultBufferSize);
	}

	/**
	 * Creates a PackedCollection backed by a named shared-memory segment of the given size.
	 *
	 * @param file the shared-memory file name or key
	 * @param size number of elements in the collection
	 * @return the shared-memory-backed PackedCollection
	 */
	private static PackedCollection createCollection(String file, int size) {
		ComputeContext<?> ctx = Hardware.getLocalHardware().getComputeContext();
		return ctx.getDataContext().sharedMemory(len -> file,
				() -> new PackedCollection(size));
	}
}
