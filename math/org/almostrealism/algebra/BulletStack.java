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
 * Per-thread stack based object pools for tools types.
 * 
 * @see StackList
 * 
 * @author jezek2
 */
public class BulletStack {
	private BulletStack() { }
	
	private static final ThreadLocal<BulletStack> threadLocal = new ThreadLocal<BulletStack>() {
		@Override
		protected BulletStack initialValue() {
			return new BulletStack();
		}
	};
	
	/**
	 * Returns stack for current thread, or create one if not present.
	 * 
	 * @return stack
	 */
	public static BulletStack get() {
		return threadLocal.get();
	}
	
	// tools math:
	public final VectorStackList vectors = new VectorStackList();
	public final TransformStackList transforms = new TransformStackList();
	public final MatrixStackList matrices = new MatrixStackList();
	
	// others:
	public final Vector4StackList vectors4 = new Vector4StackList();
	public final QuatStackList quats = new QuatStackList();

	public final ArrayPool<float[]> floatArrays = new ArrayPool<>(float.class);
	
	/**
	 * Pushes Vector3f, Transform and Matrix3f stacks.
	 */
	public void pushCommonMath() {
		vectors.push();
		transforms.push();
		matrices.push();
	}
	
	/**
	 * Pops Vector3f, Transform and Matrix3f stacks.
	 */
	public void popCommonMath() {
		vectors.pop();
		transforms.pop();
		matrices.pop();
	}
	
}
