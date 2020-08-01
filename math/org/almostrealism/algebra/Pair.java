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

import org.almostrealism.math.Hardware;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.math.MemWrapperAdapter;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

public class Pair extends MemWrapperAdapter {
	public Pair() {
		init();
	}

	protected Pair(MemWrapper delegate, int delegateOffset) {
		setDelegate(delegate, delegateOffset);
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

	public Pair multiply(Pair p) {
		// TODO  Fast version
		double d1[] = new double[2];
		double d2[] = new double[2];
		getMem(d1, 0);
		p.getMem(d2, 0);
		return new Pair(d1[0] * d2[0], d1[1] * d2[1]);
	}

	public Pair multiply(double d) {
		// TODO  Fast version
		double d1[] = new double[2];
		getMem(d1, 0);
		return new Pair(d1[0] * d, d1[1] * d);
	}

	public void multiplyBy(double d) {
		// TODO  Fast version
		double d1[] = new double[2];
		getMem(d1, 0);
		d1[0] *= d;
		d1[1] *= d;
		this.setMem(d1);
	}

	@Override
	public int getMemLength() {
		return 2;
	}

	public static Producer<Pair> empty() {
		return new DynamicProducer<>(args -> new Pair());
	}
}
