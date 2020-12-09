/*
 * Copyright 2020 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.code.expressions;

import java.util.stream.Stream;

public class NAryExpression extends Expression<Double> {
	public NAryExpression(String operator, Expression<Double>... values) {
		super(Double.class, concat(operator, Stream.of(values).map(Expression::getExpression).map(s -> "(" + s + ")")), values);
	}

	private static String concat(String separator, Stream<String> values) {
		StringBuffer buf = new StringBuffer();
		values.map(s -> " " + separator + " " + s).forEach(buf::append);
		return buf.toString().substring(separator.length() + 2);
	}
}
