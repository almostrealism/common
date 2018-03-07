/*
 * Copyright 2018 Michael Murray
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

import org.almostrealism.algebra.Mat3f;

/**
 * Stack-based object pool for {@link org.almostrealism.algebra.Mat3f}.
 * 
 * @author jezek2
 */
public class MatrixStackList extends StackList<Mat3f> {
	public Mat3f get(Mat3f mat) {
		Mat3f obj = get();
		obj.set(mat);
		return obj;
	}
	
	@Override
	protected Mat3f create() {
		return new Mat3f();
	}

	@Override
	protected void copy(Mat3f dest, Mat3f src) {
		dest.set(src);
	}
}
