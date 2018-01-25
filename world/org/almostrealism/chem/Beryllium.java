package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Beryllium implements Element {
	public int getAtomicNumber() { return 4; }

	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(2, 0));
		return Collections.unmodifiableList(s);
	}
}
