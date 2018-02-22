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

/** 2-element single-precision vector */

// TODO  Replace uses with Pair
@Deprecated
public class Vec2f {
  private float x;
  private float y;

  public Vec2f() {}

  public Vec2f(Vec2f arg) {
    this(arg.x, arg.y);
  }

  public Vec2f(float x, float y) {
    set(x, y);
  }

  public Vec2f copy() {
    return new Vec2f(this);
  }

  public void set(float x, float y) {
    this.x = x;
    this.y = y;
  }

  /** Sets the ith component, 0 <= i < 2 */
  public void set(int i, float val) {
    switch (i) {
    case 0: x = val; break;
    case 1: y = val; break;
    default: throw new IndexOutOfBoundsException();
    }
  }

  /** Gets the ith component, 0 <= i < 2 */
  public float get(int i) {
    switch (i) {
    case 0: return x;
    case 1: return y;
    default: throw new IndexOutOfBoundsException();
    }
  }

  public float x() { return x; }
  public float y() { return y; }

  public void setX(float x) { this.x = x; }
  public void setY(float y) { this.y = y; }

  public float dot(Vec2f arg) {
    return x * arg.x + y * arg.y;
  }

  public float length() {
    return (float) Math.sqrt(lengthSquared());
  }

  public float lengthSquared() {
    return this.dot(this);
  }

  public void normalize() {
    float len = length();
    if (len == 0.0f) return;
    scale(1.0f / len);
  }

  /** Returns this * val; creates new vector */
  public Vec2f times(float val) {
    Vec2f tmp = new Vec2f(this);
    tmp.scale(val);
    return tmp;
  }

  /** this = this * val */
  public void scale(float val) {
    x *= val;
    y *= val;
  }

  /** Returns this + arg; creates new vector */
  public Vec2f plus(Vec2f arg) {
    Vec2f tmp = new Vec2f();
    tmp.add(this, arg);
    return tmp;
  }

  /** this = this + b */
  public void add(Vec2f b) {
    add(this, b);
  }

  /** this = a + b */
  public void add(Vec2f a, Vec2f b) {
    x = a.x + b.x;
    y = a.y + b.y;
  }

  /** Returns this + s * arg; creates new vector */
  public Vec2f addScaled(float s, Vec2f arg) {
    Vec2f tmp = new Vec2f();
    tmp.addScaled(this, s, arg);
    return tmp;
  }

  /** this = a + s * b */
  public void addScaled(Vec2f a, float s, Vec2f b) {
    x = a.x + s * b.x;
    y = a.y + s * b.y;
  }

  /** Returns this - arg; creates new vector */
  public Vec2f minus(Vec2f arg) {
    Vec2f tmp = new Vec2f();
    tmp.sub(this, arg);
    return tmp;
  }

  /** this = this - b */
  public void sub(Vec2f b) {
    sub(this, b);
  }

  /** this = a - b */
  public void sub(Vec2f a, Vec2f b) {
    x = a.x - b.x;
    y = a.y - b.y;
  }

  public Vecf toVecf() {
    Vecf out = new Vecf(2);
    for (int i = 0; i < 2; i++) {
      out.set(i, get(i));
    }
    return out;
  }

  public String toString() {
    return "(" + x + ", " + y + ")";
  }
}
