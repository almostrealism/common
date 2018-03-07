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

/**
 * Stack-based object pool for {@link org.almostrealism.algebra.Vec4f}.
 * 
 * @author jezek2
 */
public class Vector4StackList extends StackList<Vec4f> {
	public Vec4f get(float x, float y, float z, float w) {
		Vec4f v = get();
		v.set(x, y, z, w);
		return v;
	}

	public Vec4f get(Vec4f vec) {
		Vec4f v = get();
		v.set(vec);
		return v;
	}

	@Override
	protected Vec4f create() {
		return new Vec4f();
	}

	@Override
	protected void copy(Vec4f dest, Vec4f src) {
		dest.set(src);
	}
}
