package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Californium implements Element {
	public int getAtomicNumber() { return 98; }

	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Radon.getShells());
		s.add(Shell.fifth(0, 0, 0, 10));
		s.add(Shell.seventh(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
