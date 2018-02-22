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

/** Represents a plane in 3D space. */

public class Plane {
  /** Normalized */
  private Vec3f normal;
  private Vec3f point;
  /** Constant for faster projection and intersection */
  float c;

  /** Default constructor initializes normal to (0, 1, 0) and point to
      (0, 0, 0) */
  public Plane() {
    normal = new Vec3f(0, 1, 0);
    point = new Vec3f(0, 0, 0);
    recalc();
  }

  /** Sets all parameters of plane. Plane has normal <b>normal</b> and
      goes through the point <b>point</b>. Normal does not need to be
      unit length but must not be the zero vector. */
  public Plane(Vec3f normal, Vec3f point) {
    this.normal.set(normal);
    this.normal.normalize();
    this.point.set(point);
    recalc();
  }

  /** Setter does some work to maintain internal caches. Normal does
      not need to be unit length but must not be the zero vector. */
  public void setNormal(Vec3f normal) {
    this.normal.set(normal);
    this.normal.normalize();
    recalc();
  }

  /** Normal is normalized internally, so <b>normal</b> is not
      necessarily equal to <code>plane.setNormal(normal);
      plane.getNormal();</code> */
  public Vec3f getNormal() {
    return normal;
  }

  /** Setter does some work to maintain internal caches */
  public void setPoint(Vec3f point) {
    this.point.set(point);
    recalc();
  }

  public Vec3f getPoint() {
    return point;
  }

  /** Project a point onto the plane */
  public void projectPoint(Vec3f pt,
                           Vec3f projPt) {
    float scale = normal.dot(pt) - c;
    projPt.set(pt.minus(normal.times(normal.dot(point) - c)));
  }

  /** Intersect a ray with the plane. Returns true if intersection occurred, false
      otherwise. This is a two-sided ray cast. */
  public boolean intersectRay(Vec3f rayStart,
                              Vec3f rayDirection,
                              IntersectionPoint intPt) {
    float denom = normal.dot(rayDirection);
    if (denom == 0)
      return false;
    intPt.setT((c - normal.dot(rayStart)) / denom);
    intPt.setIntersectionPoint(rayStart.plus(rayDirection.times(intPt.getT())));
    return true;
  }

  //----------------------------------------------------------------------
  // Internals only below this point
  //
  
  private void recalc() {
    c = normal.dot(point);
  }
}
