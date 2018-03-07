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

import org.almostrealism.algebra.Vector;

/**
 * Stack-based object pool for {@link Vector}.
 * 
 * @author jezek2
 */
public class VectorStackList extends StackList<Vector> {
	public Vector get(float x, float y, float z) {
		Vector v = get();
		v.setPosition(x, y, z);
		return v;
	}

	public Vector get(Vector vec) {
		Vector v = get();
		float f[] = vec.getPosition();
		v.setPosition(f[0], f[1], f[2]);
		return v;
	}

	@Override
	protected Vector create() {
		return new Vector();
	}

	@Override
	protected void copy(Vector dest, Vector src) {
		float f[] = src.getPosition();
		dest.setPosition(f[0], f[1], f[2]);
	}
}
