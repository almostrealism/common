package io.almostrealism.scope;

import io.almostrealism.expression.Expression;

public class TieredSimplificationSettings implements SimplificationSettings {

	private static int tier0 = 2;
	private static int tier1 = 8;
	private static int tier2 = 16;
	private static int tier3 = 20;

	private static int pref1 = 2;
	private static int pref2 = 3;
	private static int pref3 = 4;
	private static int pref4 = 7;

	public boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		// if (expression.getType() == Boolean.class) return true;

		if (depth < tier0) {
			return true;
		} else if (depth < tier1) {
			return expression.getType() == Boolean.class || expression.containsLong() ||
					expression.countNodes() > 50 ||
					targetByDepth(expression.treeDepth(), pref1);
		} else if (depth < tier2) {
			return expression.containsLong() ||
					expression.countNodes() > 75 ||
					targetByDepth(expression.treeDepth(), pref2);
		} else if (depth < tier3) {
			return expression.containsLong() ||
					expression.countNodes() > 85 ||
					targetByDepth(expression.treeDepth(), pref3);
		} else {
			return expression.countNodes() > 100 ||
					targetByDepth(expression.treeDepth(), pref4);
		}
	}

	public static boolean targetByDepth(int depth, int preference) {
		return depth > (preference + 3) && depth % preference == 0;
	}
}
