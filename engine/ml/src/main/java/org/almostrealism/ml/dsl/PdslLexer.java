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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lexer for the Producer DSL (.pdsl) language.
 * Converts source text into a stream of {@link PdslToken} instances,
 * handling keywords, identifiers, numbers (including scientific notation),
 * strings, operators, and delimiters.
 */
public class PdslLexer {

	private static final Map<String, PdslToken.Type> KEYWORDS = new HashMap<>();

	static {
		KEYWORDS.put("layer", PdslToken.Type.LAYER);
		KEYWORDS.put("model", PdslToken.Type.MODEL);
		KEYWORDS.put("config", PdslToken.Type.CONFIG);
		KEYWORDS.put("let", PdslToken.Type.LET);
		KEYWORDS.put("return", PdslToken.Type.RETURN);
		KEYWORDS.put("if", PdslToken.Type.IF);
		KEYWORDS.put("else", PdslToken.Type.ELSE);
		KEYWORDS.put("for", PdslToken.Type.FOR);
		KEYWORDS.put("in", PdslToken.Type.IN);
		KEYWORDS.put("branch", PdslToken.Type.BRANCH);
		KEYWORDS.put("accum", PdslToken.Type.ACCUM);
		KEYWORDS.put("product", PdslToken.Type.PRODUCT);
		KEYWORDS.put("add_blocks", PdslToken.Type.ADD_BLOCKS);
		KEYWORDS.put("weight", PdslToken.Type.WEIGHT);
		KEYWORDS.put("scalar", PdslToken.Type.SCALAR);
		KEYWORDS.put("int", PdslToken.Type.INT_TYPE);
		KEYWORDS.put("float", PdslToken.Type.FLOAT_TYPE);
		KEYWORDS.put("bool", PdslToken.Type.BOOL_TYPE);
		KEYWORDS.put("true", PdslToken.Type.TRUE);
		KEYWORDS.put("false", PdslToken.Type.FALSE);
		KEYWORDS.put("null", PdslToken.Type.NULL_LITERAL);
	}

	private final String source;
	private int pos;
	private int line;
	private int column;

	/**
	 * Create a lexer for the given source text.
	 *
	 * @param source the PDSL source code
	 */
	public PdslLexer(String source) {
		this.source = source;
		this.pos = 0;
		this.line = 1;
		this.column = 1;
	}

	/**
	 * Tokenize the entire source into a list of tokens,
	 * ending with an {@link PdslToken.Type#EOF} token.
	 *
	 * @return the token list
	 */
	public List<PdslToken> tokenize() {
		List<PdslToken> tokens = new ArrayList<>();
		while (pos < source.length()) {
			skipWhitespaceAndComments();
			if (pos >= source.length()) break;

			char ch = source.charAt(pos);

			if (Character.isLetter(ch) || ch == '_') {
				tokens.add(readIdentifierOrKeyword());
			} else if (Character.isDigit(ch)) {
				tokens.add(readNumber());
			} else if (ch == '"') {
				tokens.add(readString());
			} else {
				tokens.add(readOperatorOrDelimiter());
			}
		}
		tokens.add(new PdslToken(PdslToken.Type.EOF, "", line, column));
		return tokens;
	}

	private void skipWhitespaceAndComments() {
		while (pos < source.length()) {
			char ch = source.charAt(pos);
			if (ch == ' ' || ch == '\t' || ch == '\r') {
				advance();
			} else if (ch == '\n') {
				advance();
				line++;
				column = 1;
			} else if (ch == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
				while (pos < source.length() && source.charAt(pos) != '\n') {
					advance();
				}
			} else if (ch == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
				advance();
				advance();
				while (pos + 1 < source.length()
						&& !(source.charAt(pos) == '*' && source.charAt(pos + 1) == '/')) {
					if (source.charAt(pos) == '\n') {
						line++;
						column = 0;
					}
					advance();
				}
				if (pos + 1 < source.length()) {
					advance();
					advance();
				}
			} else {
				break;
			}
		}
	}

	private PdslToken readIdentifierOrKeyword() {
		int startLine = line;
		int startCol = column;
		StringBuilder sb = new StringBuilder();
		while (pos < source.length()
				&& (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
			sb.append(source.charAt(pos));
			advance();
		}
		String word = sb.toString();
		PdslToken.Type type = KEYWORDS.getOrDefault(word, PdslToken.Type.IDENTIFIER);
		return new PdslToken(type, word, startLine, startCol);
	}

	private PdslToken readNumber() {
		int startLine = line;
		int startCol = column;
		StringBuilder sb = new StringBuilder();

		while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
			sb.append(source.charAt(pos));
			advance();
		}

		if (pos < source.length() && source.charAt(pos) == '.'
				&& pos + 1 < source.length() && source.charAt(pos + 1) != '.') {
			sb.append('.');
			advance();
			while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
				sb.append(source.charAt(pos));
				advance();
			}
		}

