package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Dysprosium implements Element {
	public int getAtomicNumber() { return 66; }

	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Xenon.getShells());
		s.add(Shell.fourth(0, 0, 0, 10));
		s.add(Shell.sixth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
