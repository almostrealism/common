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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Token representation for the Producer DSL (.pdsl) language.
 * Each token carries its type, textual value, and source location.
 */
public class PdslToken {

	/** Token types for the Producer DSL lexer. */
	public enum Type {
		/** {@code layer} keyword introducing a layer definition. */
		LAYER("layer"),
		/** {@code model} keyword introducing a model definition. */
		MODEL("model"),
		/**
		 * {@code import} keyword introducing an import statement. Followed by a string literal
		 * naming the classpath resource of another {@code .pdsl} asset whose definitions this
		 * file depends on.
		 */
		IMPORT("import"),
		/** {@code config} keyword introducing a configuration block. */
		CONFIG("config"),
		/** {@code data} keyword introducing a data block definition. */
		DATA("data"),
		/** {@code state} keyword introducing a state block definition. */
		STATE("state"),
		/** {@code let} keyword for local variable binding. */
		LET("let"),
		/** {@code return} keyword; {@code return expr} appends {@code expr} as an expression statement does. */
		RETURN("return"),
		/** {@code if} keyword: reserved, but no parser production consumes it, so PDSL has no conditional. */
		IF("if"),
		/** {@code else} keyword: reserved, but no parser production consumes it. */
		ELSE("else"),
		/** {@code for} keyword for iteration. */
		FOR("for"),
		/** {@code in} keyword in {@code for ... in ...} iteration syntax. */
		IN("in"),
		/**
		 * {@code stack} keyword: {@code stack group as name { body }} repeats the body once for
		 * each member of a weight group, in order, with no index the body can compute with.
		 */
		STACK("stack"),
		/** {@code as} keyword naming the member of a {@code stack} each repetition reads. */
		AS("as"),
		/** {@code branch} keyword: forks the current signal into a sub-block that runs alongside the main path. */
		BRANCH("branch"),
		/** {@code accum} keyword: a residual connection adding a sub-block's output to its input. */
		ACCUM("accum"),
		/** {@code product} keyword: the element-wise product of two sub-blocks applied to the same input. */
		PRODUCT("product"),
		/** {@code accum_blocks} keyword: the element-wise sum of N sub-blocks applied to the same input. */
		ACCUM_BLOCKS("accum_blocks"),
		/** {@code concat_blocks} keyword for concatenating outputs of N blocks applied to the same input. */
		CONCAT_BLOCKS("concat_blocks"),
		/** {@code weight} keyword marking a weight reference. */
		WEIGHT("weight"),
		/** {@code scalar} type annotation keyword. */
		SCALAR("scalar"),
		/**
		 * {@code producer} type annotation keyword. Followed by a shape literal in parentheses,
		 * e.g. {@code producer([1])}, marks a parameter bound to a
		 * {@link io.almostrealism.relation.Producer} of {@link org.almostrealism.collect.PackedCollection}
		 * with the given shape.
		 */
		PRODUCER("producer"),
		/** {@code int} type annotation keyword. */
		INT_TYPE("int"),
		/** {@code float} type annotation keyword. */
		FLOAT_TYPE("float"),
		/** {@code bool} type annotation keyword. */
		BOOL_TYPE("bool"),
		/** {@code true} boolean literal. */
		TRUE("true"),
		/** {@code false} boolean literal. */
		FALSE("false"),
		/** {@code null} literal. */
		NULL_LITERAL("null"),
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
		/** Arrow {@code ->} introducing a layer's shape annotation, the input shape it is built for when called. */
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
		/** Equality comparison {@code ==}; lexed, but no parser production consumes it. */
		EQ,
		/** Inequality comparison {@code !=}; lexed, but no parser production consumes it. */
		NEQ,
		/** Less-than operator {@code <}; lexed, but no parser production consumes it. */
		LT,
		/** Greater-than operator {@code >}; lexed, but no parser production consumes it. */
		GT,
		/** Less-than-or-equal operator {@code <=}; lexed, but no parser production consumes it. */
		LTE,
		/** Greater-than-or-equal operator {@code >=}; lexed, but no parser production consumes it. */
		GTE,
		/** End-of-file sentinel token. */
		EOF;

		/** Mapping from each reserved word to the keyword type it lexes as, in declaration order. */
		private static final Map<String, Type> KEYWORDS = new LinkedHashMap<>();

		static {
			for (Type type : values()) {
				if (type.isKeyword()) KEYWORDS.put(type.keyword, type);
			}
		}

		/** The reserved word this type is spelled as in source, or null if it is not a keyword. */
		private final String keyword;

		/** Constructs a type that is not a keyword. */
		Type() {
			this(null);
		}

		/**
		 * Constructs a keyword type.
		 *
		 * @param keyword the reserved word this type is spelled as in source
		 */
		Type(String keyword) {
			this.keyword = keyword;
		}

		/**
		 * Returns the reserved word this type is spelled as in source.
		 *
		 * @return the keyword, or null if this type is not a keyword
		 */
		public String getKeyword() { return keyword; }

		/**
		 * Returns the type a word lexes as: its keyword type if it is reserved,
		 * otherwise {@link #IDENTIFIER}.
		 *
		 * @param word the word to classify
		 * @return the keyword type for {@code word}, or {@link #IDENTIFIER}
		 */
		public static Type forWord(String word) {
			return KEYWORDS.getOrDefault(word, IDENTIFIER);
		}

		/**
		 * Returns whether this type is a reserved word of the language.
		 *
		 * @return true if this type is spelled as a keyword in source
		 */
		public boolean isKeyword() { return keyword != null; }

		/**
		 * Returns every reserved word of the language, including {@code if} and {@code else}, which
		 * are reserved but have no production in {@link PdslParser}.
		 *
		 * @return an unmodifiable view of the reserved words, in declaration order
		 */
		public static Set<String> keywords() {
			return Collections.unmodifiableSet(KEYWORDS.keySet());
		}
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

	/**
	 * Returns whether this token is spelled as a word: an identifier or a reserved word.
	 * A field name after {@code .} may be any word, so {@code weights.model.layers} names
	 * the checkpoint's {@code model.layers} group whatever words the language reserves.
	 *
	 * @return true if this token is an {@link Type#IDENTIFIER} or a keyword
	 */
	public boolean isWord() {
		return type == Type.IDENTIFIER || type.isKeyword();
	}

	@Override
	public String toString() {
		return type + "(" + value + ") at " + line + ":" + column;
	}
}
