package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Xenon implements Element {
	public int getAtomicNumber() { return 54; }
	
	public Atom construct() { return new Atom(getShells()); }
	
	protected List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Krypton.getShells());
		s.add(Shell.fourth(0, 0, 10, 0));
		s.add(Shell.fifth(2, 6, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
