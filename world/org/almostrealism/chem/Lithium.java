package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Lithium implements Element {
	public int getAtomicNumber() { return 3; }

	public Atom getAtom() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(1, 0));
		return Collections.unmodifiableList(s);
	}
}
