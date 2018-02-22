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

/** Utility math routines. */

public class MathUtil {
  /** Makes an arbitrary vector perpendicular to <B>src</B> and
      inserts it into <B>dest</B>. Returns false if the source vector
      was equal to (0, 0, 0). */
  public static boolean makePerpendicular(Vec3f src,
                                          Vec3f dest) {
    if ((src.x() == 0.0f) && (src.y() == 0.0f) && (src.z() == 0.0f)) {
      return false;
    }

    if (src.x() != 0.0f) {
      if (src.y() != 0.0f) {
	dest.set(-src.y(), src.x(), 0.0f);
      }	else {
	dest.set(-src.z(), 0.0f, src.x());
      }
    } else {
      dest.set(1.0f, 0.0f, 0.0f);
    }
    return true;
  }

  /** Returns 1 if the sign of the given argument is positive; -1 if
      negative; 0 if 0. */
  public static int sgn(float f) {
    if (f > 0) {
      return 1;
    } else if (f < 0) {
      return -1;
    }
    return 0;
  }

  /** Clamps argument between min and max values. */
  public static float clamp(float val, float min, float max) {
    if (val < min) return min;
    if (val > max) return max;
    return val;
  }

  /** Clamps argument between min and max values. */
  public static int clamp(int val, int min, int max) {
    if (val < min) return min;
    if (val > max) return max;
    return val;
  }
}
