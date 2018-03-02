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
 * This differs from the Plane class in that it maintains an origin
 * and orthonormal U, V axes in the plane so that it can project a 3D
 * point to a 2D one. U cross V = normal. U and V coordinates are
 * computed with respect to the origin.
 */
public class PlaneUV {
	private Vector origin = new Vector();
	/**
	 * Normalized
	 */
	private Vector normal = new Vector();
	private Vector uAxis = new Vector();
	private Vector vAxis = new Vector();

	/**
	 * Default constructor initializes normal to (0, 1, 0), origin to
	 * (0, 0, 0), U axis to (1, 0, 0) and V axis to (0, 0, -1).
	 */
	public PlaneUV() {
		setEverything(new Vector(0, 1, 0),
				new Vector(0, 0, 0),
				new Vector(1, 0, 0),
				new Vector(0, 0, -1));
	}

	/**
	 * Takes normal vector and a point which the plane goes through
	 * (which becomes the plane's "origin"). Normal does NOT have to be
	 * normalized, but may not be zero vector. U and V axes are
	 * initialized to arbitrary values.
	 */
	public PlaneUV(Vector normal, Vector origin) {
		setOrigin(origin);
		setNormal(normal);
	}

	/**
	 * Takes normal vector, point which plane goes through, and the "u"
	 * axis in the plane. Computes the "v" axis by taking the cross
	 * product of the normal and the u axis. Axis must be perpendicular
	 * to normal. Normal and uAxis do NOT have to be normalized, but
	 * neither may be the zero vector.
	 */
	public PlaneUV(Vector normal,
				   Vector origin,
				   Vector uAxis) {
		setOrigin(origin);
		setNormalAndU(normal, uAxis);
	}

	/**
	 * Takes normal vector, point which plane goes through, and both
	 * the u and v axes. u axis cross v axis = normal. Normal, uAxis, and
	 * vAxis do NOT have to be normalized, but none may be the zero
	 * vector.
	 */
	public PlaneUV(Vector normal,
				   Vector origin,
				   Vector uAxis,
				   Vector vAxis) {
		setEverything(normal, origin, uAxis, vAxis);
	}

	/**
	 * Set the origin, through which this plane goes and with respect
	 * to which U and V coordinates are computed
	 */
	public void setOrigin(Vector origin) {
		this.origin.setTo(origin);
	}

	public Vector getOrigin() {
		return (Vector) origin.clone();
	}

	/**
	 * Normal, U and V axes must be orthogonal and satisfy U cross V =
	 * normal, do not need to be unit length but must not be the zero
	 * vector.
	 */
	public void setNormalAndUV(Vector normal,
							   Vector uAxis,
							   Vector vAxis) {
		setEverything(normal, origin, uAxis, vAxis);
	}

	/**
	 * This version sets the normal vector and generates new U and V
	 * axes.
	 */
	public void setNormal(Vector normal) {
		Vector uAxis = new Vector();
		Vector.makePerpendicular(normal, uAxis);
		Vector vAxis = normal.crossProduct(uAxis);
		setEverything(normal, origin, uAxis, vAxis);
	}

	/**
	 * This version computes the V axis from (normal cross U).
	 */
	public void setNormalAndU(Vector normal,
							  Vector uAxis) {
		Vector vAxis = normal.crossProduct(uAxis);
		setEverything(normal, origin, uAxis, vAxis);
	}

	/**
	 * Normal, U and V axes are normalized internally, so, for example,
	 * <b>normal</b> is not necessarily equal to
	 * <code>plane.setNormal(normal); plane.getNormal();</code>
	 */
	public Vector getNormal() {
		return normal;
	}

	public Vector getUAxis() {
		return uAxis;
	}

	public Vector getVAxis() {
		return vAxis;
	}

	/** Project a point onto the plane */
	public void projectPoint(Vector point,
							 Vector projPt,
							 Pair uvCoords) {
		// Using projPt as a temporary
		projPt.subtract(point, origin);
		double dotp = normal.dotProduct(projPt);

		// Component perpendicular to plane
		Vector tmpDir = new Vector();
		tmpDir.setTo(normal);
		tmpDir.multiplyBy(dotp);
		projPt.subtract(projPt, tmpDir);

		// Take dot products with basis vectors
		uvCoords.setX(projPt.dotProduct(uAxis));
		uvCoords.setY(projPt.dotProduct(vAxis));

		// Add on center to intersection point
		projPt.add(origin);
	}

	/**
	 * Intersect a ray with this plane, outputting not only the 3D
	 * intersection point but also the U, V coordinates of the
	 * intersection. Returns true if intersection occurred, false
	 * otherwise. This is a two-sided ray cast.
	 */
	public boolean intersectRay(Ray ray, IntersectionPoint intPt, Pair uvCoords) {
		double denom = ray.getDirection().dotProduct(normal);

		if (denom == 0.0f)
			return false;

		Vector tmpDir = new Vector();
		tmpDir.subtract(origin, ray.getOrigin());

		double t = tmpDir.dotProduct(normal) / denom;

		// Find intersection point
		Vector tmpPt = new Vector();
		tmpPt.setTo(ray.getDirection());
		tmpPt.multiplyBy(t);
		tmpPt.add(ray.getOrigin());
		intPt.setIntersectionPoint(tmpPt);
		intPt.setT(t);

		// Find UV coords
		tmpDir.subtract(intPt.getIntersectionPoint(), origin);
		uvCoords.setX(tmpDir.dotProduct(uAxis));
		uvCoords.setY(tmpDir.dotProduct(vAxis));

		return true;
	}

	private void setEverything(Vector normal,
							   Vector origin,
							   Vector uAxis,
							   Vector vAxis) {
		this.normal.setTo(normal);
		this.origin.setTo(origin);
		this.uAxis.setTo(uAxis);
		this.vAxis.setTo(vAxis);
		this.normal.normalize();
		this.uAxis.normalize();
		this.vAxis.normalize();
	}
}
