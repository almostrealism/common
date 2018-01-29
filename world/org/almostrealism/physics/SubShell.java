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

	public Electron getElectron(Spin s, int protons) {
		switch (s) {
			case Up:
				return e >= 1 ? new Electron(s, o.getHigherOrbitals(), protons) : null;

			case Down:
				return e >= 2 ? new Electron(s, o.getHigherOrbitals(), protons) : null;
		}

		return null;
	}
}
