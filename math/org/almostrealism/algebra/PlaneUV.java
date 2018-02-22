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

/** This differs from the Plane class in that it maintains an origin
    and orthonormal U, V axes in the plane so that it can project a 3D
    point to a 2D one. U cross V = normal. U and V coordinates are
    computed with respect to the origin. */

public class PlaneUV {
  private Vec3f origin = new Vec3f();
  /** Normalized */
  private Vec3f normal = new Vec3f();
  private Vec3f uAxis  = new Vec3f();
  private Vec3f vAxis  = new Vec3f();

  /** Default constructor initializes normal to (0, 1, 0), origin to
      (0, 0, 0), U axis to (1, 0, 0) and V axis to (0, 0, -1). */
  public PlaneUV() {
    setEverything(new Vec3f(0, 1, 0),
                  new Vec3f(0, 0, 0),
                  new Vec3f(1, 0, 0),
                  new Vec3f(0, 0, -1));
  }

  /** Takes normal vector and a point which the plane goes through
      (which becomes the plane's "origin"). Normal does NOT have to be
      normalized, but may not be zero vector. U and V axes are
      initialized to arbitrary values. */
  public PlaneUV(Vec3f normal, Vec3f origin) {
    setOrigin(origin);
    setNormal(normal);
  }

  /** Takes normal vector, point which plane goes through, and the "u"
    axis in the plane. Computes the "v" axis by taking the cross
    product of the normal and the u axis. Axis must be perpendicular
    to normal. Normal and uAxis do NOT have to be normalized, but
    neither may be the zero vector. */
  public PlaneUV(Vec3f normal,
                 Vec3f origin,
                 Vec3f uAxis) {
    setOrigin(origin);
    setNormalAndU(normal, uAxis);
  }

  /** Takes normal vector, point which plane goes through, and both
    the u and v axes. u axis cross v axis = normal. Normal, uAxis, and
    vAxis do NOT have to be normalized, but none may be the zero
    vector. */
  public PlaneUV(Vec3f normal,
                 Vec3f origin,
                 Vec3f uAxis,
                 Vec3f vAxis) {
    setEverything(normal, origin, uAxis, vAxis);
  }

  /** Set the origin, through which this plane goes and with respect
      to which U and V coordinates are computed */
  public void setOrigin(Vec3f origin) {
    this.origin.set(origin);
  }

  public Vec3f getOrigin() {
    return new Vec3f(origin);
  }

  /** Normal, U and V axes must be orthogonal and satisfy U cross V =
      normal, do not need to be unit length but must not be the zero
      vector. */
  public void setNormalAndUV(Vec3f normal,
                             Vec3f uAxis,
                             Vec3f vAxis) {
    setEverything(normal, origin, uAxis, vAxis);
  }

  /** This version sets the normal vector and generates new U and V
      axes. */
  public void setNormal(Vec3f normal) {
    Vec3f uAxis = new Vec3f();
    MathUtil.makePerpendicular(normal, uAxis);
    Vec3f vAxis = normal.cross(uAxis);
    setEverything(normal, origin, uAxis, vAxis);
  }

  /** This version computes the V axis from (normal cross U). */
  public void setNormalAndU(Vec3f normal,
                            Vec3f uAxis) {
    Vec3f vAxis = normal.cross(uAxis);
    setEverything(normal, origin, uAxis, vAxis);
  }

  /** Normal, U and V axes are normalized internally, so, for example,
      <b>normal</b> is not necessarily equal to
      <code>plane.setNormal(normal); plane.getNormal();</code> */
  public Vec3f getNormal() {
    return normal;
  }

  public Vec3f getUAxis() {
    return uAxis;
  }

  public Vec3f getVAxis() {
    return vAxis;
  }

  /** Project a point onto the plane */
  public void projectPoint(Vec3f point,
                           Vec3f projPt,
                           Vec2f uvCoords) {
    // Using projPt as a temporary
    projPt.sub(point, origin);
    float dotp = normal.dot(projPt);
    // Component perpendicular to plane
    Vec3f tmpDir = new Vec3f();
    tmpDir.set(normal);
    tmpDir.scale(dotp);
    projPt.sub(projPt, tmpDir);
    // Take dot products with basis vectors
    uvCoords.set(projPt.dot(uAxis),
                 projPt.dot(vAxis));
    // Add on center to intersection point
    projPt.add(origin);
  }

  /** Intersect a ray with this plane, outputting not only the 3D
      intersection point but also the U, V coordinates of the
      intersection. Returns true if intersection occurred, false
      otherwise. This is a two-sided ray cast. */
  public boolean intersectRay(Vec3f rayStart,
                              Vec3f rayDirection,
                              IntersectionPoint intPt,
                              Vec2f uvCoords) {
    float denom = rayDirection.dot(normal);
    if (denom == 0.0f)
      return false;
    Vec3f tmpDir = new Vec3f();
    tmpDir.sub(origin, rayStart);
    float t = tmpDir.dot(normal) / denom;
    // Find intersection point
    Vec3f tmpPt = new Vec3f();
    tmpPt.set(rayDirection);
    tmpPt.scale(t);
    tmpPt.add(rayStart);
    intPt.setIntersectionPoint(tmpPt);
    intPt.setT(t);
    // Find UV coords
    tmpDir.sub(intPt.getIntersectionPoint(), origin);
    uvCoords.set(tmpDir.dot(uAxis), tmpDir.dot(vAxis));
    return true;
  }

  private void setEverything(Vec3f normal,
                             Vec3f origin,
                             Vec3f uAxis,
                             Vec3f vAxis) {
    this.normal.set(normal);
    this.origin.set(origin);
    this.uAxis.set(uAxis);
    this.vAxis.set(vAxis);
    this.normal.normalize();
    this.uAxis.normalize();
    this.vAxis.normalize();
  }
}