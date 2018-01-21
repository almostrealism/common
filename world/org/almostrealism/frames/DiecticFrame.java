package org.almostrealism.frames;

public class DiecticFrame {
	private Predicate a, b;
	
	public DiecticFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}
	
	public String toString() { return a + " is " + b; }
}
