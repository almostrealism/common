/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml.dsl;

/**
 * Token representation for the Producer DSL (.pdsl) language.
 * Each token carries its type, textual value, and source location.
 */
public class PdslToken {

	/** Token types for the Producer DSL lexer. */
	public enum Type {
		// Keywords
		LAYER, MODEL, CONFIG, LET, RETURN, IF, ELSE,
		FOR, IN, BRANCH, ACCUM, PRODUCT,
		WEIGHT, SCALAR, INT_TYPE, FLOAT_TYPE, BOOL_TYPE,
		TRUE, FALSE, NULL_LITERAL,

		// Literals and identifiers
		IDENTIFIER, NUMBER, STRING,

		// Delimiters
		LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
		COMMA, COLON, SEMICOLON, DOT, ARROW, DOTDOT,

		// Operators
		PLUS, MINUS, STAR, SLASH,
		EQUALS, EQ, NEQ, LT, GT, LTE, GTE,

		// Special
		EOF
	}

	private final Type type;
	private final String value;
	private final int line;
	private final int column;

	/**
	 * Construct a token with location information.
	 *
	 * @param type   the token type
	 * @param value  the textual value
	 * @param line   the source line (1-based)
	 * @param column the source column (1-based)
	 */
	public PdslToken(Type type, String value, int line, int column) {
		this.type = type;
		this.value = value;
		this.line = line;
		this.column = column;
	}

	/** Returns the token type. */
	public Type getType() { return type; }

	/** Returns the raw textual value of the token. */
	public String getValue() { return value; }

	/** Returns the 1-based source line number. */
	public int getLine() { return line; }

	/** Returns the 1-based source column number. */
	public int getColumn() { return column; }

	@Override
	public String toString() {
		return type + "(" + value + ") at " + line + ":" + column;
	}
}
