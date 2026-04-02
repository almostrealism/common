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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive descent parser for the Producer DSL (.pdsl) language.
 * Converts a stream of {@link PdslToken} instances into an AST
 * rooted at {@link PdslNode.Program}.
 *
 * <p>Grammar overview:
 * <pre>
 * program      = definition*
 * definition   = config_def | layer_def | model_def
 * config_def   = 'config' IDENT '{' (IDENT '=' expr)* '}'
 * layer_def    = 'layer' IDENT '(' params ')' ('->' shape)? '{' body '}'
 * model_def    = 'model' IDENT '(' params ')' '{' body '}'
 * params       = param (',' param)*
 * param        = IDENT ':' type
 * type         = 'weight' shape? | 'scalar' | 'int' | 'float' | IDENT
 * shape        = '[' expr (',' expr)* ']'
 * body         = statement*
 * statement    = let_stmt | return_stmt | branch_stmt | accum_stmt
 *              | product_stmt | for_stmt | expr_stmt
 * expr         = addition
 * addition     = multiplication (('+' | '-') multiplication)*
 * multiplication = unary (('*' | '/') unary)*
 * unary        = ('-') unary | postfix
 * postfix      = primary ('(' args ')' | '.' IDENT)*
 * primary      = NUMBER | STRING | 'true' | 'false' | 'null'
 *              | IDENT | shape | '(' expr ')'
 * </pre>
 */
public class PdslParser {

	/** Token stream produced by the lexer. */
	private final List<PdslToken> tokens;

	/** Current read position in the token stream. */
	private int pos;

	/**
	 * Create a parser for the given token stream.
	 *
	 * @param tokens the tokens produced by {@link PdslLexer}
	 */
	public PdslParser(List<PdslToken> tokens) {
		this.tokens = tokens;
		this.pos = 0;
	}

	/**
	 * Parse the token stream into a complete program AST.
	 *
	 * @return the program node
	 */
	public PdslNode.Program parse() {
		List<PdslNode.Definition> definitions = new ArrayList<>();
		while (!check(PdslToken.Type.EOF)) {
			definitions.add(parseDefinition());
		}
		return new PdslNode.Program(definitions);
	}

	/**
	 * Parses a single top-level definition (config, layer, or model).
	 *
	 * @return The parsed definition node
	 */
	private PdslNode.Definition parseDefinition() {
		PdslToken token = peek();
		switch (token.getType()) {
			case CONFIG: return parseConfigDef();
			case DATA:   return parseDataDef();
			case LAYER:  return parseLayerDef();
			case MODEL:  return parseModelDef();
			default:
				throw error("Expected 'config', 'data', 'layer', or 'model' but found " + token);
		}
	}

	// ---- Config ----

	/**
	 * Parses a {@code config Name { key = expr; ... }} block.
	 *
	 * @return The parsed config definition node
	 */
	private PdslNode.ConfigDef parseConfigDef() {
		PdslToken kw = consume(PdslToken.Type.CONFIG);
		String name = consume(PdslToken.Type.IDENTIFIER).getValue();
		consume(PdslToken.Type.LBRACE);
		Map<String, PdslNode.Expression> entries = new LinkedHashMap<>();
		while (!check(PdslToken.Type.RBRACE)) {
			String key = consume(PdslToken.Type.IDENTIFIER).getValue();
			consume(PdslToken.Type.EQUALS);
			PdslNode.Expression value = parseExpression();
			entries.put(key, value);
		}
		consume(PdslToken.Type.RBRACE);
		return new PdslNode.ConfigDef(name, entries, kw.getLine(), kw.getColumn());
	}

	// ---- Layer ----

	/**
	 * Parses a {@code layer Name(params) -> shape? { body }} definition.
	 *
	 * @return The parsed layer definition node
	 */
	private PdslNode.LayerDef parseLayerDef() {
		PdslToken kw = consume(PdslToken.Type.LAYER);
		String name = consume(PdslToken.Type.IDENTIFIER).getValue();
		consume(PdslToken.Type.LPAREN);
		List<PdslNode.Parameter> params = parseParameterList();
		consume(PdslToken.Type.RPAREN);

		PdslNode.Expression returnShape = null;
		if (check(PdslToken.Type.ARROW)) {
			consume(PdslToken.Type.ARROW);
			returnShape = parseShapeLiteral();
		}

		consume(PdslToken.Type.LBRACE);
		List<PdslNode.Statement> body = parseBody();
		consume(PdslToken.Type.RBRACE);

		return new PdslNode.LayerDef(name, params, returnShape, body,
				kw.getLine(), kw.getColumn());
	}

