package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Chromium implements Element {
	public int getAtomicNumber() { return 24; }

	public Atom getAtom() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Argon.getShells());
		s.add(Shell.third(0, 0, 5));
		s.add(Shell.fourth(1, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
