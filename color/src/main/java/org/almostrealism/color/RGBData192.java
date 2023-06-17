/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PooledMem;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public class RGBData192 extends PackedCollection<RGBData192> implements RGB.Data {
	public static final int depth = 192;

	public RGBData192() {
		super(new TraversalPolicy(3), 0);
	}

	protected RGBData192(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(3), 0, delegate, delegateOffset);
	}

	@Override
	public void add(int i, double r) {
		double rgb[] = toArray();
		rgb[i] += r;
		setMem(rgb);
	}

	@Override
	public void scale(int i, double r) {
		double rgb[] = toArray();
		rgb[i] *= r;
		setMem(rgb);
	}

	/** Returns the sum of the components (not vector length). */
	@Override
	public double sum() {
		double rgb[] = toArray();
		return rgb[0] + rgb[1] + rgb[2];
	}

	public void read(ObjectInput in) throws IOException {
		double rgb[] = new double[3];
		rgb[0] = in.readDouble();
		rgb[1] = in.readDouble();
		rgb[2] = in.readDouble();
		setMem(rgb);
	}

	public void write(ObjectOutput out) throws IOException {
		double rgb[] = toArray();
		out.writeDouble(rgb[0]);
		out.writeDouble(rgb[1]);
		out.writeDouble(rgb[2]);
	}

	@Override
	public PooledMem getDefaultDelegate() { return RGBData192Pool.getLocal(); }

	public double[] toArray() {
		double d[] = new double[3];
		getMem(0, d, 0, 3);
		return d;
	}
}