	// ---- Model ----

	/**
	 * Parses a {@code model Name(params) { body }} definition.
	 *
	 * @return The parsed model definition node
	 */
	private PdslNode.ModelDef parseModelDef() {
		PdslToken kw = consume(PdslToken.Type.MODEL);
		String name = consume(PdslToken.Type.IDENTIFIER).getValue();
		consume(PdslToken.Type.LPAREN);
		List<PdslNode.Parameter> params = parseParameterList();
		consume(PdslToken.Type.RPAREN);
		consume(PdslToken.Type.LBRACE);
		List<PdslNode.Statement> body = parseBody();
		consume(PdslToken.Type.RBRACE);
		return new PdslNode.ModelDef(name, params, body,
				kw.getLine(), kw.getColumn());
	}

	// ---- Parameters ----

	/**
	 * Parses a comma-separated list of parameter declarations.
	 *
	 * @return List of parameter nodes, possibly empty
	 */
	private List<PdslNode.Parameter> parseParameterList() {
		List<PdslNode.Parameter> params = new ArrayList<>();
		if (!check(PdslToken.Type.RPAREN)) {
			params.add(parseParameter());
			while (check(PdslToken.Type.COMMA)) {
				consume(PdslToken.Type.COMMA);
				params.add(parseParameter());
			}
		}
		return params;
	}

	/**
	 * Parses a single parameter declaration: {@code name : type [shape]?}.
	 *
	 * @return The parsed parameter node
	 */
	private PdslNode.Parameter parseParameter() {
		PdslToken nameTok = consume(PdslToken.Type.IDENTIFIER);
		consume(PdslToken.Type.COLON);
		return parseParameterAfterColon(nameTok);
	}

	/**
	 * Parses the type portion of a parameter declaration after the colon has been consumed.
	 * Shared by {@link #parseParameter()} and {@link #parseDataDef()}.
	 *
	 * @param nameTok the already-consumed name token
	 * @return The parsed parameter node
	 */
	private PdslNode.Parameter parseParameterAfterColon(PdslToken nameTok) {
		String typeName;
		PdslNode.Expression shape = null;

		PdslToken typeTok = peek();
		switch (typeTok.getType()) {
			case WEIGHT:
				consume(PdslToken.Type.WEIGHT);
				typeName = "weight";
				if (check(PdslToken.Type.LBRACKET)) {
					shape = parseShapeLiteral();
				}
				break;
			case SCALAR:
				consume(PdslToken.Type.SCALAR);
				typeName = "scalar";
				break;
			case INT_TYPE:
				consume(PdslToken.Type.INT_TYPE);
				typeName = "int";
				break;
			case FLOAT_TYPE:
				consume(PdslToken.Type.FLOAT_TYPE);
				typeName = "float";
				break;
			case BOOL_TYPE:
				consume(PdslToken.Type.BOOL_TYPE);
				typeName = "bool";
				break;
			case IDENTIFIER:
				typeName = consume(PdslToken.Type.IDENTIFIER).getValue();
				break;
			case LBRACKET:
				typeName = "shape";
				shape = parseShapeLiteral();
				break;
			default:
				throw error("Expected type in parameter declaration but found " + typeTok);
		}

		return new PdslNode.Parameter(nameTok.getValue(), typeName, shape,
				nameTok.getLine(), nameTok.getColumn());
	}

	// ---- Data ----

