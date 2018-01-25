package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Aluminium implements Element {
	public int getAtomicNumber() { return 13; }
	
	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Neon.getShells());
		s.add(Shell.third(2, 1, 0));
		return Collections.unmodifiableList(s);
	}
}
