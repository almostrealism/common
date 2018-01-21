package org.almostrealism.frames;

public class SpatialFrame {
	private Predicate a, b;
	
	public SpatialFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}
	
	public String toString() { return a + " is closer than " + b; }
}
