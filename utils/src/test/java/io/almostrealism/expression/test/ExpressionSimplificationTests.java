package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mod;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.junit.Assert;
import org.junit.Test;

public class ExpressionSimplificationTests implements ExpressionFeatures {
	private OpenCLLanguageOperations lang = new OpenCLLanguageOperations(Precision.FP64);

	@Test
	public void productToInt() {
		Expression a = new IntegerConstant(1);
		Expression b = new IntegerConstant(2);
		Expression c = new IntegerConstant(3);
		Expression out = a.add(b).multiply(c).toInt();

		System.out.println(out.getSimpleExpression(lang));
	}

	@Test
	public void divide() {
		String e = e(1).divide(e(2)).getSimpleExpression(lang);
		System.out.println(e);
		Assert.assertTrue(e.length() < 12);
	}

	@Test
	public void castSumDivide1() {
		Expression d = e(1).divide(e(2));
		Expression out = e(25).add(d).toInt();

		System.out.println(out.getSimpleExpression(lang));
		Assert.assertEquals("25", out.getSimpleExpression(lang));
	}

	@Test
	public void castSumDivide2() {
		Expression d = e(1).divide(e(2));
		Expression c = e(3).add(e(4).multiply(e(5)));
		Expression b = c.add(e(2));
		Expression out = b.add(d).toInt();

		System.out.println(out.getSimpleExpression(lang));
		Assert.assertEquals("25", out.getSimpleExpression(lang));
	}

	@Test
	public void modCastSum() {
		Expression d = e(1).divide(e(2)).add(e(2).multiply(e(4)));
		Expression c = e(3).add(e(4).multiply(e(5)));
		Expression b = c.add(e(2));
		Expression a = d.multiply(e(4));
		Expression out = b.add(a).toInt();
		out = new Mod(out, e(5), false);

		System.out.println(out.getSimpleExpression(lang));
	}

	@Test
	public void castFloorDivide() {
		Expression exp = e(0.0).divide(e(4.0)).floor()
				.multiply(e(4)).add(e(0.0).toInt()
						.mod(e(4), false)).toInt();
		System.out.println(exp.getExpression(lang));
		Assert.assertEquals("0", exp.getSimpleExpression(lang));
	}
}
