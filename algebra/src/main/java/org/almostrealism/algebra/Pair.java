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

package org.almostrealism.algebra;

import io.almostrealism.relation.Producer;

import org.almostrealism.hardware.DynamicProducerForMemWrapper;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.mem.MemWrapperAdapter;
import org.almostrealism.hardware.PooledMem;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.cl_mem;

public class Pair extends MemWrapperAdapter {
	public Pair() {
		init();
	}

	protected Pair(MemWrapper delegate, int delegateOffset) {
		setDelegate(delegate, delegateOffset);
		init();
	}

	public Pair(double x, double y) {
		this();
		this.setMem(new double[] { x, y });
	}
	
	public Pair setX(double x) {
		double d1[] = new double[2];
		getMem(d1, 0);
		d1[0] = x;
		setMem(d1);
		return this;
	}

	public Pair setY(double y) {
		double d1[] = new double[2];
		getMem(d1, 0);
		d1[1] = y;
		setMem(d1);
		return this;
	}

	public Pair setA(double a) { this.setX(a); return this; }
	public Pair setB(double b) { this.setY(b); return this; }
	public Pair setLeft(double l) { this.setX(l); return this; }
	public Pair setRight(double r) { this.setY(r); return this; }
	public Pair setTheta(double t) { this.setX(t); return this; }
	public Pair setPhi(double p) { this.setY(p); return this; }

	public double getX() {
		double d1[] = new double[2];
		getMem(d1, 0);
		return d1[0];
	}

	public double getY() {
		double d1[] = new double[2];
		getMem(d1, 0);
		return d1[1];
	}

	public double getA() { return getX(); }
	public double getB() { return getY(); }
	public double getLeft() { return getX(); }
	public double getRight() { return getY(); }
	public double getTheta() { return getX(); }
	public double getPhi() { return getY(); }
	public double x() { return getX(); }
	public double y() { return getY(); }
	public double a() { return getX(); }
	public double b() { return getY(); }
	public double left() { return getX(); }
	public double right() { return getY(); }
	public double theta() { return getX(); }
	public double phi() { return getY(); }
	public double r() { return getX(); }
	public double i() { return getY(); }
	public double _1() { return getX(); }
	public double _2() { return getY(); }

	public Pair add(Pair p) {
		// TODO  Fast version
		double d1[] = new double[2];
		double d2[] = new double[2];
		getMem(d1, 0);
		p.getMem(d2, 0);
		return new Pair(d1[0] + d2[0], d1[1] + d2[1]);
	}

	public Pair subtract(Pair p) {
		// TODO  Fast version
		double d1[] = new double[2];
		double d2[] = new double[2];
		getMem(d1, 0);
		p.getMem(d2, 0);
		return new Pair(d1[0] - d2[0], d1[1] - d2[1]);
	}

	public Pair multiply(Pair p) {
		// TODO  Fast version
		double d1[] = new double[2];
		double d2[] = new double[2];
		getMem(d1, 0);
		p.getMem(d2, 0);
		return new Pair(d1[0] * d2[0], d1[1] * d2[1]);
	}

	// TODO  Unit test
	public Pair multiplyComplex(Pair c) {
		// TODO  Fast version
		double d1[] = new double[2];
		double d2[] = new double[2];
		getMem(d1, 0);
		c.getMem(d2, 0);
		double p = d1[0];
		double q = d1[1];
		double r = d2[0];
		double s = d2[1];
		return new Pair(p * r - q * s, p * s + q * r);
	}

	// TODO  Unit test
	public Pair invertComplex() {
		// TODO  Fast version
		double d1[] = new double[2];
		getMem(d1, 0);
		double p = d1[0];
		double q = d1[1];
		double sq = p * p + q * q;
		return new Pair(p / sq, -q / sq);
	}

	public Pair multiply(double d) {
		// TODO  Fast version
		double d1[] = new double[2];
		getMem(d1, 0);
		return new Pair(d1[0] * d, d1[1] * d);
	}

	public Pair divide(double d) {
		// TODO  Fast version
		double d1[] = new double[2];
		getMem(d1, 0);
		return new Pair(d1[0] / d, d1[1] / d);
	}

	// TODO  Unit test
	public Pair divideComplex(Pair c) {
		// TODO  Fast version
		return multiply(c.invertComplex());
	}

	public void multiplyBy(double d) {
		// TODO  Fast version
		double d1[] = new double[2];
		getMem(d1, 0);
		d1[0] *= d;
		d1[1] *= d;
		this.setMem(d1);
	}

	/**
	 * Returns an integer hash code value for this {@link Pair} obtained
	 * by adding both components and casting to an int.
	 */
	@Override
	public int hashCode() {
		return (int) (this.getX() + this.getY());
	}

	/**
	 * Returns true if and only if the object specified is a {@link Pair}
	 * with equal values as this {@link Pair}.
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Pair == false)
			return false;

		Pair pair = (Pair) obj;
		return pair.getX() == this.getX() && pair.getY() == this.getY();
	}

	@Override
	public int getMemLength() {
		return 2;
	}

	@Override
	public PooledMem getDefaultDelegate() { return PairPool.getLocal(); }

	/** @return A String representation of this Vector object. */
	@Override
	public String toString() {
		StringBuffer value = new StringBuffer();

		value.append("[");
		value.append(Defaults.displayFormat.format(getX()));
		value.append(", ");
		value.append(Defaults.displayFormat.format(getY()));
		value.append("]");


		return value.toString();
	}

	public static Producer<Pair> empty() {
		return new DynamicProducerForMemWrapper<>(Pair::new, PairBank::new);
	}

	// TODO  This could be faster by moving directly between cl_mems
	public static Pair fromMem(cl_mem mem, int sOffset, int length) {
		if (length != 1 && length != 2) {
			throw new IllegalArgumentException(String.valueOf(length));
		}

		if (Hardware.getLocalHardware().isDoublePrecision()) {
			double out[] = new double[length];
			Pointer dst = Pointer.to(out).withByteOffset(0);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * Hardware.getLocalHardware().getNumberSize(),
					length * Hardware.getLocalHardware().getNumberSize(), dst, 0,
					null, null);
			if (length == 1) {
				return new Pair(out[0], 0);
			} else {
				return new Pair(out[0], out[1]);
			}
		} else {
			float out[] = new float[length];
			Pointer dst = Pointer.to(out).withByteOffset(0);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * Hardware.getLocalHardware().getNumberSize(),
					length * Hardware.getLocalHardware().getNumberSize(), dst, 0,
					null, null);
			if (length == 1) {
				return new Pair(out[0], 0);
			} else {
				return new Pair(out[0], out[1]);
			}
		}
	}
}