	/**
	 * Parses a {@code data Name { entries }} block.
	 *
	 * <p>Each entry is either a parameter declaration ({@code name: type}) or
	 * a derivation ({@code name = expr}). Parameter declarations are satisfied
	 * from caller-supplied arguments at build time; derivations are evaluated in
	 * declaration order with earlier entries in scope for later expressions.</p>
	 *
	 * @return The parsed data definition node
	 */
	private PdslNode.DataDef parseDataDef() {
		PdslToken kw = consume(PdslToken.Type.DATA);
		String name = consume(PdslToken.Type.IDENTIFIER).getValue();
		consume(PdslToken.Type.LBRACE);

		List<PdslNode.Parameter> parameters = new ArrayList<>();
		Map<String, PdslNode.Expression> derivations = new LinkedHashMap<>();

		while (!check(PdslToken.Type.RBRACE) && !check(PdslToken.Type.EOF)) {
			PdslToken entryNameTok = consume(PdslToken.Type.IDENTIFIER);
			if (check(PdslToken.Type.COLON)) {
				consume(PdslToken.Type.COLON);
				parameters.add(parseParameterAfterColon(entryNameTok));
			} else {
				consume(PdslToken.Type.EQUALS);
				derivations.put(entryNameTok.getValue(), parseExpression());
			}
			while (check(PdslToken.Type.SEMICOLON)) advance();
		}

		consume(PdslToken.Type.RBRACE);
		return new PdslNode.DataDef(name, parameters, derivations, kw.getLine(), kw.getColumn());
	}

	// ---- Body (statements) ----

	/**
	 * Parses a sequence of statements until a closing brace or EOF.
	 *
	 * @return List of parsed statement nodes
	 */
	private List<PdslNode.Statement> parseBody() {
		List<PdslNode.Statement> stmts = new ArrayList<>();
		while (!check(PdslToken.Type.RBRACE) && !check(PdslToken.Type.EOF)) {
			stmts.add(parseStatement());
			while (check(PdslToken.Type.SEMICOLON)) advance(); // consume optional statement separators
		}
		return stmts;
	}

	/**
	 * Dispatches to the appropriate statement parser based on the current token.
	 *
	 * @return The parsed statement node
	 */
	private PdslNode.Statement parseStatement() {
		PdslToken token = peek();
		switch (token.getType()) {
			case LET: return parseLetStatement();
			case RETURN: return parseReturnStatement();
			case BRANCH: return parseBranchStatement();
			case ACCUM: return parseAccumStatement();
			case PRODUCT: return parseProductStatement();
			case ACCUM_BLOCKS: return parseAccumBlocksStatement();
			case CONCAT_BLOCKS: return parseConcatBlocksStatement();
			case FOR: return parseForStatement();
			default: return parseExpressionStatement();
		}
	}

