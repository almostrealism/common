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

// TODO  Replace with ShadableIntersection

@Deprecated
public class IntersectionPoint {
	private Vector intPt = new Vector();
	private double t;

	public Vector getIntersectionPoint() {
		return intPt;
	}

	public void setIntersectionPoint(Vector newPt) {
		intPt.setTo(newPt);
	}

	public double getT() {
		return t;
	}

	public void setT(double t) {
		this.t = t;
	}
}
