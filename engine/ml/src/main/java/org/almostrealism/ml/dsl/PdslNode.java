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

	/** Source line number of this node, used for error reporting. */
	private final int line;

	/** Source column number of this node, used for error reporting. */
	private final int column;

	/**
	 * Constructs a node with source location information.
	 *
	 * @param line   1-based source line number
	 * @param column 1-based source column number
	 */
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
		/** Top-level definitions (layers, models, configs) that make up this program. */
		private final List<Definition> definitions;

		/**
		 * Constructs a program node from a list of top-level definitions.
		 *
		 * @param definitions Top-level definitions in source order
		 */
		public Program(List<Definition> definitions) {
			super(1, 1);
			this.definitions = definitions;
		}

		/** Returns the ordered list of top-level definitions. */
		public List<Definition> getDefinitions() { return definitions; }
	}

	/** Base class for top-level definitions (layer, model, config). */
	public abstract static class Definition extends PdslNode {
		/** Identifier name given to this definition in the PDSL source. */
		private final String name;

		/**
		 * Constructs a definition node.
		 *
		 * @param name   Identifier name as declared in the source
		 * @param line   Source line number
		 * @param column Source column number
		 */
		protected Definition(String name, int line, int column) {
			super(line, column);
			this.name = name;
		}

		/** Returns the definition's identifier name. */
		public String getName() { return name; }
	}

	/** A config block defining named constants. */
	public static class ConfigDef extends Definition {
		/** Named constant entries in this config block, mapping name to value expression. */
		private final Map<String, Expression> entries;

		/**
		 * Constructs a config definition node.
		 *
		 * @param name    Config block name
		 * @param entries Map of constant names to their value expressions
		 * @param line    Source line number
		 * @param column  Source column number
		 */
		public ConfigDef(String name, Map<String, Expression> entries, int line, int column) {
			super(name, line, column);
			this.entries = entries;
		}

		/** Returns the named constant entries in this config block. */
		public Map<String, Expression> getEntries() { return entries; }
	}

	/**
	 * A data block declaring external weight inputs and derived zero-copy sub-views.
	 *
	 * <p>Data blocks are top-level definitions. When a layer or model is built,
	 * all data blocks in the program are pre-populated into the environment before
	 * the layer body is interpreted. Parameter declarations ({@code name: type})
	 * are satisfied from caller-supplied arguments; derivations ({@code name = expr})
	 * are evaluated in declaration order, with earlier entries visible to later ones.</p>
	 *
	 * <p>Example:
	 * <pre>
	 * data gru_weights {
	 *     weight_ih: weight
	 *     input_size: int
	 *     hidden_size: int
	 *
	 *     w_ir = range(weight_ih, [hidden_size, input_size], 0)
	 *     w_iz = range(weight_ih, [hidden_size, input_size], hidden_size * input_size)
	 * }
	 * </pre>
	 * </p>
	 */
	public static class DataDef extends Definition {
		/** External input declarations in the order they appear in source. */
		private final List<Parameter> parameters;

		/**
		 * Derived bindings in declaration order.
		 * Earlier entries are visible when evaluating later expressions.
		 */
		private final Map<String, Expression> derivations;

		/**
		 * Constructs a data block definition.
		 *
		 * @param name        data block name
		 * @param parameters  external input declarations
		 * @param derivations derived bindings, in declaration order
		 * @param line        source line number
		 * @param column      source column number
		 */
		public DataDef(String name, List<Parameter> parameters,
					   Map<String, Expression> derivations,
					   int line, int column) {
			super(name, line, column);
			this.parameters = parameters;
			this.derivations = derivations;
		}

		/** Returns the external input declarations. */
		public List<Parameter> getParameters() { return parameters; }

		/** Returns the derived bindings in declaration order. */
		public Map<String, Expression> getDerivations() { return derivations; }
	}

	/**
	 * A state block declaring persistent mutable DSP state.
	 *
	 * <p>Structurally identical to {@link DataDef} — the same parameter declarations and
	 * derivations are supported. The different class type signals write-intent: state block
	 * entries are read <em>and</em> written during execution by state-aware primitives
	 * such as {@code biquad}, {@code delay}, and {@code lfo}.</p>
	 *
	 * <p>Example:
	 * <pre>
	 * state biquad_state {
	 *     history: weight    // 4-element PackedCollection [x1, x2, y1, y2]
	 * }
	 * </pre>
	 * </p>
	 */
	public static class StateDef extends DataDef {
		/**
		 * Constructs a state block definition.
		 *
		 * @param name        state block name
		 * @param parameters  external input declarations
		 * @param derivations derived bindings, in declaration order
		 * @param line        source line number
		 * @param column      source column number
		 */
		public StateDef(String name, List<Parameter> parameters,
						Map<String, Expression> derivations,
						int line, int column) {
			super(name, parameters, derivations, line, column);
		}
	}

	/** A layer definition: reusable block builder. */
	public static class LayerDef extends Definition {
		/** Formal parameters accepted by this layer. */
		private final List<Parameter> parameters;

		/** Declared output shape expression, or {@code null} if omitted. */
		private final Expression returnShape;

		/** Statements that build the layer's computation graph. */
		private final List<Statement> body;

		/**
		 * Constructs a layer definition node.
		 *
		 * @param name        Layer name
		 * @param parameters  Formal parameter declarations
		 * @param returnShape Declared output shape, or {@code null}
		 * @param body        Statements forming the layer body
		 * @param line        Source line number
		 * @param column      Source column number
		 */
		public LayerDef(String name, List<Parameter> parameters,
						Expression returnShape, List<Statement> body,
						int line, int column) {
			super(name, line, column);
			this.parameters = parameters;
			this.returnShape = returnShape;
			this.body = body;
		}

		/** Returns the formal parameter list. */
		public List<Parameter> getParameters() { return parameters; }

		/** The declared return shape, or null if omitted. */
		public Expression getReturnShape() { return returnShape; }

		/** Returns the statements that form the layer body. */
		public List<Statement> getBody() { return body; }
	}

	/** A model definition: top-level model builder. */
	public static class ModelDef extends Definition {
		/** Formal parameters accepted by this model. */
		private final List<Parameter> parameters;

		/** Statements that build the model's computation graph. */
		private final List<Statement> body;

		/**
		 * Constructs a model definition node.
		 *
		 * @param name       Model name
		 * @param parameters Formal parameter declarations
		 * @param body       Statements forming the model body
		 * @param line       Source line number
		 * @param column     Source column number
		 */
		public ModelDef(String name, List<Parameter> parameters,
						List<Statement> body, int line, int column) {
			super(name, line, column);
			this.parameters = parameters;
			this.body = body;
		}

		/** Returns the formal parameter list. */
		public List<Parameter> getParameters() { return parameters; }

		/** Returns the statements that form the model body. */
		public List<Statement> getBody() { return body; }
	}

	// ---- Parameters ----

	/** A parameter declaration in a layer or model definition. */
	public static class Parameter extends PdslNode {
		/** Name of this parameter as declared in the PDSL source. */
		private final String name;

		/** Type keyword: {@code weight}, {@code scalar}, {@code int}, {@code float}, or a config name. */
		private final String typeName;

		/** Shape annotation for weight-typed parameters; {@code null} for scalar/int/float types. */
		private final Expression shape;

		/**
		 * @param name     parameter name
		 * @param typeName type keyword (weight, scalar, int, float, or a user-defined config name)
		 * @param shape    shape expression for weight types, or null
		 * @param line     source line number
		 * @param column   source column number
		 */
		public Parameter(String name, String typeName, Expression shape,
						 int line, int column) {
			super(line, column);
			this.name = name;
			this.typeName = typeName;
			this.shape = shape;
		}

		/** Returns the parameter name. */
		public String getName() { return name; }

		/** Returns the type keyword string. */
		public String getTypeName() { return typeName; }

		/** Shape annotation (for weight parameters), or null. */
		public Expression getShape() { return shape; }
	}

	// ---- Statements ----

	/** Base class for statements within a block body. */
	public abstract static class Statement extends PdslNode {
		/**
		 * Constructs a statement node with source location.
		 *
		 * @param line   Source line number
		 * @param column Source column number
		 */
		protected Statement(int line, int column) {
			super(line, column);
		}
	}

	/** Variable binding: {@code let name = expression}. */
	public static class LetStatement extends Statement {
		/** Name of the variable being bound. */
		private final String name;

		/** Expression whose value is bound to the variable. */
		private final Expression value;

		/**
		 * Constructs a let statement.
		 *
		 * @param name   Variable name
		 * @param value  Bound expression
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public LetStatement(String name, Expression value, int line, int column) {
			super(line, column);
			this.name = name;
			this.value = value;
		}

		/** Returns the variable name. */
		public String getName() { return name; }

		/** Returns the bound expression. */
		public Expression getValue() { return value; }
	}

	/** Return statement: {@code return expression}. */
	public static class ReturnStatement extends Statement {
		/** Expression whose value is returned from the block. */
		private final Expression value;

		/**
		 * Constructs a return statement.
		 *
		 * @param value  The expression to return
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public ReturnStatement(Expression value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		/** Returns the expression being returned. */
		public Expression getValue() { return value; }
	}

	/** Branch statement: {@code branch name { body }}. */
	public static class BranchStatement extends Statement {
		/** Name of this branch, used to reference it from product/attention expressions. */
		private final String name;

		/** Statements forming the body of this branch. */
		private final List<Statement> body;

		/**
		 * Constructs a branch statement.
		 *
		 * @param name   Branch name
		 * @param body   Statements forming the branch body
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public BranchStatement(String name, List<Statement> body,
							   int line, int column) {
			super(line, column);
			this.name = name;
			this.body = body;
		}

		/** The branch name (used to reference from product/attention). */
		public String getName() { return name; }

		/** Returns the statements forming the branch body. */
		public List<Statement> getBody() { return body; }
	}

	/** Accumulation (residual connection): {@code accum { body }}. */
	public static class AccumStatement extends Statement {
		/** Statements forming the body of this accumulation block. */
		private final List<Statement> body;

		/**
		 * Constructs an accumulation statement.
		 *
		 * @param body   Statements forming the accum body
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public AccumStatement(List<Statement> body, int line, int column) {
			super(line, column);
			this.body = body;
		}

		/** Returns the statements forming the accum body. */
		public List<Statement> getBody() { return body; }
	}

	/**
	 * Product statement: element-wise multiply of two sub-blocks.
	 * {@code product(blockA, blockB)}
	 */
	public static class ProductStatement extends Statement {
		/** The left-hand sub-block expression. */
		private final Expression left;

		/** The right-hand sub-block expression. */
		private final Expression right;

		/**
		 * Constructs a product statement.
		 *
		 * @param left   Left-hand sub-block
		 * @param right  Right-hand sub-block
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public ProductStatement(Expression left, Expression right,
								int line, int column) {
			super(line, column);
			this.left = left;
			this.right = right;
		}

		/** Returns the left-hand sub-block expression. */
		public Expression getLeft() { return left; }

		/** Returns the right-hand sub-block expression. */
		public Expression getRight() { return right; }
	}

	/**
	 * ConcatBlocks statement: concatenation of N sub-block outputs.
	 * All sub-blocks receive the same input; their outputs are concatenated in order.
	 * {@code concat_blocks(blockA, blockB, ...)}
	 */
	public static class ConcatBlocksStatement extends Statement {
		/** The list of sub-block expressions to apply in parallel and concatenate. */
		private final List<Expression> blocks;

		/**
		 * Constructs a ConcatBlocksStatement.
		 *
		 * @param blocks the list of sub-block expressions
		 * @param line   the source line number
		 * @param column the source column number
		 */
		public ConcatBlocksStatement(List<Expression> blocks, int line, int column) {
			super(line, column);
			this.blocks = blocks;
		}

		/** Returns the list of sub-block expressions to be concatenated. */
		public List<Expression> getBlocks() { return blocks; }
	}

	/**
	 * AccumBlocks statement: element-wise accumulation (sum) of two sub-block outputs.
	 * Both sub-blocks receive the same input; their outputs are summed.
	 * {@code accum_blocks(blockA, blockB)}
	 */
	public static class AccumBlocksStatement extends Statement {
		/** The left sub-block expression. */
		private final Expression left;
		/** The right sub-block expression. */
		private final Expression right;

		/**
		 * Constructs an AccumBlocksStatement.
		 *
		 * @param left   the left sub-block expression
		 * @param right  the right sub-block expression
		 * @param line   the source line number
		 * @param column the source column number
		 */
		public AccumBlocksStatement(Expression left, Expression right,
								  int line, int column) {
			super(line, column);
			this.left = left;
			this.right = right;
		}

		/** Returns the left sub-block expression. */
		public Expression getLeft() { return left; }

		/** Returns the right sub-block expression. */
		public Expression getRight() { return right; }
	}

	/**
	 * Heterogeneous fan-out statement: {@code fan_out_with({ body1 }, { body2 }, ...)}.
	 *
	 * <p>Takes the upstream {@code [1, signal_size]} signal and applies a distinct
	 * sub-block (specified inline as a brace-delimited body) to each branch, producing
	 * a {@code [N, signal_size]} output where {@code N} is the number of sub-blocks.
	 * The PDSL rendition of {@code CellList.branch(IntFunction<Cell>...)} from
	 * {@code MixdownManager.createCells()} lines 572-602.</p>
	 *
	 * <p>The number of branches is fixed at parse time. After the statement, the
	 * environment's {@code channels} binding is updated to {@code N} so that
	 * subsequent {@code for each channel}, {@code sum_channels()}, {@code route()},
	 * etc., operate on the new channel count.</p>
	 */
	public static class FanOutWithStatement extends Statement {
		/** One sub-block expression per branch (typically each is an
		 * {@link InlineBlock}). */
		private final List<Expression> branches;

		/**
		 * Constructs a heterogeneous fan-out statement.
		 *
		 * @param branches per-branch sub-block expressions (in branch order)
		 * @param line     source line number
		 * @param column   source column number
		 */
		public FanOutWithStatement(List<Expression> branches, int line, int column) {
			super(line, column);
			this.branches = branches;
		}

		/** Returns the per-branch sub-block expressions. */
		public List<Expression> getBranches() { return branches; }
	}

	/** For-loop: {@code for i in start..end { body }}. */
	public static class ForStatement extends Statement {
		/** Loop variable name. */
		private final String variable;

		/** Inclusive loop start expression. */
		private final Expression start;

		/** Exclusive loop end expression. */
		private final Expression end;

		/** Statements forming the loop body. */
		private final List<Statement> body;

		/**
		 * Constructs a for statement.
		 *
		 * @param variable Loop variable name
		 * @param start    Inclusive start expression
		 * @param end      Exclusive end expression
		 * @param body     Loop body statements
		 * @param line     Source line number
		 * @param column   Source column number
		 */
		public ForStatement(String variable, Expression start, Expression end,
							List<Statement> body, int line, int column) {
			super(line, column);
			this.variable = variable;
			this.start = start;
			this.end = end;
			this.body = body;
		}

		/** Returns the loop variable name. */
		public String getVariable() { return variable; }

		/** Returns the inclusive start expression. */
		public Expression getStart() { return start; }

		/** Returns the exclusive end expression. */
		public Expression getEnd() { return end; }

		/** Returns the loop body statements. */
		public List<Statement> getBody() { return body; }
	}

	/**
	 * For-each-channel statement: {@code for each channel { body }}.
	 *
	 * <p>Iterates over all channels declared on the enclosing layer (via the {@code channels: int}
	 * parameter). The body is interpreted once per channel with the variable {@code channel}
	 * bound to the current channel index (0-based). The resulting per-channel blocks are
	 * dispatched in parallel: each receives a single-channel slice of the multi-channel input
	 * and the outputs are concatenated.</p>
	 */
	public static class ForEachChannelStatement extends Statement {
		/** Statements forming the per-channel body. */
		private final List<Statement> body;

		/**
		 * Constructs a for-each-channel statement.
		 *
		 * @param body   Statements forming the per-channel body
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public ForEachChannelStatement(List<Statement> body, int line, int column) {
			super(line, column);
			this.body = body;
		}

		/** Returns the statements forming the per-channel body. */
		public List<Statement> getBody() { return body; }
	}

	/**
	 * Subscript expression: {@code expr[index]}.
	 *
	 * <p>Indexes into a {@link org.almostrealism.collect.PackedCollection} by channel,
	 * returning a zero-copy sub-view. The stride is inferred as {@code total / channels}
	 * where {@code channels} is the current loop variable from {@code for each channel}.</p>
	 */
	public static class Subscript extends Expression {
		/** The collection expression being indexed. */
		private final Expression object;

		/** The index expression. */
		private final Expression index;

		/**
		 * Constructs a subscript expression.
		 *
		 * @param object The collection expression being indexed
		 * @param index  The index expression
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public Subscript(Expression object, Expression index, int line, int column) {
			super(line, column);
			this.object = object;
			this.index = index;
		}

		/** Returns the subscripted collection expression. */
		public Expression getObject() { return object; }

		/** Returns the index expression. */
		public Expression getIndex() { return index; }
	}

	/** An expression used as a statement (typically a function call that adds a block). */
	public static class ExpressionStatement extends Statement {
		/** The expression being used as a statement. */
		private final Expression expression;

		/**
		 * Constructs an expression statement.
		 *
		 * @param expression The expression to evaluate as a statement
		 * @param line       Source line number
		 * @param column     Source column number
		 */
		public ExpressionStatement(Expression expression, int line, int column) {
			super(line, column);
			this.expression = expression;
		}

		/** Returns the wrapped expression. */
		public Expression getExpression() { return expression; }
	}

	// ---- Expressions ----

	/** Base class for all expressions. */
	public abstract static class Expression extends PdslNode {
		/**
		 * Constructs an expression node with source location.
		 *
		 * @param line   Source line number
		 * @param column Source column number
		 */
		protected Expression(int line, int column) {
			super(line, column);
		}
	}

	/** Numeric literal. */
	public static class NumberLiteral extends Expression {
		/** The numeric value of this literal. */
		private final double value;

		/**
		 * Constructs a numeric literal node.
		 *
		 * @param value  The literal value
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public NumberLiteral(double value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		/** Returns the literal numeric value. */
		public double getValue() { return value; }
	}

	/** String literal. */
	public static class StringLiteral extends Expression {
		/** The string value without surrounding quotes. */
		private final String value;

		/**
		 * Constructs a string literal node.
		 *
		 * @param value  The literal string value (without quotes)
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public StringLiteral(String value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		/** Returns the string value. */
		public String getValue() { return value; }
	}

	/** Boolean literal. */
	public static class BoolLiteral extends Expression {
		/** The boolean value of this literal. */
		private final boolean value;

		/**
		 * Constructs a boolean literal node.
		 *
		 * @param value  The boolean value
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public BoolLiteral(boolean value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		/** Returns the boolean value. */
		public boolean getValue() { return value; }
	}

	/** Null literal. */
	public static class NullLiteral extends Expression {
		/**
		 * Constructs a null literal node.
		 *
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public NullLiteral(int line, int column) {
			super(line, column);
		}
	}

	/** Identifier reference. */
	public static class Identifier extends Expression {
		/** The identifier name as it appears in the source. */
		private final String name;

		/**
		 * Constructs an identifier reference node.
		 *
		 * @param name   The identifier name
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public Identifier(String name, int line, int column) {
			super(line, column);
			this.name = name;
		}

		/** Returns the identifier name. */
		public String getName() { return name; }
	}

	/** Shape literal: {@code [dim1, dim2, ...]}. */
	public static class ShapeLiteral extends Expression {
		/** Expressions for each dimension of the shape. */
		private final List<Expression> dimensions;

		/**
		 * Constructs a shape literal node.
		 *
		 * @param dimensions Expressions for each dimension
		 * @param line       Source line number
		 * @param column     Source column number
		 */
		public ShapeLiteral(List<Expression> dimensions, int line, int column) {
			super(line, column);
			this.dimensions = dimensions;
		}

		/** Returns the per-dimension expressions. */
		public List<Expression> getDimensions() { return dimensions; }
	}

	/** Function call: {@code name(arg1, arg2, ...)}. */
	public static class FunctionCall extends Expression {
		/** The name of the function being called. */
		private final String name;

		/** Argument expressions passed to the function. */
		private final List<Expression> arguments;

		/**
		 * Constructs a function call node.
		 *
		 * @param name      Function name
		 * @param arguments Argument expressions
		 * @param line      Source line number
		 * @param column    Source column number
		 */
		public FunctionCall(String name, List<Expression> arguments,
							int line, int column) {
			super(line, column);
			this.name = name;
			this.arguments = arguments;
		}

		/** Returns the function name. */
		public String getName() { return name; }

		/** Returns the argument expressions. */
		public List<Expression> getArguments() { return arguments; }
	}

	/** Binary operation: {@code left op right}. */
	public static class BinaryOp extends Expression {
		/** The left-hand operand. */
		private final Expression left;

		/** The operator symbol (e.g., {@code +}, {@code -}, {@code ==}). */
		private final String operator;

		/** The right-hand operand. */
		private final Expression right;

		/**
		 * Constructs a binary operation node.
		 *
		 * @param left     Left-hand operand
		 * @param operator Operator symbol
		 * @param right    Right-hand operand
		 * @param line     Source line number
		 * @param column   Source column number
		 */
		public BinaryOp(Expression left, String operator, Expression right,
						int line, int column) {
			super(line, column);
			this.left = left;
			this.operator = operator;
			this.right = right;
		}

		/** Returns the left-hand operand. */
		public Expression getLeft() { return left; }

		/** Returns the operator symbol. */
		public String getOperator() { return operator; }

		/** Returns the right-hand operand. */
		public Expression getRight() { return right; }
	}

	/** Unary operation: {@code -expr}. */
	public static class UnaryOp extends Expression {
		/** The operator symbol (e.g., {@code -}). */
		private final String operator;

		/** The operand expression. */
		private final Expression operand;

		/**
		 * Constructs a unary operation node.
		 *
		 * @param operator Operator symbol
		 * @param operand  Operand expression
		 * @param line     Source line number
		 * @param column   Source column number
		 */
		public UnaryOp(String operator, Expression operand, int line, int column) {
			super(line, column);
			this.operator = operator;
			this.operand = operand;
		}

		/** Returns the operator symbol. */
		public String getOperator() { return operator; }

		/** Returns the operand expression. */
		public Expression getOperand() { return operand; }
	}

	/** Field access: {@code expr.field}. */
	public static class FieldAccess extends Expression {
		/** The object expression whose field is being accessed. */
		private final Expression object;

		/** The field name being accessed. */
		private final String field;

		/**
		 * Constructs a field access node.
		 *
		 * @param object The receiver expression
		 * @param field  The field name
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public FieldAccess(Expression object, String field, int line, int column) {
			super(line, column);
			this.object = object;
			this.field = field;
		}

		/** Returns the receiver expression. */
		public Expression getObject() { return object; }

		/** Returns the field name. */
		public String getField() { return field; }
	}

	/**
	 * Inline block expression used inside product:
	 * {@code block { stmt; stmt; }}
	 */
	public static class InlineBlock extends Expression {
		/** Statements forming the inline block body. */
		private final List<Statement> body;

		/**
		 * Constructs an inline block expression.
		 *
		 * @param body   Statements in the block
		 * @param line   Source line number
		 * @param column Source column number
		 */
		public InlineBlock(List<Statement> body, int line, int column) {
			super(line, column);
			this.body = body;
		}

		/** Returns the statements forming the block body. */
		public List<Statement> getBody() { return body; }
	}

	/**
	 * Weight reference expression: {@code weight("key.path")}.
	 * Used in model definitions to reference StateDictionary keys.
	 */
	public static class WeightRef extends Expression {
		/** Expression that evaluates to the StateDictionary key string. */
		private final Expression keyExpression;

		/**
		 * Constructs a weight reference node.
		 *
		 * @param keyExpression Expression evaluating to the weight key
		 * @param line          Source line number
		 * @param column        Source column number
		 */
		public WeightRef(Expression keyExpression, int line, int column) {
			super(line, column);
			this.keyExpression = keyExpression;
		}

		/** Returns the expression that evaluates to the weight key. */
		public Expression getKeyExpression() { return keyExpression; }
	}
}
