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

import java.util.List;
import java.util.Map;

/**
 * Abstract syntax tree (AST) nodes for the Producer DSL.
 * All node types are defined as static inner classes for
 * cohesive grouping.
 */
public abstract class PdslNode {

	private final int line;
	private final int column;

	protected PdslNode(int line, int column) {
		this.line = line;
		this.column = column;
	}

	/** Source line number for error reporting. */
	public int getLine() { return line; }

	/** Source column number for error reporting. */
	public int getColumn() { return column; }

	// ---- Top-level structures ----

	/** A complete PDSL program: a list of definitions. */
	public static class Program extends PdslNode {
		private final List<Definition> definitions;

		public Program(List<Definition> definitions) {
			super(1, 1);
			this.definitions = definitions;
		}

		public List<Definition> getDefinitions() { return definitions; }
	}

	/** Base class for top-level definitions (layer, model, config). */
	public abstract static class Definition extends PdslNode {
		private final String name;

		protected Definition(String name, int line, int column) {
			super(line, column);
			this.name = name;
		}

		public String getName() { return name; }
	}

	/** A config block defining named constants. */
	public static class ConfigDef extends Definition {
		private final Map<String, Expression> entries;

		public ConfigDef(String name, Map<String, Expression> entries, int line, int column) {
			super(name, line, column);
			this.entries = entries;
		}

		public Map<String, Expression> getEntries() { return entries; }
	}

	/** A layer definition: reusable block builder. */
	public static class LayerDef extends Definition {
		private final List<Parameter> parameters;
		private final Expression returnShape;
		private final List<Statement> body;

		public LayerDef(String name, List<Parameter> parameters,
						Expression returnShape, List<Statement> body,
						int line, int column) {
			super(name, line, column);
			this.parameters = parameters;
			this.returnShape = returnShape;
			this.body = body;
		}

		public List<Parameter> getParameters() { return parameters; }

		/** The declared return shape, or null if omitted. */
		public Expression getReturnShape() { return returnShape; }

		public List<Statement> getBody() { return body; }
	}

	/** A model definition: top-level model builder. */
	public static class ModelDef extends Definition {
		private final List<Parameter> parameters;
		private final List<Statement> body;

		public ModelDef(String name, List<Parameter> parameters,
						List<Statement> body, int line, int column) {
			super(name, line, column);
			this.parameters = parameters;
			this.body = body;
		}

		public List<Parameter> getParameters() { return parameters; }

		public List<Statement> getBody() { return body; }
	}

	// ---- Parameters ----

	/** A parameter declaration in a layer or model definition. */
	public static class Parameter extends PdslNode {
		private final String name;
		private final String typeName;
		private final Expression shape;

		/**
		 * @param name     parameter name
		 * @param typeName type keyword (weight, scalar, int, float, or a user-defined config name)
		 * @param shape    shape expression for weight types, or null
		 */
		public Parameter(String name, String typeName, Expression shape,
						 int line, int column) {
			super(line, column);
			this.name = name;
			this.typeName = typeName;
			this.shape = shape;
		}

		public String getName() { return name; }

		public String getTypeName() { return typeName; }

		/** Shape annotation (for weight parameters), or null. */
		public Expression getShape() { return shape; }
	}

	// ---- Statements ----

	/** Base class for statements within a block body. */
	public abstract static class Statement extends PdslNode {
		protected Statement(int line, int column) {
			super(line, column);
		}
	}

	/** Variable binding: {@code let name = expression}. */
	public static class LetStatement extends Statement {
		private final String name;
		private final Expression value;

		public LetStatement(String name, Expression value, int line, int column) {
			super(line, column);
			this.name = name;
			this.value = value;
		}

		public String getName() { return name; }

		public Expression getValue() { return value; }
	}

	/** Return statement: {@code return expression}. */
	public static class ReturnStatement extends Statement {
		private final Expression value;

		public ReturnStatement(Expression value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		public Expression getValue() { return value; }
	}

	/** Branch statement: {@code branch name { body }}. */
	public static class BranchStatement extends Statement {
		private final String name;
		private final List<Statement> body;

		public BranchStatement(String name, List<Statement> body,
							   int line, int column) {
			super(line, column);
			this.name = name;
			this.body = body;
		}

		/** The branch name (used to reference from product/attention). */
		public String getName() { return name; }

		public List<Statement> getBody() { return body; }
	}

	/** Accumulation (residual connection): {@code accum { body }}. */
	public static class AccumStatement extends Statement {
		private final List<Statement> body;

		public AccumStatement(List<Statement> body, int line, int column) {
			super(line, column);
			this.body = body;
		}

		public List<Statement> getBody() { return body; }
	}

	/**
	 * Product statement: element-wise multiply of two sub-blocks.
	 * {@code product(blockA, blockB)}
	 */
	public static class ProductStatement extends Statement {
		private final Expression left;
		private final Expression right;

		public ProductStatement(Expression left, Expression right,
								int line, int column) {
			super(line, column);
			this.left = left;
			this.right = right;
		}

		public Expression getLeft() { return left; }

		public Expression getRight() { return right; }
	}

	/**
	 * ConcatBlocks statement: concatenation of N sub-block outputs.
	 * All sub-blocks receive the same input; their outputs are concatenated in order.
	 * {@code concat_blocks(blockA, blockB, ...)}
	 */
	public static class ConcatBlocksStatement extends Statement {
		private final List<Expression> blocks;

