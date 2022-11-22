package org.almostrealism.hardware.cl;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_device_id;

public class DeviceInfo {
	private long cores;
	private long clockMhz;
	private long globalMem;
	private long localMem;
	private long maxAlloc;
	private long workGroupSize;
	private long maxWorkItemDimensions;
	private long maxConstantArgs;

	public DeviceInfo(cl_device_id device) {
		this(readLong(device, CL.CL_DEVICE_MAX_COMPUTE_UNITS),
				readLong(device, CL.CL_DEVICE_MAX_CLOCK_FREQUENCY),
				readLong(device, CL.CL_DEVICE_GLOBAL_MEM_SIZE),
				readLong(device, CL.CL_DEVICE_LOCAL_MEM_SIZE),
				readLong(device, CL.CL_DEVICE_MAX_MEM_ALLOC_SIZE),
				readLong(device, CL.CL_DEVICE_MAX_WORK_GROUP_SIZE),
				readLong(device, CL.CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS),
				readLong(device, CL.CL_DEVICE_MAX_CONSTANT_ARGS));
	}

	public DeviceInfo(long cores, long clockMhz, long globalMem, long localMem, long maxAlloc,
					  long workGroupSize, long maxWorkItemDimensions, long maxConstantArgs) {
		this.cores = cores;
		this.clockMhz = clockMhz;
		this.globalMem = globalMem;
		this.localMem = localMem;
		this.maxAlloc = maxAlloc;
		this.workGroupSize = workGroupSize;
		this.maxWorkItemDimensions = maxWorkItemDimensions;
		this.maxConstantArgs = maxConstantArgs;
	}

	public long getCores() {
		return cores;
	}

	public long getClockMhz() {
		return clockMhz;
	}

	public long getGlobalMem() {
		return globalMem;
	}

	public long getLocalMem() {
		return localMem;
	}

	public long getMaxAlloc() {
		return maxAlloc;
	}

	public long getWorkGroupSize() {
		return workGroupSize;
	}

	public long getMaxWorkItemDimensions() {
		return maxWorkItemDimensions;
	}

	public long getMaxConstantArgs() {
		return maxConstantArgs;
	}

	private static long readLong(cl_device_id device, int param) {
		long[] value = new long[1];
		CL.clGetDeviceInfo(device, param, Sizeof.cl_long, Pointer.to(value), null);
		return value[0];
	}
}
