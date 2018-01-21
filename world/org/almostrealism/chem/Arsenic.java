package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Arsenic implements Element {
	public int getAtomicNumber() { return 33; }
	
	public Atom getAtom() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Argon.getShells());
		s.add(Shell.third(0, 0, 10));
		s.add(Shell.fourth(2, 3, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
