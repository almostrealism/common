package org.almostrealism.math;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

public abstract class MemWrapperAdapter implements MemWrapper {
	private cl_mem mem;

	private MemWrapper delegateMem;
	private int delegateMemOffset;

	protected void init() {
		if (delegateMem == null) {
			mem = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
					CL.CL_MEM_READ_WRITE, getMemLength() * Sizeof.cl_double,
					null, null);
		}
	}

	@Override
	public cl_mem getMem() { return delegateMem == null ? mem : delegateMem.getMem(); }

	@Override
	public int getOffset() { return delegateMem == null ? 0 : delegateMemOffset; }

	@Override
	public void destroy() {
		if (mem == null) return;
		CL.clReleaseMemObject(mem);
		mem = null;
	}

	public void setMem(double[] source) {
		setMem(0, source, 0, getMemLength());
	}

	protected void setMem(double[] source, int offset) {
		setMem(0, source, offset, getMemLength());
	}

	protected void setMem(int offset, double[] source, int srcOffset, int length) {
		if (delegateMem == null) {
			setMem(mem, offset, source, srcOffset, length);
		} else {
			setMem(delegateMem.getMem(), delegateMemOffset + offset, source, srcOffset, length);
		}
	}

	protected void setMem(int offset, MemWrapperAdapter src, int srcOffset, int length) {
		if (delegateMem == null) {
			setMem(mem, offset, src, srcOffset, length);
		} else {
			setMem(delegateMem.getMem(), delegateMemOffset + offset, src, srcOffset, length);
		}
	}

	protected void getMem(int sOffset, double out[], int oOffset, int length) {
		if (delegateMem == null) {
			getMem(mem, sOffset, out, oOffset, length);
		} else {
			getMem(delegateMem.getMem(), delegateMemOffset + sOffset, out, oOffset, length);
		}
	}

	protected void getMem(double out[], int offset) { getMem(0, out, offset, getMemLength()); }

	@Override
	public void setDelegate(MemWrapper m, int offset) {
		this.delegateMem = m;
		this.delegateMemOffset = offset;
	}

	@Override
	public void finalize() throws Throwable {
		destroy();
	}

	protected static void setMem(cl_mem mem, double[] source, int offset) {
		setMem(mem, 0, source, offset, source.length);
	}

	protected static void setMem(cl_mem mem, int offset, double[] source, int srcOffset, int length) {
		Pointer src = Pointer.to(source).withByteOffset(srcOffset* Sizeof.cl_double);
		CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
				offset * Sizeof.cl_double, length * Sizeof.cl_double,
				src, 0, null, null);
	}

	protected static void setMem(cl_mem mem, int offset, MemWrapperAdapter src, int srcOffset, int length) {
		if (src.delegateMem == null) {
			CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.mem, mem,
					srcOffset * Sizeof.cl_double,
					offset * Sizeof.cl_double, length * Sizeof.cl_double,
					0, null, null);
		} else {
			CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(),
					src.delegateMem.getMem(), mem,
					(src.delegateMemOffset + srcOffset) * Sizeof.cl_double,
					offset * Sizeof.cl_double, length * Sizeof.cl_double,
					0, null, null);
		}
	}

	protected static void getMem(cl_mem mem, int sOffset, double out[], int oOffset, int length) {
		Pointer dst = Pointer.to(out).withByteOffset(oOffset * Sizeof.cl_double);
		CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
				CL.CL_TRUE, sOffset * Sizeof.cl_double,
				length * Sizeof.cl_double, dst, 0,
				null, null);
	}

	protected static void getMem(cl_mem mem, double out[], int offset) { getMem(mem, 0, out, offset, out.length); }
}
