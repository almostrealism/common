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
 * Stack-based object pool for {@link Transform}.
 * 
 * @author jezek2
 */
public class TransformStackList extends StackList<Transform> {
	public Transform get(Transform tr) {
		Transform obj = get();
		obj.set(tr);
		return obj;
	}

	public Transform get(Mat3f mat) {
		Transform obj = get();
		obj.basis.set(mat);
		obj.origin.setPosition(0f, 0f, 0f);
		return obj;
	}

	@Override
	protected Transform create() {
		return new Transform();
	}
	
	@Override
	protected void copy(Transform dest, Transform src) {
		dest.set(src);
	}
	
}
