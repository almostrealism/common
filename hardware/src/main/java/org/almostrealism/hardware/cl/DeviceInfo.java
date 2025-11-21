package org.almostrealism.hardware.cl;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_device_id;

/**
 * OpenCL device capabilities and hardware specifications.
 *
 * <p>Queries and stores device information from {@link org.jocl.cl_device_id}
 * including compute units, memory sizes, and work group limits.</p>
 *
 * <h2>Queried Properties</h2>
 *
 * <pre>{@code
 * DeviceInfo info = new DeviceInfo(device);
 *
 * long cores = info.getCores();              // CL_DEVICE_MAX_COMPUTE_UNITS
 * long clock = info.getClockMhz();           // CL_DEVICE_MAX_CLOCK_FREQUENCY
 * long globalMem = info.getGlobalMem();      // CL_DEVICE_GLOBAL_MEM_SIZE
 * long localMem = info.getLocalMem();        // CL_DEVICE_LOCAL_MEM_SIZE
 * long maxAlloc = info.getMaxAlloc();        // CL_DEVICE_MAX_MEM_ALLOC_SIZE
 * long workGroup = info.getWorkGroupSize();  // CL_DEVICE_MAX_WORK_GROUP_SIZE
 * }</pre>
 *
 * @see CLDataContext#getMainDeviceInfo()
 * @see CLDataContext#getKernelDeviceInfo()
 */
public class DeviceInfo {
	/** Number of compute units (cores) on the device. */
	private long cores;

	/** Maximum clock frequency in MHz. */
	private long clockMhz;

	/** Total global memory size in bytes. */
	private long globalMem;

	/** Total local memory size in bytes per compute unit. */
	private long localMem;

	/** Maximum memory allocation size in bytes. */
	private long maxAlloc;

	/** Maximum work group size (number of work items). */
	private long workGroupSize;

	/** Maximum number of work item dimensions supported. */
	private long maxWorkItemDimensions;

	/** Maximum number of constant arguments for kernels. */
	private long maxConstantArgs;

	/**
	 * Creates a DeviceInfo by querying the specified OpenCL device.
	 *
	 * @param device the OpenCL device to query
	 */
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

	/**
	 * Creates a DeviceInfo with the specified values.
	 *
	 * @param cores                 number of compute units
	 * @param clockMhz              maximum clock frequency in MHz
	 * @param globalMem             total global memory size in bytes
	 * @param localMem              total local memory size in bytes per compute unit
	 * @param maxAlloc              maximum memory allocation size in bytes
	 * @param workGroupSize         maximum work group size
	 * @param maxWorkItemDimensions maximum number of work item dimensions
	 * @param maxConstantArgs       maximum number of constant arguments
	 */
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

	/**
	 * Returns the number of compute units (cores) on the device.
	 *
	 * @return the compute unit count
	 */
	public long getCores() {
		return cores;
	}

	/**
	 * Returns the maximum clock frequency in MHz.
	 *
	 * @return the maximum clock frequency
	 */
	public long getClockMhz() {
		return clockMhz;
	}

	/**
	 * Returns the total global memory size in bytes.
	 *
	 * @return the global memory size
	 */
	public long getGlobalMem() {
		return globalMem;
	}

	/**
	 * Returns the total local memory size in bytes per compute unit.
	 *
	 * @return the local memory size
	 */
	public long getLocalMem() {
		return localMem;
	}

	/**
	 * Returns the maximum memory allocation size in bytes.
	 *
	 * @return the maximum allocation size
	 */
	public long getMaxAlloc() {
		return maxAlloc;
	}

	/**
	 * Returns the maximum work group size (number of work items).
	 *
	 * @return the maximum work group size
	 */
	public long getWorkGroupSize() {
		return workGroupSize;
	}

	/**
	 * Returns the maximum number of work item dimensions supported.
	 *
	 * @return the maximum work item dimensions
	 */
	public long getMaxWorkItemDimensions() {
		return maxWorkItemDimensions;
	}

	/**
	 * Returns the maximum number of constant arguments for kernels.
	 *
	 * @return the maximum constant arguments
	 */
	public long getMaxConstantArgs() {
		return maxConstantArgs;
	}

	/**
	 * Reads a long value from an OpenCL device property.
	 *
	 * @param device the OpenCL device to query
	 * @param param  the OpenCL device info parameter constant
	 * @return the property value as a long
	 */
	private static long readLong(cl_device_id device, int param) {
		long[] value = new long[1];
		CL.clGetDeviceInfo(device, param, Sizeof.cl_long, Pointer.to(value), null);
		return value[0];
	}
}
