package org.almostrealism.physics;

public class SubShell {
	private Orbital o;
	private int e;
	
	public SubShell(Orbital o, int electrons) {
		if (electrons < 1 || electrons > 2) throw new IllegalArgumentException("A SubShell may only contain 1 or 2 electrons");
		this.o = o;
		this.e = electrons;
	}
	
	public Orbital getOrbital() { return o; }
}
