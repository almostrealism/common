package org.almostrealism.space;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.RayField;
import org.almostrealism.util.RayFieldFactory;

public class Test {
	public static void main(String[] args) {
		Mesh m = new Mesh();
		m.addVector(new Vector(1, 2352, 31));
		m.addVector(new Vector(4, 52, 23));
		m.addVector(new Vector(521, 2, 3));
		m.addVector(new Vector(2345, 352, 3));
		m.addVector(new Vector(3, 2, 3345));
		m.addVector(new Vector(31, 2, 3));
		m.addVector(new Vector(41, 235, 53));
		m.addVector(new Vector(21, 23, 3));

		BoundingSolid bounds = BoundingSolid.getBounds(m.getVectors());

		RayField field = RayFieldFactory.getFactory().buildRayField(bounds, 1000, RayFieldFactory.RayDistribution.UNIFORM);



		System.out.println("Hi");
	}
}
