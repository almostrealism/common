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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Mat2f;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;

/**
 * Represents a line in 3D space.
 *
 * @deprecated  Use {@link Ray} instead.
 */
@Deprecated
public class Line {
	private Vector point;

	/** Normalized */
	private Vector direction;

	/** For computing projections along line */
	private Vector alongVec;

	/**
	 * Default constructor initializes line to point (0, 0, 0) and
	 * direction (1, 0, 0)
	 */
	public Line() {
		point = new Vector(0, 0, 0);
		direction = new Vector(1, 0, 0);
		alongVec = new Vector();
		recalc();
	}

	/**
	 * Line goes in direction <b>direction</b> through the point
	 * <b>point</b>. <b>direction</b> does not need to be normalized but must
	 * not be the zero vector.
	 */
	public Line(Vector direction, Vector point) {
		this.direction = new Vector();
		this.direction.setTo(direction);
		this.direction.normalize();

		this.point = new Vector();
		this.point.setTo(point);

		alongVec = new Vector();
		recalc();
	}

	/**
	 * Setter does some work to maintain internal caches.
	 * <b>direction</b> does not need to be normalized but must not be
	 * the zero vector.
	 */
	public void setDirection(Vector direction) {
		this.direction.setTo(direction);
		this.direction.normalize();
		recalc();
	}

	/**
	 * Direction is normalized internally, so <b>direction</b> is not
	 * necessarily equal to <code>plane.setDirection(direction);
	 * plane.getDirection();</code>
	 */
	public Vector getDirection() {
		return direction;
	}

	/** Setter does some work to maintain internal caches. */
	public void setPoint(Vector point) {
		this.point.setTo(point);
		recalc();
	}

	public Vector getPoint() {
		return point;
	}

	/** Project a point onto the line */
	public void projectPoint(Vector pt,
							 Vector projPt) {
		double dotp = direction.dotProduct(pt);
		projPt.setTo(direction);
		projPt.multiplyBy(dotp);
		projPt.add(alongVec);
	}

	/**
	 * Find closest point on this line to the given ray, specified by
	 * start point and direction. If ray is parallel to this line,
	 * returns false and closestPoint is not modified.
	 */
	public boolean closestPointToRay(Ray ray,
									 Vector closestPoint) {
		// Line 1 is this one. Line 2 is the incoming one.
		Mat2f A = new Mat2f();
		A.set(0, 0, (float) -direction.lengthSq());
		A.set(1, 1, (float) -ray.getDirection().lengthSq());
		A.set(0, 1, (float) direction.dotProduct(ray.getDirection()));
		A.set(1, 0, A.get(0, 1));

		if (Math.abs(A.determinant()) == 0.0f) {
			return false;
		}

		if (!A.invert()) {
			return false;
		}

		Pair b = new Pair();
		b.setX(point.dotProduct(direction) - ray.getOrigin().dotProduct(direction));
		b.setY(ray.getOrigin().dotProduct(ray.getDirection()) - point.dotProduct(ray.getDirection()));

		Pair x = new Pair();
		A.xformVec(b, x);

		if (x.y() < 0) {
			// Means that ray start is closest point to this line
			closestPoint.setTo(ray.getOrigin());
		} else {
			closestPoint.setTo(direction);
			closestPoint.multiplyBy(x.getX());
			closestPoint.add(point);
		}

		return true;
	}

	private void recalc() {
		double denom = direction.lengthSq();

		if (denom == 0.0) {
			throw new RuntimeException("Direction was the zero vector (not allowed)");
		}

		alongVec.setTo(point.subtract(direction.multiply(point.dotProduct(direction))));
	}
}
