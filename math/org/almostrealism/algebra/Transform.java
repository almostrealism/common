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
 * Transform supports rigid transforms (only translation and rotation, no scaling/shear).
 * 
 * @author jezek2
 */
public class Transform {
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
}