		if (pos < source.length()
				&& (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
			sb.append(source.charAt(pos));
			advance();
			if (pos < source.length()
					&& (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
				sb.append(source.charAt(pos));
				advance();
			}
			while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
				sb.append(source.charAt(pos));
				advance();
			}
		}

		return new PdslToken(PdslToken.Type.NUMBER, sb.toString(), startLine, startCol);
	}

	private PdslToken readString() {
		int startLine = line;
		int startCol = column;
		advance(); // skip opening quote
		StringBuilder sb = new StringBuilder();
		while (pos < source.length() && source.charAt(pos) != '"') {
			if (source.charAt(pos) == '\\' && pos + 1 < source.length()) {
				advance();
				char escaped = source.charAt(pos);
				switch (escaped) {
					case 'n': sb.append('\n'); break;
					case 't': sb.append('\t'); break;
					case '\\': sb.append('\\'); break;
					case '"': sb.append('"'); break;
					default: sb.append('\\').append(escaped); break;
				}
			} else {
				sb.append(source.charAt(pos));
			}
			advance();
		}
		if (pos < source.length()) advance(); // skip closing quote
		return new PdslToken(PdslToken.Type.STRING, sb.toString(), startLine, startCol);
	}

	private PdslToken readOperatorOrDelimiter() {
		int startLine = line;
		int startCol = column;
		char ch = source.charAt(pos);
		advance();

		switch (ch) {
			case '(': return new PdslToken(PdslToken.Type.LPAREN, "(", startLine, startCol);
			case ')': return new PdslToken(PdslToken.Type.RPAREN, ")", startLine, startCol);
			case '{': return new PdslToken(PdslToken.Type.LBRACE, "{", startLine, startCol);
			case '}': return new PdslToken(PdslToken.Type.RBRACE, "}", startLine, startCol);
			case '[': return new PdslToken(PdslToken.Type.LBRACKET, "[", startLine, startCol);
			case ']': return new PdslToken(PdslToken.Type.RBRACKET, "]", startLine, startCol);
			case ',': return new PdslToken(PdslToken.Type.COMMA, ",", startLine, startCol);
			case ':': return new PdslToken(PdslToken.Type.COLON, ":", startLine, startCol);
			case ';': return new PdslToken(PdslToken.Type.SEMICOLON, ";", startLine, startCol);
			case '+': return new PdslToken(PdslToken.Type.PLUS, "+", startLine, startCol);
			case '*': return new PdslToken(PdslToken.Type.STAR, "*", startLine, startCol);
			case '/': return new PdslToken(PdslToken.Type.SLASH, "/", startLine, startCol);
			case '.':
				if (pos < source.length() && source.charAt(pos) == '.') {
					advance();
					return new PdslToken(PdslToken.Type.DOTDOT, "..", startLine, startCol);
				}
				return new PdslToken(PdslToken.Type.DOT, ".", startLine, startCol);
			case '-':
				if (pos < source.length() && source.charAt(pos) == '>') {
					advance();
					return new PdslToken(PdslToken.Type.ARROW, "->", startLine, startCol);
				}
				return new PdslToken(PdslToken.Type.MINUS, "-", startLine, startCol);
			case '=':
				if (pos < source.length() && source.charAt(pos) == '=') {
					advance();
					return new PdslToken(PdslToken.Type.EQ, "==", startLine, startCol);
				}
				return new PdslToken(PdslToken.Type.EQUALS, "=", startLine, startCol);
			case '!':
				if (pos < source.length() && source.charAt(pos) == '=') {
					advance();
					return new PdslToken(PdslToken.Type.NEQ, "!=", startLine, startCol);
				}
				throw new PdslParseException("Unexpected character '!' at " + startLine + ":" + startCol);
			case '<':
				if (pos < source.length() && source.charAt(pos) == '=') {
					advance();
					return new PdslToken(PdslToken.Type.LTE, "<=", startLine, startCol);
				}
				return new PdslToken(PdslToken.Type.LT, "<", startLine, startCol);
			case '>':
				if (pos < source.length() && source.charAt(pos) == '=') {
					advance();
					return new PdslToken(PdslToken.Type.GTE, ">=", startLine, startCol);
				}
				return new PdslToken(PdslToken.Type.GT, ">", startLine, startCol);
			default:
				throw new PdslParseException(
						"Unexpected character '" + ch + "' at " + startLine + ":" + startCol);
		}
	}

	private void advance() {
		pos++;
		column++;
	}
}
