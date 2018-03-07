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
import org.almostrealism.algebra.Rotf;
import org.almostrealism.algebra.Vector;

/**
 * Transform supports rigid transforms (only translation and rotation, no scaling/shear).
 * 
 * @author jezek2
 */
public class Transform {
	protected BulletStack stack;

	public final Mat3f basis = new Mat3f();
	public final Vector origin = new Vector();

	public Transform() {
	}

	public Transform(Mat3f mat) {
		basis.set(mat);
	}

	public Transform(Transform tr) {
		set(tr);
	}
	
	public void set(Transform tr) {
		basis.set(tr.basis);
		float f[] = tr.origin.getPosition();
		origin.setPosition(f[0], f[1], f[2]);
	}

	public void transform(Vector v) {
		basis.transform(v);
		v.add(origin);
	}

	public void setIdentity() {
		basis.setIdentity();
		origin.setPosition(0f, 0f, 0f);
	}
	
	public void inverse() {
		basis.transpose();
		origin.multiplyBy(-1.0);
		basis.transform(origin);
	}

	public void inverse(Transform tr) {
		set(tr);
		inverse();
	}
	
	public void mul(Transform tr) {
		if (stack == null) stack = BulletStack.get();
		
		stack.vectors.push();
		try {
			Vector vec = stack.vectors.get(tr.origin);
			transform(vec);

			basis.mul(tr.basis);
			float f[] = vec.getPosition();
			origin.setPosition(f[0], f[1], f[2]);
		}
		finally {
			stack.vectors.pop();
		}
	}

	public void mul(Transform tr1, Transform tr2) {
		set(tr1);
		mul(tr2);
	}
	
	public void invXform(Vector inVec, Vector out) {
		if (stack == null) stack = BulletStack.get();

		stack.matrices.push();

		try {
			out.subtract(inVec, origin);

			Mat3f mat = stack.matrices.get(basis);
			mat.transpose();
			mat.transform(out);
		} finally {
			stack.matrices.pop();
		}
	}
	
	public Rotf getRotation() {
		if (stack == null) stack = BulletStack.get();

		stack.quats.push();

		try {
			Rotf q = stack.quats.get();
			Transform.getRotation(basis, q);
			return stack.quats.returning(q);
		} finally {
			stack.quats.pop();
		}
	}
	
	public void setRotation(Rotf q) {
		Mat3f.setRotation(basis, q);
	}
	
	public void setFromOpenGLMatrix(float[] m) {
		Mat3f.setFromOpenGLSubMatrix(basis, m);
		origin.setPosition(m[12], m[13], m[14]);
	}

	public void getOpenGLMatrix(float[] m) {
		Mat3f.getOpenGLSubMatrix(basis, m);
		m[12] = (float) origin.getX();
		m[13] = (float) origin.getY();
		m[14] = (float) origin.getZ();
		m[15] = 1f;
	}

	public static void getRotation(Mat3f mat, Rotf dest) {
		BulletStack stack = BulletStack.get();

		float trace = mat.get(0, 0) + mat.get(1, 1) + mat.get(2, 2);
		float[] temp = stack.floatArrays.getFixed(4);

		if (trace > 0f) {
			float s = (float) Math.sqrt(trace + 1f);
			temp[3] = (s * 0.5f);
			s = 0.5f / s;

			temp[0] = ((mat.get(2, 1) - mat.get(1, 2)) * s);
			temp[1] = ((mat.get(0, 2) - mat.get(2, 0)) * s);
			temp[2] = ((mat.get(1, 0) - mat.get(0, 1)) * s);
		} else {
			int i = mat.get(0, 0) < mat.get(1, 1) ? (mat.get(1, 1) < mat.get(2, 2) ? 2 : 1) : (mat.get(0, 0) < mat.get(2, 2) ? 2 : 0);
			int j = (i + 1) % 3;
			int k = (i + 2) % 3;

			float s = (float) Math.sqrt(mat.get(i, i) - mat.get(j, j) - mat.get(k, k) + 1f);
			temp[i] = s * 0.5f;
			s = 0.5f / s;

			temp[3] = (mat.get(k, j) - mat.get(j, k)) * s;
			temp[j] = (mat.get(j, i) + mat.get(i, j)) * s;
			temp[k] = (mat.get(k, i) + mat.get(i, k)) * s;
		}

		dest.set(temp[0], temp[1], temp[2], temp[3]);

		stack.floatArrays.release(temp);
	}
}
