package org.almostrealism.chem;

import org.almostrealism.physics.PhysicalConstants;

public class Orbital implements PhysicalConstants {
	private int principal, angular, magnetic;
	
	public Orbital(int principal, int angular, int magnetic) {
		this.principal = principal;
		this.angular = angular;
		this.magnetic = magnetic;
	}
	
	public int getPrincipal() { return principal; }
	public int getAngular() { return angular; }
	public int getMagnetic() { return magnetic; }
	
	public SubShell populate(int electrons) { return new SubShell(this, electrons); }

	public double getEnergy(int protons) {
		return HCR * protons * protons * principal * principal;
	}
	
	public boolean equals(Object o) {
		if (o instanceof Orbital == false) return false;
		Orbital or = (Orbital) o;
		return principal == or.principal && angular == or.angular && magnetic == or.magnetic;
	}
	
	public int hashCode() { return principal; }
	
	public static Orbital s1() { return new Orbital(1, 0, 0); }
	public static Orbital s2() { return new Orbital(2, 0, 0); }
	public static Orbital s3() { return new Orbital(3, 0, 0); }
	public static Orbital s4() { return new Orbital(4, 0, 0); }
	public static Orbital s5() { return new Orbital(5, 0, 0); }
	public static Orbital s6() { return new Orbital(6, 0, 0); }
	public static Orbital s7() { return new Orbital(6, 0, 0); }
	
	public static Orbital p2x() { return new Orbital(2, 1, -1); }
	public static Orbital p2y() { return new Orbital(2, 1, 0); }
	public static Orbital p2z() { return new Orbital(2, 1, 1); }
	
	public static Orbital p3x() { return new Orbital(3, 1, -1); }
	public static Orbital p3y() { return new Orbital(3, 1, 0); }
	public static Orbital p3z() { return new Orbital(3, 1, 1); }
	
	public static Orbital p4x() { return new Orbital(4, 1, -1); }
	public static Orbital p4y() { return new Orbital(4, 1, 0); }
	public static Orbital p4z() { return new Orbital(4, 1, 1); }
	
	public static Orbital p5x() { return new Orbital(5, 1, -1); }
	public static Orbital p5y() { return new Orbital(5, 1, 0); }
	public static Orbital p5z() { return new Orbital(5, 1, 1); }
	
	public static Orbital p6x() { return new Orbital(6, 1, -1); }
	public static Orbital p6y() { return new Orbital(6, 1, 0); }
	public static Orbital p6z() { return new Orbital(6, 1, 1); }
	
	public static Orbital p7x() { return new Orbital(7, 1, -1); }
	public static Orbital p7y() { return new Orbital(7, 1, 0); }
	public static Orbital p7z() { return new Orbital(7, 1, 1); }
	
	public static Orbital d3a() { return new Orbital(3, 2, -2); }
	public static Orbital d3b() { return new Orbital(3, 2, -1); }
	public static Orbital d3c() { return new Orbital(3, 2, 0); }
	public static Orbital d3d() { return new Orbital(3, 2, 1); }
	public static Orbital d3e() { return new Orbital(3, 2, 2); }
	
	public static Orbital d4a() { return new Orbital(4, 2, -2); }
	public static Orbital d4b() { return new Orbital(4, 2, -1); }
	public static Orbital d4c() { return new Orbital(4, 2, 0); }
	public static Orbital d4d() { return new Orbital(4, 2, 1); }
	public static Orbital d4e() { return new Orbital(4, 2, 2); }

	public static Orbital d5a() { return new Orbital(5, 2, -2); }
	public static Orbital d5b() { return new Orbital(5, 2, -1); }
	public static Orbital d5c() { return new Orbital(5, 2, 0); }
	public static Orbital d5d() { return new Orbital(5, 2, 1); }
	public static Orbital d5e() { return new Orbital(5, 2, 2); }
	
	public static Orbital d6a() { return new Orbital(6, 2, -2); }
	public static Orbital d6b() { return new Orbital(6, 2, -1); }
	public static Orbital d6c() { return new Orbital(6, 2, 0); }
	public static Orbital d6d() { return new Orbital(6, 2, 1); }
	public static Orbital d6e() { return new Orbital(6, 2, 2); }
	
	public static Orbital d7a() { return new Orbital(7, 2, -2); }
	public static Orbital d7b() { return new Orbital(7, 2, -1); }
	public static Orbital d7c() { return new Orbital(7, 2, 0); }
	public static Orbital d7d() { return new Orbital(7, 2, 1); }
	public static Orbital d7e() { return new Orbital(7, 2, 2); }
	
	public static Orbital f4a() { return new Orbital(4, 3, -3); }
	public static Orbital f4b() { return new Orbital(4, 3, -2); }
	public static Orbital f4c() { return new Orbital(4, 3, -1); }
	public static Orbital f4d() { return new Orbital(4, 3, 0); }
	public static Orbital f4e() { return new Orbital(4, 3, 1); }
	public static Orbital f4f() { return new Orbital(4, 3, 2); }
	public static Orbital f4g() { return new Orbital(4, 3, 3); }
	
	public static Orbital f5a() { return new Orbital(5, 3, -3); }
	public static Orbital f5b() { return new Orbital(5, 3, -2); }
	public static Orbital f5c() { return new Orbital(5, 3, -1); }
	public static Orbital f5d() { return new Orbital(5, 3, 0); }
	public static Orbital f5e() { return new Orbital(5, 3, 1); }
	public static Orbital f5f() { return new Orbital(5, 3, 2); }
	public static Orbital f5g() { return new Orbital(5, 3, 3); }
	
	public static Orbital f6a() { return new Orbital(6, 3, -3); }
	public static Orbital f6b() { return new Orbital(6, 3, -2); }
	public static Orbital f6c() { return new Orbital(6, 3, -1); }
	public static Orbital f6d() { return new Orbital(6, 3, 0); }
	public static Orbital f6e() { return new Orbital(6, 3, 1); }
	public static Orbital f6f() { return new Orbital(6, 3, 2); }
	public static Orbital f6g() { return new Orbital(6, 3, 3); }
	
	public static Orbital f7a() { return new Orbital(7, 3, -3); }
	public static Orbital f7b() { return new Orbital(7, 3, -2); }
	public static Orbital f7c() { return new Orbital(7, 3, -1); }
	public static Orbital f7d() { return new Orbital(7, 3, 0); }
	public static Orbital f7e() { return new Orbital(7, 3, 1); }
	public static Orbital f7f() { return new Orbital(7, 3, 2); }
	public static Orbital f7g() { return new Orbital(7, 3, 3); }
}
