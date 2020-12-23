package io.almostrealism.frames;

public class CausalFrame {
	private Predicate a, b;
	
	public CausalFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}
	
	public String toString() { return b + " is because of " + a; }
}
