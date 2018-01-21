package org.almostrealism.frames;

public class TemporalFrame {
	private Predicate a, b;
	
	public TemporalFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}
	
	public String toString() { return a + " is before " + b; }
}
