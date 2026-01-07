package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.data.ParameterSet;
import org.junit.Test;

import java.util.stream.IntStream;

public class ParameterFunctionTest {
	@Test
	public void power() {
		ParameterFunction f = ParameterFunction.random();
		IntStream.range(0, 10).mapToObj(i -> ParameterSet.random()).mapToDouble(f.power(2.0, 3, -3)::apply).forEach(System.out::println);
	}
}
