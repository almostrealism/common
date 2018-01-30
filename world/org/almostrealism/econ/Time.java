package org.almostrealism.econ;

public class Time {
	private long sysTime;

	public Time(long sysTime) {
		this.sysTime = sysTime;
	}

	public int hashCode() {
		// TODO  This probably doesn't perform well, as all of the times are near-neighbors (recent past)
		return (int) ((((double) sysTime) / ((double) Long.MAX_VALUE)) * Integer.MAX_VALUE);
	}

	public boolean equals(Object o) {
		if (o instanceof Time == false) return false;
		return ((Time) o).sysTime == sysTime;
	}
}
