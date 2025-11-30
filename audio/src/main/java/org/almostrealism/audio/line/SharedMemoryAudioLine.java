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

public class SharedMemoryAudioLine implements AudioLine, ConsoleFeatures {
	public static final int controlSize = 8;
	public static final int READ_POSITION = 0;
	public static final int PASSTHROUGH_LEVEL = 2;

	private int cursor;
	private PackedCollection controls;
	private PackedCollection input;
	private PackedCollection output;

	public SharedMemoryAudioLine(String location) {
		this(createControls(location),
				createDestination(location, false),
				createDestination(location, true));
	}

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

	private static PackedCollection createControls(String location) {
		String shared = location + "_ctl";
		return createCollection(shared, controlSize);
	}

	private static PackedCollection createDestination(String location, boolean output) {
		String shared = location + (output ? "_out" : "_in");
		return createCollection(shared, BufferDefaults.defaultBufferSize);
	}

	private static PackedCollection createCollection(String file, int size) {
		ComputeContext<?> ctx = Hardware.getLocalHardware().getComputeContext();
		return ctx.getDataContext().sharedMemory(len -> file,
				() -> new PackedCollection(size));
	}
}
