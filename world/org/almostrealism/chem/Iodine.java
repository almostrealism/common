package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Iodine implements Element {
	public int getAtomicNumber() { return 53; }
	
	public Atom construct() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Krypton.getShells());
		s.add(Shell.fourth(0, 0, 10, 0));
		s.add(Shell.fifth(2, 5, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
