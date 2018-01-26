package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Carbon implements Element {
	public int getAtomicNumber() { return 6; }

	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Helium.getShells());
		s.add(Shell.second(2, 2));
		return Collections.unmodifiableList(s);
	}
}
