package org.almostrealism.math;

import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.cl_mem;

public abstract class MemWrapperAdapter implements MemWrapper {
	private cl_mem mem;

	private MemWrapper delegateMem;
	private int delegateMemOffset;

	private static int sizeOf = Hardware.getLocalHardware().getNumberSize();

	protected void init() {
		if (delegateMem == null) {
			mem = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
					CL.CL_MEM_READ_WRITE, getMemLength() * sizeOf,
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
		if (Hardware.getLocalHardware().isDoublePrecision()) {
			Pointer src = Pointer.to(source).withByteOffset(srcOffset * sizeOf);
			CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
					offset * sizeOf, length * sizeOf,
					src, 0, null, null);
		} else {
			float f[] = new float[length];
			for (int i = 0; i < f.length; i++) f[i] = (float) source[srcOffset + i];
			Pointer src = Pointer.to(f).withByteOffset(0);
			CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
					offset * sizeOf, length * sizeOf,
					src, 0, null, null);
		}
	}

	protected static void setMem(cl_mem mem, int offset, MemWrapperAdapter src, int srcOffset, int length) {
		if (src.delegateMem == null) {
			try {
				CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.mem, mem,
						srcOffset * sizeOf,
						offset * sizeOf, length * sizeOf,
						0, null, null);
			} catch (CLException e) {
				throw e;
			}
		} else {
			setMem(mem, offset, (MemWrapperAdapter) src.delegateMem, src.delegateMemOffset + srcOffset, length);
		}
	}

	protected static void getMem(cl_mem mem, int sOffset, double out[], int oOffset, int length) {
		if (Hardware.getLocalHardware().isDoublePrecision()) {
			Pointer dst = Pointer.to(out).withByteOffset(oOffset * sizeOf);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * sizeOf,
					length * sizeOf, dst, 0,
					null, null);
		} else {
			float f[] = new float[length];
			Pointer dst = Pointer.to(f).withByteOffset(0);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * sizeOf,
					length * sizeOf, dst, 0,
					null, null);
			for (int i = 0; i < f.length; i++) out[oOffset + i] = f[i];
		}
	}

	protected static void getMem(cl_mem mem, double out[], int offset) { getMem(mem, 0, out, offset, out.length); }
}