		public ConcatBlocksStatement(List<Expression> blocks, int line, int column) {
			super(line, column);
			this.blocks = blocks;
		}

		public List<Expression> getBlocks() { return blocks; }
	}

	/**
	 * AccumBlocks statement: element-wise accumulation (sum) of two sub-block outputs.
	 * Both sub-blocks receive the same input; their outputs are summed.
	 * {@code accum_blocks(blockA, blockB)}
	 */
	public static class AccumBlocksStatement extends Statement {
		private final Expression left;
		private final Expression right;

		public AccumBlocksStatement(Expression left, Expression right,
								  int line, int column) {
			super(line, column);
			this.left = left;
			this.right = right;
		}

		public Expression getLeft() { return left; }

		public Expression getRight() { return right; }
	}

	/** For-loop: {@code for i in start..end { body }}. */
	public static class ForStatement extends Statement {
		private final String variable;
		private final Expression start;
		private final Expression end;
		private final List<Statement> body;

		public ForStatement(String variable, Expression start, Expression end,
							List<Statement> body, int line, int column) {
			super(line, column);
			this.variable = variable;
			this.start = start;
			this.end = end;
			this.body = body;
		}

		public String getVariable() { return variable; }

		public Expression getStart() { return start; }

		public Expression getEnd() { return end; }

		public List<Statement> getBody() { return body; }
	}

	/** An expression used as a statement (typically a function call that adds a block). */
	public static class ExpressionStatement extends Statement {
		private final Expression expression;

		public ExpressionStatement(Expression expression, int line, int column) {
			super(line, column);
			this.expression = expression;
		}

		public Expression getExpression() { return expression; }
	}

	// ---- Expressions ----

	/** Base class for all expressions. */
	public abstract static class Expression extends PdslNode {
		protected Expression(int line, int column) {
			super(line, column);
		}
	}

	/** Numeric literal. */
	public static class NumberLiteral extends Expression {
		private final double value;

		public NumberLiteral(double value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		public double getValue() { return value; }
	}

	/** String literal. */
	public static class StringLiteral extends Expression {
		private final String value;

		public StringLiteral(String value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		public String getValue() { return value; }
	}

	/** Boolean literal. */
	public static class BoolLiteral extends Expression {
		private final boolean value;

		public BoolLiteral(boolean value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		public boolean getValue() { return value; }
	}

	/** Null literal. */
	public static class NullLiteral extends Expression {
		public NullLiteral(int line, int column) {
			super(line, column);
		}
	}

	/** Identifier reference. */
	public static class Identifier extends Expression {
		private final String name;

		public Identifier(String name, int line, int column) {
			super(line, column);
			this.name = name;
		}

		public String getName() { return name; }
	}

	/** Shape literal: {@code [dim1, dim2, ...]}. */
	public static class ShapeLiteral extends Expression {
		private final List<Expression> dimensions;

		public ShapeLiteral(List<Expression> dimensions, int line, int column) {
			super(line, column);
			this.dimensions = dimensions;
		}

		public List<Expression> getDimensions() { return dimensions; }
	}

	/** Function call: {@code name(arg1, arg2, ...)}. */
	public static class FunctionCall extends Expression {
		private final String name;
		private final List<Expression> arguments;

		public FunctionCall(String name, List<Expression> arguments,
							int line, int column) {
			super(line, column);
			this.name = name;
			this.arguments = arguments;
		}

		public String getName() { return name; }

		public List<Expression> getArguments() { return arguments; }
	}

	/** Binary operation: {@code left op right}. */
	public static class BinaryOp extends Expression {
		private final Expression left;
		private final String operator;
		private final Expression right;

		public BinaryOp(Expression left, String operator, Expression right,
						int line, int column) {
			super(line, column);
			this.left = left;
			this.operator = operator;
			this.right = right;
		}

		public Expression getLeft() { return left; }

		public String getOperator() { return operator; }

		public Expression getRight() { return right; }
	}

	/** Unary operation: {@code -expr}. */
	public static class UnaryOp extends Expression {
		private final String operator;
		private final Expression operand;

		public UnaryOp(String operator, Expression operand, int line, int column) {
			super(line, column);
			this.operator = operator;
			this.operand = operand;
		}

		public String getOperator() { return operator; }

		public Expression getOperand() { return operand; }
	}

	/** Field access: {@code expr.field}. */
	public static class FieldAccess extends Expression {
		private final Expression object;
		private final String field;

		public FieldAccess(Expression object, String field, int line, int column) {
			super(line, column);
			this.object = object;
			this.field = field;
		}

		public Expression getObject() { return object; }

		public String getField() { return field; }
	}

	/**
	 * Inline block expression used inside product:
	 * {@code block { stmt; stmt; }}
	 */
	public static class InlineBlock extends Expression {
		private final List<Statement> body;

		public InlineBlock(List<Statement> body, int line, int column) {
			super(line, column);
			this.body = body;
		}

		public List<Statement> getBody() { return body; }
	}

	/**
	 * Weight reference expression: {@code weight("key.path")}.
	 * Used in model definitions to reference StateDictionary keys.
	 */
	public static class WeightRef extends Expression {
		private final Expression keyExpression;

		public WeightRef(Expression keyExpression, int line, int column) {
			super(line, column);
			this.keyExpression = keyExpression;
		}

		public Expression getKeyExpression() { return keyExpression; }
	}
}
