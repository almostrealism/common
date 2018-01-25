package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Gadolinium implements Element {
	public int getAtomicNumber() { return 64; }

	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Xenon.getShells());
		s.add(Shell.fourth(0, 0, 0, 7));
		s.add(Shell.fifth(0, 0, 1, 0));
		s.add(Shell.sixth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
