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

	private final List<PdslToken> tokens;
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

	private PdslNode.Definition parseDefinition() {
		PdslToken token = peek();
		switch (token.getType()) {
			case CONFIG: return parseConfigDef();
			case LAYER: return parseLayerDef();
			case MODEL: return parseModelDef();
			default:
				throw error("Expected 'config', 'layer', or 'model' but found " + token);
		}
	}

	// ---- Config ----

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

	private PdslNode.Parameter parseParameter() {
		PdslToken nameTok = consume(PdslToken.Type.IDENTIFIER);
		consume(PdslToken.Type.COLON);

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

	// ---- Body (statements) ----

	private List<PdslNode.Statement> parseBody() {
		List<PdslNode.Statement> stmts = new ArrayList<>();
		while (!check(PdslToken.Type.RBRACE) && !check(PdslToken.Type.EOF)) {
			stmts.add(parseStatement());
			while (check(PdslToken.Type.SEMICOLON)) advance(); // consume optional statement separators
		}
		return stmts;
	}

	private PdslNode.Statement parseStatement() {
		PdslToken token = peek();
		switch (token.getType()) {
			case LET: return parseLetStatement();
			case RETURN: return parseReturnStatement();
			case BRANCH: return parseBranchStatement();
			case ACCUM: return parseAccumStatement();
			case PRODUCT: return parseProductStatement();
			case ADD_BLOCKS: return parseAddBlocksStatement();
			case CONCAT_BLOCKS: return parseConcatBlocksStatement();
			case FOR: return parseForStatement();
			default: return parseExpressionStatement();
		}
	}

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

	private PdslNode.ReturnStatement parseReturnStatement() {
		PdslToken kw = consume(PdslToken.Type.RETURN);
		PdslNode.Expression value = parseExpression();
		return new PdslNode.ReturnStatement(value, kw.getLine(), kw.getColumn());
	}

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

	private PdslNode.AccumStatement parseAccumStatement() {
		PdslToken kw = consume(PdslToken.Type.ACCUM);
		consume(PdslToken.Type.LBRACE);
		List<PdslNode.Statement> body = parseBody();
		consume(PdslToken.Type.RBRACE);
		return new PdslNode.AccumStatement(body, kw.getLine(), kw.getColumn());
	}

	private PdslNode.ProductStatement parseProductStatement() {
		PdslToken kw = consume(PdslToken.Type.PRODUCT);
		consume(PdslToken.Type.LPAREN);
		PdslNode.Expression left = parseBlockArg();
		consume(PdslToken.Type.COMMA);
		PdslNode.Expression right = parseBlockArg();
		consume(PdslToken.Type.RPAREN);
		return new PdslNode.ProductStatement(left, right, kw.getLine(), kw.getColumn());
	}

	private PdslNode.AddBlocksStatement parseAddBlocksStatement() {
		PdslToken kw = consume(PdslToken.Type.ADD_BLOCKS);
		consume(PdslToken.Type.LPAREN);
		PdslNode.Expression left = parseBlockArg();
		consume(PdslToken.Type.COMMA);
		PdslNode.Expression right = parseBlockArg();
		consume(PdslToken.Type.RPAREN);
		return new PdslNode.AddBlocksStatement(left, right, kw.getLine(), kw.getColumn());
	}

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
	 * Parse an argument that can be an inline block {@code { ... }},
	 * a nested {@code product(...)} statement (wrapped in a synthetic inline block),
	 * a nested {@code add_blocks(...)} statement (wrapped in a synthetic inline block),
	 * or a plain expression.
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
		} else if (check(PdslToken.Type.ADD_BLOCKS)) {
			// Wrap an add_blocks statement inside a synthetic inline block
			int line = peek().getLine();
			int col = peek().getColumn();
			PdslNode.AddBlocksStatement addStmt = parseAddBlocksStatement();
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

	private PdslNode.ExpressionStatement parseExpressionStatement() {
		PdslNode.Expression expr = parseExpression();
		return new PdslNode.ExpressionStatement(expr, expr.getLine(), expr.getColumn());
	}

	// ---- Expressions (precedence climbing) ----

	private PdslNode.Expression parseExpression() {
		return parseAddition();
	}

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

	private PdslNode.Expression parseUnary() {
		if (check(PdslToken.Type.MINUS)) {
			PdslToken op = advance();
			PdslNode.Expression operand = parseUnary();
			return new PdslNode.UnaryOp(op.getValue(), operand,
					op.getLine(), op.getColumn());
		}
		return parsePostfix();
	}

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

	private PdslToken peek() {
		return tokens.get(pos);
	}

	private boolean check(PdslToken.Type type) {
		return pos < tokens.size() && tokens.get(pos).getType() == type;
	}

	private PdslToken advance() {
		PdslToken token = tokens.get(pos);
		pos++;
		return token;
	}

	private PdslToken consume(PdslToken.Type type) {
		PdslToken token = peek();
		if (token.getType() != type) {
			throw error("Expected " + type + " but found " + token);
		}
		return advance();
	}

	private PdslParseException error(String message) {
		PdslToken token = peek();
		return new PdslParseException(message + " at line " + token.getLine()
				+ ", column " + token.getColumn());
	}
}
