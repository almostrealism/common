package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import org.almostrealism.physics.Element;
import org.almostrealism.physics.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Platinum implements Element {
	public int getAtomicNumber() { return 78; }
	
	public Atom construct() { return new Atom(getAtomicNumber(), getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Xenon.getShells());
		s.add(Shell.fourth(0, 0, 0, 14));
		s.add(Shell.fifth(0, 0, 9, 0));
		s.add(Shell.sixth(1, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
