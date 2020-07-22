package org.almostrealism.algebra;

import org.almostrealism.math.Hardware;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.Producer;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

public class Pair implements MemWrapper {
	private cl_mem mem;

	public Pair() {
		mem = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
				CL.CL_MEM_READ_WRITE,2 * Sizeof.cl_double,
				null, null);
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

	@Override
	public cl_mem getMem() { return mem; }

	@Override
	public void destroy() {
		if (mem == null) return;
		CL.clReleaseMemObject(mem);
		mem = null;
	}

	@Override
	public void finalize() throws Throwable {
		destroy();
	}

	public void setMem(double[] source) {
		setMem(0, source, 0, 2);
	}

	private void setMem(double[] source, int offset) {
		setMem(0, source, offset, 2);
	}

	private void setMem(int offset, double[] source, int srcOffset, int length) {
		Pointer src = Pointer.to(source).withByteOffset(srcOffset* Sizeof.cl_double);
		CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
				offset * Sizeof.cl_double, length * Sizeof.cl_double,
				src, 0, null, null);
	}

	private void setMem(int offset, Pair src, int srcOffset, int length) {
		CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.mem, this.mem,
				srcOffset * Sizeof.cl_double,
				offset * Sizeof.cl_double,length * Sizeof.cl_double,
				0,null,null);
	}

	private void getMem(int sOffset, double out[], int oOffset, int length) {
		Pointer dst = Pointer.to(out).withByteOffset(oOffset * Sizeof.cl_double);
		CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
				CL.CL_TRUE, sOffset * Sizeof.cl_double,
				length * Sizeof.cl_double, dst, 0,
				null, null);
	}

	private void getMem(double out[], int offset) { getMem(0, out, offset, 2); }

	// TODO  This could be faster by moving directly between cl_mems
	public static Pair fromMem(cl_mem mem, int sOffset, int length) {
		if (length != 1 && length != 2) {
			throw new IllegalArgumentException(String.valueOf(length));
		}

		double out[] = new double[length];
		Pointer dst = Pointer.to(out).withByteOffset(0);
		CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
				CL.CL_TRUE, sOffset * Sizeof.cl_double,
				length * Sizeof.cl_double, dst, 0,
				null, null);
		if (length == 1) {
			return new Pair(out[0], 0);
		} else {
			return new Pair(out[0], out[1]);
		}
	}

	public static Producer<Pair> empty() {
		return new Producer<Pair>() {
			@Override
			public Pair evaluate(Object[] args) {
				return new Pair();
			}

			@Override
			public void compact() { }
		};
	}
}
