package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Lawrencium implements Element {
	public int getAtomicNumber() { return 103; }
	
	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Radon.getShells());
		s.add(Shell.fifth(0, 0, 0, 14));
		s.add(Shell.seventh(2, 1, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
