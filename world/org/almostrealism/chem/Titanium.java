package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Titanium implements Element {
	public int getAtomicNumber() { return 22; }
	
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Argon.getShells());
		s.add(Shell.third(0, 0, 2));
		s.add(Shell.fourth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