	/**
	 * Parses a {@code let name = expr} or {@code let name = branch { ... }} statement.
	 *
	 * @return The parsed let statement node
	 */
	private PdslNode.LetStatement parseLetStatement() {
		PdslToken kw = consume(PdslToken.Type.LET);
		String name = consume(PdslToken.Type.IDENTIFIER).getValue();
		consume(PdslToken.Type.EQUALS);

		PdslNode.Expression value;
		if (check(PdslToken.Type.BRANCH)) {
			// let x = branch { ... } -- inline branch assignment
			consume(PdslToken.Type.BRANCH);
			consume(PdslToken.Type.LBRACE);
			List<PdslNode.Statement> body = parseBody();
			consume(PdslToken.Type.RBRACE);
			value = new PdslNode.InlineBlock(body, kw.getLine(), kw.getColumn());
		} else {
			value = parseExpression();
		}

		return new PdslNode.LetStatement(name, value, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses a {@code return expr} statement.
	 *
	 * @return The parsed return statement node
	 */
	private PdslNode.ReturnStatement parseReturnStatement() {
		PdslToken kw = consume(PdslToken.Type.RETURN);
		PdslNode.Expression value = parseExpression();
		return new PdslNode.ReturnStatement(value, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses a {@code branch name? { body }} statement.
	 *
	 * @return The parsed branch statement node
	 */
	private PdslNode.BranchStatement parseBranchStatement() {
		PdslToken kw = consume(PdslToken.Type.BRANCH);
		String name = null;
		if (check(PdslToken.Type.IDENTIFIER)) {
			name = consume(PdslToken.Type.IDENTIFIER).getValue();
		}
		consume(PdslToken.Type.LBRACE);
		List<PdslNode.Statement> body = parseBody();
		consume(PdslToken.Type.RBRACE);
		return new PdslNode.BranchStatement(name, body, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses an {@code accum { body }} (residual connection) statement.
	 *
	 * @return The parsed accumulation statement node
	 */
	private PdslNode.AccumStatement parseAccumStatement() {
		PdslToken kw = consume(PdslToken.Type.ACCUM);
		consume(PdslToken.Type.LBRACE);
		List<PdslNode.Statement> body = parseBody();
		consume(PdslToken.Type.RBRACE);
		return new PdslNode.AccumStatement(body, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses a {@code product(left, right)} element-wise multiplication statement.
	 *
	 * @return The parsed product statement node
	 */
	private PdslNode.ProductStatement parseProductStatement() {
		PdslToken kw = consume(PdslToken.Type.PRODUCT);
		consume(PdslToken.Type.LPAREN);
		PdslNode.Expression left = parseBlockArg();
		consume(PdslToken.Type.COMMA);
		PdslNode.Expression right = parseBlockArg();
		consume(PdslToken.Type.RPAREN);
		return new PdslNode.ProductStatement(left, right, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses an {@code accum_blocks(left, right)} statement.
	 *
	 * @return the parsed {@link PdslNode.AccumBlocksStatement}
	 */
	private PdslNode.AccumBlocksStatement parseAccumBlocksStatement() {
		PdslToken kw = consume(PdslToken.Type.ACCUM_BLOCKS);
		consume(PdslToken.Type.LPAREN);
		PdslNode.Expression left = parseBlockArg();
		consume(PdslToken.Type.COMMA);
		PdslNode.Expression right = parseBlockArg();
		consume(PdslToken.Type.RPAREN);
		return new PdslNode.AccumBlocksStatement(left, right, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses a {@code concat_blocks(block1, block2, ...)} statement with two or more block arguments.
	 *
	 * @return the parsed {@link PdslNode.ConcatBlocksStatement}
	 */
	private PdslNode.ConcatBlocksStatement parseConcatBlocksStatement() {
		PdslToken kw = consume(PdslToken.Type.CONCAT_BLOCKS);
		consume(PdslToken.Type.LPAREN);
		List<PdslNode.Expression> blocks = new ArrayList<>();
		blocks.add(parseBlockArg());
		while (check(PdslToken.Type.COMMA)) {
			consume(PdslToken.Type.COMMA);
			blocks.add(parseBlockArg());
		}
		consume(PdslToken.Type.RPAREN);
		return new PdslNode.ConcatBlocksStatement(blocks, kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses one argument of a block expression, which may be an inline block {@code { ... }},
	 * a nested {@code product(...)}, {@code accum_blocks(...)}, or {@code concat_blocks(...)}
	 * statement (each wrapped in a synthetic inline block), or a plain expression.
	 *
	 * @return the parsed expression node
	 */
	private PdslNode.Expression parseBlockArg() {
		if (check(PdslToken.Type.LBRACE)) {
			PdslToken brace = consume(PdslToken.Type.LBRACE);
			List<PdslNode.Statement> body = parseBody();
			consume(PdslToken.Type.RBRACE);
			return new PdslNode.InlineBlock(body, brace.getLine(), brace.getColumn());
		} else if (check(PdslToken.Type.PRODUCT)) {
			// Wrap a product statement inside a synthetic inline block
			int line = peek().getLine();
			int col = peek().getColumn();
			PdslNode.ProductStatement productStmt = parseProductStatement();
			List<PdslNode.Statement> body = new ArrayList<>();
			body.add(productStmt);
			return new PdslNode.InlineBlock(body, line, col);
		} else if (check(PdslToken.Type.ACCUM_BLOCKS)) {
			// Wrap an accum_blocks statement inside a synthetic inline block
			int line = peek().getLine();
			int col = peek().getColumn();
			PdslNode.AccumBlocksStatement addStmt = parseAccumBlocksStatement();
			List<PdslNode.Statement> body = new ArrayList<>();
			body.add(addStmt);
			return new PdslNode.InlineBlock(body, line, col);
		} else if (check(PdslToken.Type.CONCAT_BLOCKS)) {
			// Wrap a concat_blocks statement inside a synthetic inline block
			int line = peek().getLine();
			int col = peek().getColumn();
			PdslNode.ConcatBlocksStatement concatStmt = parseConcatBlocksStatement();
			List<PdslNode.Statement> body = new ArrayList<>();
			body.add(concatStmt);
			return new PdslNode.InlineBlock(body, line, col);
		}
		return parseExpression();
	}

	/**
	 * Parses a {@code for variable in start..end { body }} loop statement.
	 *
	 * @return The parsed for statement node
	 */
	private PdslNode.ForStatement parseForStatement() {
		PdslToken kw = consume(PdslToken.Type.FOR);
		String variable = consume(PdslToken.Type.IDENTIFIER).getValue();
		consume(PdslToken.Type.IN);
		PdslNode.Expression start = parseExpression();
		consume(PdslToken.Type.DOTDOT);
		PdslNode.Expression end = parseExpression();
		consume(PdslToken.Type.LBRACE);
		List<PdslNode.Statement> body = parseBody();
		consume(PdslToken.Type.RBRACE);
		return new PdslNode.ForStatement(variable, start, end, body,
				kw.getLine(), kw.getColumn());
	}

	/**
	 * Parses an expression used as a standalone statement.
	 *
	 * @return The parsed expression statement node
	 */
	private PdslNode.ExpressionStatement parseExpressionStatement() {
		PdslNode.Expression expr = parseExpression();
		return new PdslNode.ExpressionStatement(expr, expr.getLine(), expr.getColumn());
	}

	// ---- Expressions (precedence climbing) ----

	/**
	 * Parses an expression (entry point for precedence-climbing grammar).
	 *
	 * @return The parsed expression node
	 */
	private PdslNode.Expression parseExpression() {
		return parseAddition();
	}

	/**
	 * Parses additive expressions ({@code +} and {@code -}).
	 *
	 * @return The parsed expression node
	 */
	private PdslNode.Expression parseAddition() {
		PdslNode.Expression left = parseMultiplication();
		while (check(PdslToken.Type.PLUS) || check(PdslToken.Type.MINUS)) {
			PdslToken op = advance();
			PdslNode.Expression right = parseMultiplication();
			left = new PdslNode.BinaryOp(left, op.getValue(), right,
					op.getLine(), op.getColumn());
		}
		return left;
	}

	/**
	 * Parses multiplicative expressions ({@code *} and {@code /}).
	 *
	 * @return The parsed expression node
	 */
	private PdslNode.Expression parseMultiplication() {
		PdslNode.Expression left = parseUnary();
		while (check(PdslToken.Type.STAR) || check(PdslToken.Type.SLASH)) {
			PdslToken op = advance();
			PdslNode.Expression right = parseUnary();
			left = new PdslNode.BinaryOp(left, op.getValue(), right,
					op.getLine(), op.getColumn());
		}
		return left;
	}

	/**
	 * Parses a unary negation ({@code -}) or delegates to postfix.
	 *
	 * @return The parsed expression node
	 */
	private PdslNode.Expression parseUnary() {
		if (check(PdslToken.Type.MINUS)) {
			PdslToken op = advance();
			PdslNode.Expression operand = parseUnary();
			return new PdslNode.UnaryOp(op.getValue(), operand,
					op.getLine(), op.getColumn());
		}
		return parsePostfix();
	}

	/**
	 * Parses postfix operations: function calls and field accesses.
	 *
	 * @return The parsed expression node
	 */
	private PdslNode.Expression parsePostfix() {
		PdslNode.Expression expr = parsePrimary();

		while (true) {
			if (check(PdslToken.Type.LPAREN) && expr instanceof PdslNode.Identifier) {
				String funcName = ((PdslNode.Identifier) expr).getName();
				consume(PdslToken.Type.LPAREN);
				List<PdslNode.Expression> args = parseArgumentList();
				consume(PdslToken.Type.RPAREN);
				expr = new PdslNode.FunctionCall(funcName, args,
						expr.getLine(), expr.getColumn());
			} else if (check(PdslToken.Type.DOT)) {
				consume(PdslToken.Type.DOT);
				String field = consume(PdslToken.Type.IDENTIFIER).getValue();
				if (check(PdslToken.Type.LPAREN)) {
					// Method call: expr.method(args)
					consume(PdslToken.Type.LPAREN);
					List<PdslNode.Expression> args = parseArgumentList();
					consume(PdslToken.Type.RPAREN);
					List<PdslNode.Expression> allArgs = new ArrayList<>();
					allArgs.add(expr);
					allArgs.addAll(args);
					expr = new PdslNode.FunctionCall(field, allArgs,
							expr.getLine(), expr.getColumn());
				} else {
					expr = new PdslNode.FieldAccess(expr, field,
							expr.getLine(), expr.getColumn());
				}
			} else {
				break;
			}
		}

		return expr;
	}

	/**
	 * Parses a primary expression: literal, identifier, grouped expression, or shape literal.
	 *
	 * @return The parsed expression node
	 */
	private PdslNode.Expression parsePrimary() {
		PdslToken token = peek();

		switch (token.getType()) {
			case NUMBER:
				advance();
				return new PdslNode.NumberLiteral(
						Double.parseDouble(token.getValue()),
						token.getLine(), token.getColumn());

			case STRING:
				advance();
				return new PdslNode.StringLiteral(token.getValue(),
						token.getLine(), token.getColumn());

			case TRUE:
				advance();
				return new PdslNode.BoolLiteral(true,
						token.getLine(), token.getColumn());

			case FALSE:
				advance();
				return new PdslNode.BoolLiteral(false,
						token.getLine(), token.getColumn());

			case NULL_LITERAL:
				advance();
				return new PdslNode.NullLiteral(
						token.getLine(), token.getColumn());

			case WEIGHT:
				advance();
				if (check(PdslToken.Type.LPAREN)) {
					consume(PdslToken.Type.LPAREN);
					PdslNode.Expression key = parseExpression();
					consume(PdslToken.Type.RPAREN);
					return new PdslNode.WeightRef(key,
							token.getLine(), token.getColumn());
				}
				return new PdslNode.Identifier("weight",
						token.getLine(), token.getColumn());

			case IDENTIFIER:
				advance();
				return new PdslNode.Identifier(token.getValue(),
						token.getLine(), token.getColumn());

			case LBRACKET:
				return parseShapeLiteral();

			case LPAREN:
				advance();
				PdslNode.Expression expr = parseExpression();
				consume(PdslToken.Type.RPAREN);
				return expr;

			default:
				throw error("Expected expression but found " + token);
		}
	}

	/**
	 * Parses a shape literal: {@code [dim1, dim2, ...]}.
	 *
	 * @return The parsed shape literal node
	 */
	private PdslNode.ShapeLiteral parseShapeLiteral() {
		PdslToken bracket = consume(PdslToken.Type.LBRACKET);
		List<PdslNode.Expression> dims = new ArrayList<>();
		if (!check(PdslToken.Type.RBRACKET)) {
			dims.add(parseExpression());
			while (check(PdslToken.Type.COMMA)) {
				consume(PdslToken.Type.COMMA);
				dims.add(parseExpression());
			}
		}
		consume(PdslToken.Type.RBRACKET);
		return new PdslNode.ShapeLiteral(dims, bracket.getLine(), bracket.getColumn());
	}

	/**
	 * Parses a comma-separated function argument list (without surrounding parentheses).
	 *
	 * @return List of parsed argument expression nodes, possibly empty
	 */
	private List<PdslNode.Expression> parseArgumentList() {
		List<PdslNode.Expression> args = new ArrayList<>();
		if (!check(PdslToken.Type.RPAREN)) {
			args.add(parseExpression());
			while (check(PdslToken.Type.COMMA)) {
				consume(PdslToken.Type.COMMA);
				args.add(parseExpression());
			}
		}
		return args;
	}

	// ---- Token utilities ----

	/**
	 * Returns the current token without consuming it.
	 *
	 * @return The current token
	 */
	private PdslToken peek() {
		return tokens.get(pos);
	}

	/**
	 * Returns {@code true} if the current token has the given type.
	 *
	 * @param type Token type to check
	 * @return Whether the current token matches
	 */
	private boolean check(PdslToken.Type type) {
		return pos < tokens.size() && tokens.get(pos).getType() == type;
	}

	/**
	 * Returns the current token and advances the position.
	 *
	 * @return The consumed token
	 */
	private PdslToken advance() {
		PdslToken token = tokens.get(pos);
		pos++;
		return token;
	}

	/**
	 * Consumes and returns the current token, asserting it has the expected type.
	 *
	 * @param type Expected token type
	 * @return The consumed token
	 * @throws PdslParseException If the current token does not match the expected type
	 */
	private PdslToken consume(PdslToken.Type type) {
		PdslToken token = peek();
		if (token.getType() != type) {
			throw error("Expected " + type + " but found " + token);
		}
		return advance();
	}

	/**
	 * Creates a parse exception with the current token's source location appended.
	 *
	 * @param message Error description
	 * @return A {@link PdslParseException} with location information
	 */
	private PdslParseException error(String message) {
		PdslToken token = peek();
		return new PdslParseException(message + " at line " + token.getLine()
				+ ", column " + token.getColumn());
	}
}
