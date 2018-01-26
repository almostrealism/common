package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Iron implements Element {
	public int getAtomicNumber() { return 26; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Argon.getShells());
		s.add(Shell.third(0, 0, 6));
		s.add(Shell.fourth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
