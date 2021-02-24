/*
 * Copyright 2018 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.econ;

import org.almostrealism.algebra.Scalar;

/**
 * {@link Time} represents a millisecond somewhere along the timeline starting
 * at January 1st 1970 and ending {@link Double#MAX_VALUE} milliseconds after that.
 * Instances of {@link Time} are also used to represent duration, measured in
 * milliseconds. Because {@link Time} extends {@link Scalar}, uncertain times
 * can be represented. The default setting for certainty is 1.0, but it may be
 * adjusted using {@link #setCertainty(double)}.
 */
public class Time extends Scalar {
	public Time() { this.setValue(-1); }

	public Time(long sysTime) {
		this.setValue(sysTime);
	}

	protected Time(double v) { this.setValue(v); }

	public void setTime(long sysTime) { this.setValue(sysTime); }

	public long getTime() { return (long) this.getValue(); }

	public Time subtract(Time t) {
		return new Time(this.getValue() - t.getValue());
	}

	public long inMinutes() { return getTime() / 60000; }

	public boolean isBefore(Time t) {
		return isBefore(t, false);
	}

	public boolean isBefore(Time t, boolean inclusive) {
		return inclusive ? this.getTime() <= t.getTime() :
							this.getTime() < t.getTime();
	}

	public boolean isAfter(Time t) {
		return isAfter(t, false);
	}

	public boolean isAfter(Time t, boolean inclusive) {
		return inclusive ? this.getTime() >= t.getTime() :
							this.getTime() > t.getTime();
	}

	public int hashCode() {
		// TODO  This probably doesn't perform well, as all of the times are near-neighbors (recent past)
		return (int) (getValue() / Double.MAX_VALUE) * Integer.MAX_VALUE;
	}

	public boolean equals(Object o) {
		if (o instanceof Time == false) return false;
		return ((Time) o).getTime() == getTime();
	}
}
