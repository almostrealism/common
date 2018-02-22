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

/** Wraps a 3D point and parametric time value. */
public class IntersectionPoint {
  private Vec3f intPt = new Vec3f();
  private float t;

  public Vec3f getIntersectionPoint() {
    return intPt;
  }

  public void setIntersectionPoint(Vec3f newPt) {
    intPt.set(newPt);
  }

  public float getT() {
    return t;
  }

  public void setT(float t) {
    this.t = t;
  }
}
