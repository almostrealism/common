package org.almostrealism.chem;

import java.util.List;

public class Atom {
	private int protons;
	private List<Shell> shells;
	
	protected Atom(int protons, List<Shell> shells) {
		this.protons = protons;
		this.shells = shells;
	}

	public int getProtons() { return protons; }
	
	public Shell getValenceShell() {
		int highestEnergy = 0;
		Shell sh = null;
		
		for (Shell s : shells) {
			if (s.getEnergyLevel() > highestEnergy) {
				highestEnergy = s.getEnergyLevel();
				sh = s;
			}
		}
		
		return sh;
	}
}
