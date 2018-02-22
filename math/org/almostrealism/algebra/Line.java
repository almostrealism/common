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

/** Represents a line in 3D space. */

public class Line {
  private Vec3f point;
  /** Normalized */
  private Vec3f direction;
  /** For computing projections along line */
  private Vec3f alongVec;

  /** Default constructor initializes line to point (0, 0, 0) and
      direction (1, 0, 0) */
  public Line() {
    point = new Vec3f(0, 0, 0);
    direction = new Vec3f(1, 0, 0);
    alongVec = new Vec3f();
    recalc();
  }

  /** Line goes in direction <b>direction</b> through the point
      <b>point</b>. <b>direction</b> does not need to be normalized but must
      not be the zero vector. */
  public Line(Vec3f direction, Vec3f point) {
    direction = new Vec3f(direction);
    direction.normalize();
    point = new Vec3f(point);
    alongVec = new Vec3f();
    recalc();
  }

  /** Setter does some work to maintain internal caches.
      <b>direction</b> does not need to be normalized but must not be
      the zero vector. */
  public void setDirection(Vec3f direction) {
    this.direction.set(direction);
    this.direction.normalize();
    recalc();
  }

  /** Direction is normalized internally, so <b>direction</b> is not
      necessarily equal to <code>plane.setDirection(direction);
      plane.getDirection();</code> */
  public Vec3f getDirection() {
    return direction;
  }

  /** Setter does some work to maintain internal caches. */
  public void setPoint(Vec3f point) {
    this.point.set(point);
    recalc();
  }

  public Vec3f getPoint() {
    return point;
  }

  /** Project a point onto the line */
  public void projectPoint(Vec3f pt,
                           Vec3f projPt) {
    float dotp = direction.dot(pt);
    projPt.set(direction);
    projPt.scale(dotp);
    projPt.add(alongVec);
  }

  /** Find closest point on this line to the given ray, specified by
      start point and direction. If ray is parallel to this line,
      returns false and closestPoint is not modified. */
  public boolean closestPointToRay(Vec3f rayStart,
                                   Vec3f rayDirection,
                                   Vec3f closestPoint) {
    // Line 1 is this one. Line 2 is the incoming one.
    Mat2f A = new Mat2f();
    A.set(0, 0, -direction.lengthSquared());
    A.set(1, 1, -rayDirection.lengthSquared());
    A.set(0, 1, direction.dot(rayDirection));
    A.set(1, 0, A.get(0, 1));
    if (Math.abs(A.determinant()) == 0.0f) {
      return false;
    }
    if (!A.invert()) {
      return false;
    }
    Vec2f b = new Vec2f();
    b.setX(point.dot(direction) - rayStart.dot(direction));
    b.setY(rayStart.dot(rayDirection) - point.dot(rayDirection));
    Vec2f x = new Vec2f();
    A.xformVec(b, x);
    if (x.y() < 0) {
      // Means that ray start is closest point to this line
      closestPoint.set(rayStart);
    } else {
      closestPoint.set(direction);
      closestPoint.scale(x.x());
      closestPoint.add(point);
    }
    return true;
  }

  //----------------------------------------------------------------------
  // Internals only below this point
  //
  
  private void recalc() {
    float denom = direction.lengthSquared();
    if (denom == 0.0f) {
      throw new RuntimeException("Line.recalc: ERROR: direction was the zero vector " +
                                 "(not allowed)");
    }
    alongVec.set(point.minus(direction.times(point.dot(direction))));
  }
}
