/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Precision;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

public class MTLBuffer extends MTLObject {
	private Precision precision;

	public MTLBuffer(Precision precision, long nativePointer) {
		super(nativePointer);
		this.precision = precision;
	}

	public void setContents(FloatBuffer buf, int offset, int length) {
		if (precision == Precision.FP16) {
			MTL.setBufferContents16(getNativePointer(), buf, offset, length);
		} else {
			MTL.setBufferContents32(getNativePointer(), buf, offset, length);
		}
	}

	public void getContents(FloatBuffer buf, int offset, int length) {
		MTL.getBufferContents32(getNativePointer(), buf, offset, length);
	}

	public void setContents(DoubleBuffer buf, int offset, int length) {
		throw new UnsupportedOperationException("DoubleBuffer not supported");
	}

	public void getContents(DoubleBuffer buf, int offset, int length) {
		throw new UnsupportedOperationException("DoubleBuffer not supported");
	}

	public long length() {
		return MTL.bufferLength(getNativePointer());
	}

	public void release() {
		MTL.releaseBuffer(getNativePointer());
	}
}
