package io.almostrealism.frames;

public class ComparativeFrame {
	private Predicate a, b;
	
	public ComparativeFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}
	
	public String toString() { return b + " is larger than " + a; }
}
