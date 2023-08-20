package org.almostrealism.hardware.metal;

public class MTLComputePipelineState extends MTLObject {
	public MTLComputePipelineState(long nativePointer) {
		super(nativePointer);
	}

	public void release() {
		MTL.releaseComputePipelineState(getNativePointer());
	}
}
