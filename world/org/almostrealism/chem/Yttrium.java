package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Yttrium implements Element {
	public int getAtomicNumber() { return 39; }
	
	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Krypton.getShells());
		s.add(Shell.fourth(0, 0, 1, 0));
		s.add(Shell.fifth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
