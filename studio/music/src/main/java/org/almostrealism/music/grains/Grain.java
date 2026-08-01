/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.music.grains;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.code.Memory;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Heap;

/**
 * A single audio grain used in granular synthesis.
 *
 * <p>A grain is a short audio excerpt defined by its start position within a source,
 * its duration, and its playback rate. It extends {@link PackedCollection} and stores
 * three values: {@code start}, {@code duration}, and {@code rate}.</p>
 *
 * @see GrainSet
 * @see GrainProcessor
 */
public class Grain extends PackedCollection {
	/** Creates an uninitialized grain with storage for three values. */
	public Grain() {
		super(3);
	}

	/**
	 * Creates a grain with the given start position, duration, and playback rate.
	 *
	 * @param start    the start position within the source audio, in seconds
	 * @param duration the duration of the grain, in seconds
	 * @param rate     the playback rate (1.0 = normal speed)
	 */
	public Grain(double start, double duration, double rate) {
		this();
		setStart(start);
		setDuration(duration);
		setRate(rate);
	}

	@JsonIgnore
	@Override
	public TraversalPolicy getShape() {
		return super.getShape();
	}

	@JsonIgnore
	@Override
	public int getMemLength() {
		return super.getMemLength();
	}

	@JsonIgnore
	@Override
	public int getAtomicMemLength() {
		return super.getAtomicMemLength();
	}

	@JsonIgnore
	@Override
	public int getOffset() {
		return super.getOffset();
	}

	@JsonIgnore
	@Override
	public int getDelegateOffset() { return super.getDelegateOffset(); }

	@JsonIgnore
	@Override
	public int getCount() { return super.getCount(); }

	@JsonIgnore
	@Override
	public Memory getMem() { return super.getMem(); }

	@JsonIgnore
	@Override
	public MemoryData getDelegate() { return super.getDelegate(); }

	@JsonIgnore
	@Override
	public MemoryData getRootDelegate() { return super.getRootDelegate(); }

	@JsonIgnore
	@Override
	public Heap getDefaultDelegate() { return super.getDefaultDelegate(); }

	public double getStart() { return toArray(0, 1)[0]; }
	public void setStart(double start) { setMem(0, start); }

	public double getDuration() { return toArray(1, 1)[0]; }
	public void setDuration(double duration) { setMem(1, duration); }

	public double getRate() { return toArray(2, 1)[0]; }
	public void setRate(double rate) { setMem(2, rate); }
}
