package org.almostrealism.frames;

public class CoordinationFrame {
	private Predicate a, b;
	
	public CoordinationFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}
	
	public String toString() { return a + " is the same as " + b; }
}
