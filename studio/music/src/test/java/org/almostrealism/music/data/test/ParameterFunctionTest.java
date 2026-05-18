package org.almostrealism.music.data.test;

import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.stream.IntStream;

public class ParameterFunctionTest extends TestSuiteBase {
	@Test(timeout = 10_000)
	public void power() {
		ParameterFunction f = ParameterFunction.random();
		IntStream.range(0, 10).mapToObj(i -> ParameterSet.random()).mapToDouble(f.power(2.0, 3, -3)::apply).forEach(System.out::println);
	}
}
