package org.almostrealism.hardware.metal;

public class MTLComputePipelineState extends MTLObject {
	public MTLComputePipelineState(long nativePointer) {
		super(nativePointer);
	}

	public int maxTotalThreadsPerThreadgroup() {
		return MTL.maxTotalThreadsPerThreadgroup(getNativePointer());
	}

	public int threadExecutionWidth() {
		return MTL.threadExecutionWidth(getNativePointer());
	}

	public void release() {
		MTL.releaseComputePipelineState(getNativePointer());
	}
}
