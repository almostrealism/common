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
		/** {@code layer} keyword introducing a layer definition. */
		LAYER,
		/** {@code model} keyword introducing a model definition. */
		MODEL,
		/** {@code config} keyword introducing a configuration block. */
		CONFIG,
		/** {@code data} keyword introducing a data block definition. */
		DATA,
		/** {@code state} keyword introducing a state block definition. */
		STATE,
		/** {@code let} keyword for local variable binding. */
		LET,
		/** {@code return} keyword for returning a value. */
		RETURN,
		/** {@code if} keyword for conditional branching. */
		IF,
		/** {@code else} keyword for the alternative branch of a conditional. */
		ELSE,
		/** {@code for} keyword for iteration. */
		FOR,
		/** {@code in} keyword in {@code for ... in ...} iteration syntax. */
		IN,
		/** {@code branch} keyword for multi-way conditional dispatch. */
		BRANCH,
		/** {@code accum} keyword for accumulation expressions. */
		ACCUM,
		/** {@code product} keyword for product expressions. */
		PRODUCT,
		/** {@code accum_blocks} keyword for element-wise accumulation of two blocks. */
		ACCUM_BLOCKS,
		/** {@code concat_blocks} keyword for concatenating outputs of N blocks applied to the same input. */
		CONCAT_BLOCKS,
		/** {@code weight} keyword marking a weight reference. */
		WEIGHT,
		/** {@code scalar} type annotation keyword. */
		SCALAR,
		/**
		 * {@code producer} type annotation keyword. Followed by a shape literal in parentheses,
		 * e.g. {@code producer([1])}, marks a parameter bound to a
		 * {@link io.almostrealism.relation.Producer} of {@link org.almostrealism.collect.PackedCollection}
		 * with the given shape.
		 */
		PRODUCER,
		/** {@code int} type annotation keyword. */
		INT_TYPE,
		/** {@code float} type annotation keyword. */
		FLOAT_TYPE,
		/** {@code bool} type annotation keyword. */
		BOOL_TYPE,
		/** {@code true} boolean literal. */
		TRUE,
		/** {@code false} boolean literal. */
		FALSE,
		/** {@code null} literal. */
		NULL_LITERAL,
		/** An identifier token (variable name, function name, etc.). */
		IDENTIFIER,
		/** A numeric literal (integer or floating-point). */
		NUMBER,
		/** A string literal enclosed in quotes. */
		STRING,
		/** Left parenthesis {@code (}. */
		LPAREN,
		/** Right parenthesis {@code )}. */
		RPAREN,
		/** Left brace {@code \{}. */
		LBRACE,
		/** Right brace {@code \}}. */
		RBRACE,
		/** Left bracket {@code [}. */
		LBRACKET,
		/** Right bracket {@code ]}. */
		RBRACKET,
		/** Comma {@code ,}. */
		COMMA,
		/** Colon {@code :}. */
		COLON,
		/** Semicolon {@code ;}. */
		SEMICOLON,
		/** Dot {@code .} for member access. */
		DOT,
		/** Arrow {@code ->} for return type annotations. */
		ARROW,
		/** Double dot {@code ..} for range expressions. */
		DOTDOT,
		/** Addition operator {@code +}. */
		PLUS,
		/** Subtraction operator {@code -}. */
		MINUS,
		/** Multiplication operator {@code *}. */
		STAR,
		/** Division operator {@code /}. */
		SLASH,
		/** Assignment operator {@code =}. */
		EQUALS,
		/** Equality comparison {@code ==}. */
		EQ,
		/** Inequality comparison {@code !=}. */
		NEQ,
		/** Less-than operator {@code <}. */
		LT,
		/** Greater-than operator {@code >}. */
		GT,
		/** Less-than-or-equal operator {@code <=}. */
		LTE,
		/** Greater-than-or-equal operator {@code >=}. */
		GTE,
		/** End-of-file sentinel token. */
		EOF
	}

	/** The type of this token. */
	private final Type type;

	/** The raw textual value captured from the source. */
	private final String value;

	/** The 1-based source line number where this token appears. */
	private final int line;

	/** The 1-based source column number where this token starts. */
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
