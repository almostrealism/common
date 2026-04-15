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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Interprets a parsed PDSL program to construct {@link Block} and {@link Model}
 * objects using the AlmostRealism producer framework. The interpreter maps
 * DSL function calls to concrete {@link AttentionFeatures} and
 * {@link RotationFeatures} methods, building computation graphs that can
 * be compiled and executed on GPU/CPU hardware.
 *
 * <p>Built-in primitive operations:
 * <ul>
 *   <li>{@code dense(weights)} / {@code dense(weights, biases)}</li>
 *   <li>{@code rmsnorm(weights, epsilon)}</li>
 *   <li>{@code softmax()}, {@code silu()}, {@code relu()}, {@code gelu()},
 *       {@code sigmoid()}, {@code tanh_act()}</li>
 *   <li>{@code slice(offset, size)} - extract a 1-D sub-range</li>
 *   <li>{@code lerp(hidden_size)} - linear interpolation from [from|weight|to] input</li>
 *   <li>{@code reshape(shape)}</li>
 *   <li>{@code rope_rotation(shape, freq_cis, position)}</li>
 *   <li>{@code attention(...)}, {@code transformer(...)},
 *       {@code feed_forward(...)}</li>
 *   <li>{@code embedding(table)}</li>
 *   <li>{@code fir(coefficients)} - FIR filter (convolution with a coefficient array)</li>
 *   <li>{@code scale(factor)} - multiply each element by a scalar factor</li>
 *   <li>{@code lowpass(cutoff, sampleRate, filterOrder)} - low-pass FIR filter</li>
 *   <li>{@code highpass(cutoff, sampleRate, filterOrder)} - high-pass FIR filter</li>
 *   <li>{@code biquad(b0,b1,b2,a1,a2,history)} - stateful biquad IIR filter;
 *       {@code history} is a 4-element {@link PackedCollection}
 *       {@code [x[n-1], x[n-2], y[n-1], y[n-2]]} mutated in-place each call</li>
 *   <li>{@code delay(delaySamples,buffer,head)} - stateful integer-sample delay line;
 *       {@code buffer} is a circular {@link PackedCollection}, {@code head} is a
 *       1-element write-position counter mutated in-place each call</li>
 *   <li>{@code lfo(freqHz,sampleRate,phase)} - stateful sinusoidal LFO;
 *       {@code phase} is a 1-element {@link PackedCollection} mutated in-place
 *       so phase is continuous across successive calls</li>
 * </ul>
 *
 * <p>Composition constructs:
 * <ul>
 *   <li>{@code branch name { ... }} - parallel path</li>
 *   <li>{@code accum { ... }} - residual connection</li>
 *   <li>{@code product(blockA, blockB)} - element-wise multiply</li>
 *   <li>{@code accum_blocks(blockA, blockB)} - element-wise addition</li>
 * </ul>
 */
public class PdslInterpreter {

	/**
	 * Anonymous implementation providing access to all default methods
	 * in {@link AttentionFeatures} and {@link RotationFeatures}.
	 */
	private static final Features FEATURES = new Features();

	/** Layer definitions keyed by name, built from the parsed program. */
	private final Map<String, PdslNode.LayerDef> layerDefs;

	/** Config definitions keyed by name, built from the parsed program. */
	private final Map<String, PdslNode.ConfigDef> configDefs;

	/** Model definitions keyed by name, built from the parsed program. */
	private final Map<String, PdslNode.ModelDef> modelDefs;

	/** Data block definitions keyed by name, built from the parsed program. */
	private final Map<String, PdslNode.DataDef> dataDefs;

	/** State block definitions keyed by name, built from the parsed program. */
	private final Map<String, PdslNode.StateDef> stateDefs;

	/**
	 * Create an interpreter for the given parsed program.
	 *
	 * @param program the parsed AST
	 */
	public PdslInterpreter(PdslNode.Program program) {
		this.layerDefs = new HashMap<>();
		this.configDefs = new HashMap<>();
		this.modelDefs = new HashMap<>();
		this.dataDefs = new LinkedHashMap<>();
		this.stateDefs = new LinkedHashMap<>();
		for (PdslNode.Definition def : program.getDefinitions()) {
			if (def instanceof PdslNode.LayerDef) {
				layerDefs.put(def.getName(), (PdslNode.LayerDef) def);
			} else if (def instanceof PdslNode.ConfigDef) {
				configDefs.put(def.getName(), (PdslNode.ConfigDef) def);
			} else if (def instanceof PdslNode.ModelDef) {
				modelDefs.put(def.getName(), (PdslNode.ModelDef) def);
			} else if (def instanceof PdslNode.StateDef) {
				// Check StateDef before DataDef since StateDef extends DataDef
				stateDefs.put(def.getName(), (PdslNode.StateDef) def);
			} else if (def instanceof PdslNode.DataDef) {
				dataDefs.put(def.getName(), (PdslNode.DataDef) def);
			}
		}
	}

	/** Returns the names of all layer definitions. */
	public Set<String> getLayerNames() { return layerDefs.keySet(); }

	/** Returns the names of all model definitions. */
	public Set<String> getModelNames() { return modelDefs.keySet(); }

	/** Returns the names of all config definitions. */
	public Set<String> getConfigNames() { return configDefs.keySet(); }

	/** Returns the names of all data block definitions. */
	public Set<String> getDataDefNames() { return dataDefs.keySet(); }

	/** Returns the names of all state block definitions. */
	public Set<String> getStateDefNames() { return stateDefs.keySet(); }

	/**
	 * Build a {@link Block} from a named layer definition.
	 *
	 * @param name       the layer name as defined in the PDSL source
	 * @param inputShape the input tensor shape for the block
	 * @param args       parameter bindings (name to value)
	 * @return the constructed Block
	 */
	public Block buildLayer(String name, TraversalPolicy inputShape,
							Map<String, Object> args) {
		PdslNode.LayerDef def = layerDefs.get(name);
		if (def == null) {
			throw new PdslParseException("Layer '" + name + "' not found");
		}
		Environment env = new Environment(null);
		populateDataDefs(args, env);
		for (PdslNode.Parameter param : def.getParameters()) {
			Object value = args.get(param.getName());
			if (value == null && !args.containsKey(param.getName())) {
				throw new PdslParseException(
						"Missing argument '" + param.getName() + "' for layer '" + name + "'");
			}
			env.set(param.getName(), value);
		}
		SequentialBlock block = new SequentialBlock(inputShape);
		interpretBody(def.getBody(), block, env);
		return block;
	}

	/**
	 * Build a {@link Model} from a named model definition.
	 *
	 * @param name       the model name as defined in the PDSL source
	 * @param inputShape the input tensor shape
	 * @param args       parameter bindings
	 * @return the constructed Model
	 */
	public Model buildModel(String name, TraversalPolicy inputShape,
							Map<String, Object> args) {
		PdslNode.ModelDef def = modelDefs.get(name);
		if (def == null) {
			throw new PdslParseException("Model '" + name + "' not found");
		}
		Environment env = new Environment(null);
		populateDataDefs(args, env);
		for (PdslNode.Parameter param : def.getParameters()) {
			env.set(param.getName(), args.get(param.getName()));
		}
		Model model = new Model(inputShape);
		interpretModelBody(def.getBody(), model, env);
		return model;
	}

	/**
	 * Evaluate a config definition and return its entries as a map.
	 *
	 * @param name the config name
	 * @return config entries as name-value pairs
	 */
	public Map<String, Object> evaluateConfig(String name) {
		PdslNode.ConfigDef def = configDefs.get(name);
		if (def == null) {
			throw new PdslParseException("Config '" + name + "' not found");
		}
		Environment env = new Environment(null);
		Map<String, Object> result = new HashMap<>();
		for (Map.Entry<String, PdslNode.Expression> entry : def.getEntries().entrySet()) {
			Object value = evaluateExpression(entry.getValue(), env);
			result.put(entry.getKey(), value);
			env.set(entry.getKey(), value);
		}
		return result;
	}

	/**
	 * Evaluate a named data block, binding external inputs from {@code args}
	 * and computing all derived views in declaration order.
	 *
	 * @param name the data block name
	 * @param args external input values (name → value)
	 * @return all data block entries (parameters + derivations) as a map
	 */
	public Map<String, Object> evaluateDataDef(String name, Map<String, Object> args) {
		PdslNode.DataDef def = dataDefs.get(name);
		if (def == null) {
			throw new PdslParseException("Data block '" + name + "' not found");
		}
		return evaluateDefEntries(def, args);
	}

	/**
	 * Evaluate a named state block, binding external inputs from {@code args}
	 * and computing all derived views in declaration order.
	 *
	 * <p>The logic is identical to {@link #evaluateDataDef} — bind parameter
	 * references and evaluate range() derivations — applied to a state block
	 * rather than a data block.</p>
	 *
	 * @param name the state block name
	 * @param args external input values (name → value)
	 * @return all state block entries (parameters + derivations) as a map
	 */
	public Map<String, Object> evaluateStateDef(String name, Map<String, Object> args) {
		PdslNode.StateDef def = stateDefs.get(name);
		if (def == null) {
			throw new PdslParseException("State block '" + name + "' not found");
		}
		return evaluateDefEntries(def, args);
	}

	/**
	 * Evaluates the entries of a data or state block definition by binding external inputs
	 * from {@code args} and computing derived views in declaration order.
	 *
	 * <p>This is the shared implementation called by both {@link #evaluateDataDef} and
	 * {@link #evaluateStateDef} to eliminate duplicate logic between the two paths.</p>
	 *
	 * @param def  the data or state block definition
	 * @param args external input values (name → value)
	 * @return all block entries (parameters + derivations) as a linked map preserving order
	 */
	private Map<String, Object> evaluateDefEntries(PdslNode.DataDef def,
													Map<String, Object> args) {
		Environment env = new Environment(null);
		Map<String, Object> result = new LinkedHashMap<>();
		for (PdslNode.Parameter param : def.getParameters()) {
			if (!args.containsKey(param.getName())) {
				throw new PdslParseException(
						"Missing argument '" + param.getName()
								+ "' for block '" + def.getName() + "'");
			}
			Object value = args.get(param.getName());
			env.set(param.getName(), value);
			result.put(param.getName(), value);
		}
		for (Map.Entry<String, PdslNode.Expression> entry : def.getDerivations().entrySet()) {
			Object value = evaluateExpression(entry.getValue(), env);
			env.set(entry.getKey(), value);
			result.put(entry.getKey(), value);
		}
		return result;
	}

	/**
	 * Pre-populates an environment with all entries from every data block in this
	 * program. External inputs are resolved from {@code args}; derived views are
	 * computed in declaration order so that earlier entries are visible to later ones.
	 *
	 * <p>Called at the start of {@link #buildLayer} and {@link #buildModel} so that
	 * layer bodies can reference data block entries without explicitly declaring them
	 * as layer parameters.</p>
	 *
	 * @param args external input values
	 * @param env  target environment (mutated in-place)
	 */
	private void populateDataDefs(Map<String, Object> args, Environment env) {
		for (PdslNode.DataDef def : dataDefs.values()) {
			for (PdslNode.Parameter param : def.getParameters()) {
				if (!args.containsKey(param.getName())) {
					throw new PdslParseException(
							"Missing argument '" + param.getName()
									+ "' required by data block '" + def.getName() + "'");
				}
				env.set(param.getName(), args.get(param.getName()));
			}
			for (Map.Entry<String, PdslNode.Expression> entry : def.getDerivations().entrySet()) {
				Object value = evaluateExpression(entry.getValue(), env);
				env.set(entry.getKey(), value);
			}
		}
		for (PdslNode.StateDef def : stateDefs.values()) {
			for (PdslNode.Parameter param : def.getParameters()) {
				if (!args.containsKey(param.getName())) {
					throw new PdslParseException(
							"Missing argument '" + param.getName()
									+ "' required by state block '" + def.getName() + "'");
				}
				env.set(param.getName(), args.get(param.getName()));
			}
			for (Map.Entry<String, PdslNode.Expression> entry : def.getDerivations().entrySet()) {
				Object value = evaluateExpression(entry.getValue(), env);
				env.set(entry.getKey(), value);
			}
		}
	}

	// ---- Body interpretation ----

	/**
	 * Interprets each statement in a layer body, appending operations to the given block.
	 *
	 * @param stmts List of statements to execute
	 * @param block Target sequential block
	 * @param env   Current variable environment
	 */
	private void interpretBody(List<PdslNode.Statement> stmts,
							   SequentialBlock block, Environment env) {
		for (PdslNode.Statement stmt : stmts) {
			interpretStatement(stmt, block, env);
		}
	}

	/**
	 * Interprets each statement in a model body, adding blocks or inputs to the model.
	 *
	 * @param stmts List of statements to execute
	 * @param model Target model under construction
	 * @param env   Current variable environment
	 */
	private void interpretModelBody(List<PdslNode.Statement> stmts,
									Model model, Environment env) {
		for (PdslNode.Statement stmt : stmts) {
			if (stmt instanceof PdslNode.ExpressionStatement) {
				Object result = evaluateExpression(
						((PdslNode.ExpressionStatement) stmt).getExpression(), env);
				addToModel(model, result);
			} else if (stmt instanceof PdslNode.LetStatement) {
				PdslNode.LetStatement let = (PdslNode.LetStatement) stmt;
				Object value = evaluateExpression(let.getValue(), env);
				env.set(let.getName(), value);
			} else if (stmt instanceof PdslNode.ForStatement) {
				PdslNode.ForStatement forStmt = (PdslNode.ForStatement) stmt;
				int start = toInt(evaluateExpression(forStmt.getStart(), env));
				int end = toInt(evaluateExpression(forStmt.getEnd(), env));
				for (int i = start; i < end; i++) {
					Environment loopEnv = new Environment(env);
					loopEnv.set(forStmt.getVariable(), i);
					interpretModelBody(forStmt.getBody(), model, loopEnv);
				}
			} else {
				throw new PdslParseException(
						"Unsupported statement in model body: " + stmt.getClass().getSimpleName());
			}
		}
	}

	/**
	 * Dispatches a single statement to its specific handler.
	 *
	 * @param stmt  Statement to interpret
	 * @param block Target sequential block
	 * @param env   Current variable environment
	 */
	private void interpretStatement(PdslNode.Statement stmt,
									SequentialBlock block, Environment env) {
		if (stmt instanceof PdslNode.ExpressionStatement) {
			Object result = evaluateExpression(
					((PdslNode.ExpressionStatement) stmt).getExpression(), env);
			addToBlock(block, result);
		} else if (stmt instanceof PdslNode.LetStatement) {
			interpretLet((PdslNode.LetStatement) stmt, block, env);
		} else if (stmt instanceof PdslNode.BranchStatement) {
			interpretBranch((PdslNode.BranchStatement) stmt, block, env);
		} else if (stmt instanceof PdslNode.AccumStatement) {
			interpretAccum((PdslNode.AccumStatement) stmt, block, env);
		} else if (stmt instanceof PdslNode.ProductStatement) {
			interpretProduct((PdslNode.ProductStatement) stmt, block, env);
		} else if (stmt instanceof PdslNode.AccumBlocksStatement) {
			interpretAccumBlocks((PdslNode.AccumBlocksStatement) stmt, block, env);
		} else if (stmt instanceof PdslNode.ConcatBlocksStatement) {
			interpretConcatBlocks((PdslNode.ConcatBlocksStatement) stmt, block, env);
		} else if (stmt instanceof PdslNode.ForStatement) {
			PdslNode.ForStatement forStmt = (PdslNode.ForStatement) stmt;
			int start = toInt(evaluateExpression(forStmt.getStart(), env));
			int end = toInt(evaluateExpression(forStmt.getEnd(), env));
			for (int i = start; i < end; i++) {
				Environment loopEnv = new Environment(env);
				loopEnv.set(forStmt.getVariable(), i);
				interpretBody(forStmt.getBody(), block, loopEnv);
			}
		} else if (stmt instanceof PdslNode.ReturnStatement) {
			// Return is handled by evaluating and adding the final expression
			Object result = evaluateExpression(
					((PdslNode.ReturnStatement) stmt).getValue(), env);
			addToBlock(block, result);
		} else {
			throw new PdslParseException(
					"Unsupported statement: " + stmt.getClass().getSimpleName());
		}
	}

	/**
	 * Interprets a {@code let} variable binding, creating an inline branch if the value
	 * is an {@link PdslNode.InlineBlock}, or evaluating the expression otherwise.
	 *
	 * @param let   The let statement
	 * @param block Target sequential block (used when value is an inline block)
	 * @param env   Current variable environment (updated with the new binding)
	 */
	private void interpretLet(PdslNode.LetStatement let,
							  SequentialBlock block, Environment env) {
		if (let.getValue() instanceof PdslNode.InlineBlock) {
			PdslNode.InlineBlock inlineBlock = (PdslNode.InlineBlock) let.getValue();
			SequentialBlock branch = block.branch();
			interpretBody(inlineBlock.getBody(), branch, new Environment(env));
			env.set(let.getName(), branch);
		} else {
			Object value = evaluateExpression(let.getValue(), env);
			env.set(let.getName(), value);
		}
	}

	/**
	 * Interprets a {@code branch} statement, creating a sub-block and registering it in the
	 * environment under the branch name.
	 *
	 * @param branchStmt The branch statement
	 * @param block      Target sequential block that owns the branch
	 * @param env        Current variable environment (updated with the branch block)
	 */
	private void interpretBranch(PdslNode.BranchStatement branchStmt,
								 SequentialBlock block, Environment env) {
		SequentialBlock branch = block.branch();
		interpretBody(branchStmt.getBody(), branch, new Environment(env));
		if (branchStmt.getName() != null) {
			env.set(branchStmt.getName(), branch);
		}
	}

	/**
	 * Interprets an {@code accum} (residual connection) statement, adding the sub-block's
	 * output to the main block's current output.
	 *
	 * @param accumStmt The accumulation statement
	 * @param block     Target sequential block
	 * @param env       Current variable environment
	 */
	private void interpretAccum(PdslNode.AccumStatement accumStmt,
								SequentialBlock block, Environment env) {
		// Optimize: single expression → use directly as Block
		if (accumStmt.getBody().size() == 1
				&& accumStmt.getBody().get(0) instanceof PdslNode.ExpressionStatement) {
			PdslNode.Expression expr =
					((PdslNode.ExpressionStatement) accumStmt.getBody().get(0)).getExpression();
			Object result = evaluateExpression(expr, env);
			Block accumBlock = objectToBlock(result, block.getOutputShape());
			block.accum(accumBlock);
		} else {
			SequentialBlock subBlock = new SequentialBlock(block.getOutputShape());
			interpretBody(accumStmt.getBody(), subBlock, new Environment(env));
			block.accum(subBlock);
		}
	}

	/**
	 * Interprets a {@code product} statement, computing element-wise multiplication of two
	 * sub-blocks and appending the result to the target block.
	 *
	 * @param prodStmt The product statement
	 * @param block    Target sequential block
	 * @param env      Current variable environment
	 */
	private void interpretProduct(PdslNode.ProductStatement prodStmt,
								  SequentialBlock block, Environment env) {
		TraversalPolicy shape = block.getOutputShape();
		Block left = expressionToBlock(prodStmt.getLeft(), shape, env);
		Block right = expressionToBlock(prodStmt.getRight(), shape, env);
		block.product(left, right);
	}

	/**
	 * Interprets an {@code accum_blocks} statement by applying two sub-blocks to the same input
	 * and accumulating their outputs element-wise.
	 */
	private void interpretAccumBlocks(PdslNode.AccumBlocksStatement accumStmt,
									  SequentialBlock block, Environment env) {
		TraversalPolicy shape = block.getOutputShape();
		Block left = expressionToBlock(accumStmt.getLeft(), shape, env);
		Block right = expressionToBlock(accumStmt.getRight(), shape, env);
		block.accum(left, right);
	}

	/**
	 * Interprets a {@code concat_blocks} statement by applying N sub-blocks to the same input
	 * and concatenating their outputs.
	 */
	private void interpretConcatBlocks(PdslNode.ConcatBlocksStatement concatStmt,
										SequentialBlock block, Environment env) {
		TraversalPolicy inputShape = block.getOutputShape();
		List<Block> subBlocks = new ArrayList<>();
		for (PdslNode.Expression expr : concatStmt.getBlocks()) {
			subBlocks.add(expressionToBlock(expr, inputShape, env));
		}
		block.add(FEATURES.concatBlocks(inputShape, subBlocks));
	}

	// ---- Expression evaluation ----

	/**
	 * Evaluates an AST expression node to a Java value.
	 *
	 * @param expr The expression node to evaluate
	 * @param env  Current variable environment
	 * @return The evaluated value (may be a Number, String, Boolean, Block, PackedCollection, etc.)
	 */
	private Object evaluateExpression(PdslNode.Expression expr, Environment env) {
		if (expr instanceof PdslNode.NumberLiteral) {
			return ((PdslNode.NumberLiteral) expr).getValue();
		} else if (expr instanceof PdslNode.StringLiteral) {
			return ((PdslNode.StringLiteral) expr).getValue();
		} else if (expr instanceof PdslNode.BoolLiteral) {
			return ((PdslNode.BoolLiteral) expr).getValue();
		} else if (expr instanceof PdslNode.NullLiteral) {
			return null;
		} else if (expr instanceof PdslNode.Identifier) {
			return resolveIdentifier(((PdslNode.Identifier) expr).getName(), env);
		} else if (expr instanceof PdslNode.ShapeLiteral) {
			return evaluateShape((PdslNode.ShapeLiteral) expr, env);
		} else if (expr instanceof PdslNode.FunctionCall) {
			return evaluateFunctionCall((PdslNode.FunctionCall) expr, env);
		} else if (expr instanceof PdslNode.BinaryOp) {
			return evaluateBinaryOp((PdslNode.BinaryOp) expr, env);
		} else if (expr instanceof PdslNode.UnaryOp) {
			return evaluateUnaryOp((PdslNode.UnaryOp) expr, env);
		} else if (expr instanceof PdslNode.FieldAccess) {
			return evaluateFieldAccess((PdslNode.FieldAccess) expr, env);
		} else if (expr instanceof PdslNode.WeightRef) {
			return evaluateWeightRef((PdslNode.WeightRef) expr, env);
		} else if (expr instanceof PdslNode.InlineBlock) {
			return expr; // returned as-is for product args
		} else {
			throw new PdslParseException(
					"Unsupported expression: " + expr.getClass().getSimpleName()
							+ " at line " + expr.getLine());
		}
	}

	/**
	 * Resolves an identifier name to a value using the current environment or config definitions.
	 *
	 * @param name Identifier to resolve
	 * @param env  Current variable environment
	 * @return The resolved value
	 * @throws PdslParseException If the identifier is not defined
	 */
	private Object resolveIdentifier(String name, Environment env) {
		Object value = env.get(name);
		if (value != null) return value;

		// Check if it's a config name
		if (configDefs.containsKey(name)) {
			return evaluateConfig(name);
		}

		throw new PdslParseException("Undefined identifier: '" + name + "'");
	}

	/**
	 * Evaluates a shape literal to a {@link TraversalPolicy} by resolving each dimension.
	 *
	 * @param shape The shape literal node
	 * @param env   Current variable environment for dimension expressions
	 * @return The evaluated traversal policy
	 */
	private TraversalPolicy evaluateShape(PdslNode.ShapeLiteral shape,
										  Environment env) {
		int[] dims = new int[shape.getDimensions().size()];
		for (int i = 0; i < dims.length; i++) {
			dims[i] = toInt(evaluateExpression(shape.getDimensions().get(i), env));
		}
		return FEATURES.shape(dims);
	}

	/**
	 * Evaluates a function call expression, dispatching to built-in primitives or
	 * user-defined layer/model definitions.
	 *
	 * @param call The function call node
	 * @param env  Current variable environment
	 * @return The result of the function call (a Block, PackedCollection, Number, etc.)
	 */
	private Object evaluateFunctionCall(PdslNode.FunctionCall call,
										Environment env) {
		String name = call.getName();
		List<Object> args = new ArrayList<>();
		for (PdslNode.Expression argExpr : call.getArguments()) {
			args.add(evaluateExpression(argExpr, env));
		}

		// Try built-in functions first
		Object builtinResult = tryCallBuiltin(name, args);
		if (builtinResult != null) return builtinResult;

		// Try user-defined layers
		if (layerDefs.containsKey(name)) {
			return callUserLayer(name, args);
		}

		// Try calling as a method on a value passed as first arg (dot-call syntax)
		throw new PdslParseException(
				"Unknown function '" + name + "' at line " + call.getLine());
	}

	/**
	 * Evaluates a binary arithmetic operation ({@code +}, {@code -}, {@code *}, {@code /}).
	 *
	 * @param op  The binary operation node
	 * @param env Current variable environment
	 * @return The numeric result as a {@code Double}
	 */
	private Object evaluateBinaryOp(PdslNode.BinaryOp op, Environment env) {
		Object left = evaluateExpression(op.getLeft(), env);
		Object right = evaluateExpression(op.getRight(), env);
		double l = toDouble(left);
		double r = toDouble(right);
		switch (op.getOperator()) {
			case "+": return l + r;
			case "-": return l - r;
			case "*": return l * r;
			case "/": return l / r;
			default:
				throw new PdslParseException("Unknown operator: " + op.getOperator());
		}
	}

	/**
	 * Evaluates a unary operation (currently only negation {@code -}).
	 *
	 * @param op  The unary operation node
	 * @param env Current variable environment
	 * @return The numeric result as a {@code Double}
	 */
	private Object evaluateUnaryOp(PdslNode.UnaryOp op, Environment env) {
		Object operand = evaluateExpression(op.getOperand(), env);
		if ("-".equals(op.getOperator())) {
			return -toDouble(operand);
		}
		throw new PdslParseException("Unknown unary operator: " + op.getOperator());
	}

	/**
	 * Evaluates a field access expression ({@code config.field}) by looking up
	 * the field name in the evaluated object (expected to be a config map).
	 *
	 * @param access The field access node
	 * @param env    Current variable environment
	 * @return The field value from the config map
	 */
	private Object evaluateFieldAccess(PdslNode.FieldAccess access,
									   Environment env) {
		Object obj = evaluateExpression(access.getObject(), env);
		if (obj instanceof Map) {
			Object value = ((Map<String, Object>) obj).get(access.getField());
			if (value == null) {
				throw new PdslParseException(
						"Field '" + access.getField() + "' not found in config");
			}
			return value;
		}
		throw new PdslParseException(
				"Cannot access field on " + obj.getClass().getSimpleName());
	}

	/**
	 * Evaluates a weight reference expression, returning a prefixed key string
	 * that the caller resolves against a {@link org.almostrealism.ml.StateDictionary}.
	 *
	 * @param ref The weight reference node
	 * @param env Current variable environment
	 * @return A {@code "weight:<key>"} string identifying the weight
	 */
	private Object evaluateWeightRef(PdslNode.WeightRef ref, Environment env) {
		Object key = evaluateExpression(ref.getKeyExpression(), env);
		// WeightRef returns the key string; the loader resolves it via StateDictionary
		return "weight:" + key.toString();
	}

	// ---- Built-in function dispatch ----

	/**
	 * Attempts to resolve and execute a built-in function by name.
	 *
	 * @param name Name of the function
	 * @param args Evaluated arguments
	 * @return The result of the built-in, or {@code null} if the name is not a built-in
	 */
	private Object tryCallBuiltin(String name, List<Object> args) {
		switch (name) {
			case "dense": return callDense(args);
			case "rmsnorm": return callRmsnorm(args);
			case "softmax": return callSoftmax(args);
			case "silu": return callActivation("silu");
			case "relu": return callActivation("relu");
			case "gelu": return callActivation("gelu");
			case "sigmoid": return callActivation("sigmoid");
			case "tanh_act": return callActivation("tanh_act");
			case "slice": return callSlice(args);
			case "lerp": return callLerp(args);
				case "reshape": return callReshape(args);
			case "rope_rotation": return callRopeRotation(args);
			case "attention": return callAttention(args);
			case "transformer": return callTransformer(args);
			case "feed_forward": return callFeedForward(args);
				case "shape": return callShape(args);
			case "range": return callRange(args);
			case "fir": return callFir(args);
			case "scale": return callScale(args);
			case "lowpass": return callLowpass(args);
			case "highpass": return callHighpass(args);
			case "biquad": return callBiquad(args);
			case "delay": return callDelay(args);
			case "lfo": return callLfo(args);
			default: return null;
		}
	}

	/**
	 * Builds a dense (fully-connected) layer block from weight and optional bias arguments.
	 *
	 * @param args Evaluated arguments: weight tensor, and optionally bias tensor
	 * @return A dense {@link Block}
	 */
	private Object callDense(List<Object> args) {
		if (args.size() == 1) {
			return FEATURES.dense((PackedCollection) args.get(0));
		} else if (args.size() == 2) {
			return FEATURES.dense(
					(PackedCollection) args.get(0),
					(PackedCollection) args.get(1));
		}
		throw new PdslParseException(
				"dense() expects 1 or 2 arguments, got " + args.size());
	}

	/**
	 * Builds an RMSNorm layer from weight and epsilon arguments.
	 *
	 * @param args Evaluated arguments: weights tensor and epsilon value
	 * @return A shape-dependent {@link CellularLayer} factory
	 */
	private Object callRmsnorm(List<Object> args) {
		if (args.size() == 2) {
			PackedCollection weights = (PackedCollection) args.get(0);
			double epsilon = toDouble(args.get(1));
			return (Function<TraversalPolicy, CellularLayer>)
					(shape -> FEATURES.rmsnorm(shape, weights, epsilon));
		}
		throw new PdslParseException(
				"rmsnorm() expects 2 arguments (weights, epsilon), got " + args.size());
	}

	/**
	 * Builds a softmax activation block.
	 *
	 * @param args Must be empty
	 * @return A softmax {@link Block}
	 */
	private Object callSoftmax(List<Object> args) {
		if (args.isEmpty()) {
			return FEATURES.softmax();
		}
		throw new PdslParseException(
				"softmax() expects 0 arguments, got " + args.size());
	}

	/**
	 * Builds an activation block for the given activation type name.
	 *
	 * @param type One of {@code "silu"}, {@code "relu"}, or {@code "gelu"}
	 * @return The corresponding activation {@link Block}
	 */
	private Object callActivation(String type) {
		switch (type) {
			case "silu": return FEATURES.silu();
			case "relu": return FEATURES.relu();
			case "gelu": return FEATURES.gelu();
			case "sigmoid": return FEATURES.sigmoid();
			case "tanh_act": return FEATURES.tanh();
			default:
				throw new PdslParseException("Unknown activation: " + type);
		}
	}

	/**
	 * Builds a subset (slice) block from offset and size arguments.
	 *
	 * @param args two integer arguments: offset, size
	 * @return a factory that creates a slice block for any input shape
	 */
	private Object callSlice(List<Object> args) {
		if (args.size() == 2) {
			int offset = toInt(args.get(0));
			int size = toInt(args.get(1));
			return (Function<TraversalPolicy, Block>)
					(inputShape -> FEATURES.subset(inputShape, FEATURES.shape(size), offset));
		}
		throw new PdslParseException(
				"slice() expects 2 arguments (offset, size), got " + args.size());
	}

	/**
	 * Builds a lerp (linear interpolation) layer from a hidden-size argument.
	 *
	 * @param args one integer argument: hidden_size
	 * @return a factory that creates the lerp layer for any (3 * hidden_size) input shape
	 */
	private Object callLerp(List<Object> args) {
		if (args.size() == 1) {
			int hiddenSize = toInt(args.get(0));
			return (Function<TraversalPolicy, Block>)
					(inputShape -> FEATURES.lerpLayer(inputShape, hiddenSize));
		}
		throw new PdslParseException(
				"lerp() expects 1 argument (hidden_size), got " + args.size());
	}

	/**
	 * Builds a reshape block from one or two shape arguments.
	 *
	 * @param args Shape arguments: output shape only, or input shape then output shape
	 * @return A reshape {@link Block}
	 */
	private Object callReshape(List<Object> args) {
		if (args.size() == 1 && args.get(0) instanceof TraversalPolicy) {
			TraversalPolicy outputShape = (TraversalPolicy) args.get(0);
			return (Function<TraversalPolicy, Block>)
					(inputShape -> FEATURES.reshape(inputShape, outputShape));
		} else if (args.size() == 2
				&& args.get(0) instanceof TraversalPolicy
				&& args.get(1) instanceof TraversalPolicy) {
			TraversalPolicy inputShape = (TraversalPolicy) args.get(0);
			TraversalPolicy outputShape = (TraversalPolicy) args.get(1);
			return FEATURES.reshape(inputShape, outputShape);
		}
		throw new PdslParseException(
				"reshape() expects 1 or 2 shape arguments, got " + args.size());
	}

	/**
	 * Builds a RoPE rotary position embedding block.
	 *
	 * @param args Evaluated arguments: shape, frequency tensor, and position producer
	 * @return A RoPE rotation {@link Block}
	 */
	private Object callRopeRotation(List<Object> args) {
		if (args.size() == 3) {
			TraversalPolicy shape = (TraversalPolicy) args.get(0);
			PackedCollection freqCis = (PackedCollection) args.get(1);
			Producer<PackedCollection> position = toProducer(args.get(2));
			return FEATURES.ropeRotation(shape, freqCis, position);
		}
		throw new PdslParseException(
				"rope_rotation() expects 3 arguments (shape, freq_cis, position), got "
						+ args.size());
	}

	/**
	 * Builds an attention block from 8, 14, or 15 evaluated arguments.
	 *
	 * @param args Evaluated arguments matching one of the supported
	 *             {@link org.almostrealism.ml.AttentionFeatures#attention} overloads
	 * @return An attention {@link Block}
	 */
	private Object callAttention(List<Object> args) {
		if (args.size() == 8) {
			// attention(heads, rms_weight, wk, wv, wq, wo, freq_cis, position)
			return FEATURES.attention(
					toInt(args.get(0)),
					(PackedCollection) args.get(1),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					(PackedCollection) args.get(4),
					(PackedCollection) args.get(5),
					(PackedCollection) args.get(6),
					toProducer(args.get(7)));
		} else if (args.size() == 14) {
			// attention(heads, kv_heads, rms_weight, wk, wv, wq, wo,
			//           bk, bv, bq, qk_norm_q, qk_norm_k, freq_cis, position)
			return FEATURES.attention(
					toInt(args.get(0)),
					toInt(args.get(1)),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					(PackedCollection) args.get(4),
					(PackedCollection) args.get(5),
					(PackedCollection) args.get(6),
					(PackedCollection) args.get(7),
					(PackedCollection) args.get(8),
					(PackedCollection) args.get(9),
					(PackedCollection) args.get(10),
					(PackedCollection) args.get(11),
					(PackedCollection) args.get(12),
					toProducer(args.get(13)));
		} else if (args.size() == 15) {
			// attention(heads, kv_heads, rms_weight, wk, wv, wq, wo,
			//           bk, bv, bq, qk_norm_q, qk_norm_k, freq_cis, position, epsilon)
			return FEATURES.attention(
					toInt(args.get(0)),
					toInt(args.get(1)),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					(PackedCollection) args.get(4),
					(PackedCollection) args.get(5),
					(PackedCollection) args.get(6),
					(PackedCollection) args.get(7),
					(PackedCollection) args.get(8),
					(PackedCollection) args.get(9),
					(PackedCollection) args.get(10),
					(PackedCollection) args.get(11),
					(PackedCollection) args.get(12),
					toProducer(args.get(13)),
					toDouble(args.get(14)));
		}
		throw new PdslParseException(
				"attention() expects 8, 14, or 15 arguments, got " + args.size());
	}

	/**
	 * Builds a full transformer block from evaluated arguments.
	 *
	 * @param args Evaluated arguments matching the
	 *             {@link org.almostrealism.ml.AttentionFeatures#transformer} signature
	 * @return A transformer {@link Block}
	 */
	private Object callTransformer(List<Object> args) {
		if (args.size() == 19) {
			return FEATURES.transformer(
					toInt(args.get(0)),       // heads
					toInt(args.get(1)),       // kv_heads
					(PackedCollection) args.get(2),  // rms_att_weight
					(PackedCollection) args.get(3),  // wk
					(PackedCollection) args.get(4),  // wv
					(PackedCollection) args.get(5),  // wq
					(PackedCollection) args.get(6),  // wo
					(PackedCollection) args.get(7),  // bk
					(PackedCollection) args.get(8),  // bv
					(PackedCollection) args.get(9),  // bq
					(PackedCollection) args.get(10), // qk_norm_q
					(PackedCollection) args.get(11), // qk_norm_k
					(PackedCollection) args.get(12), // freq_cis
					(PackedCollection) args.get(13), // rms_ffn_weight
					(PackedCollection) args.get(14), // w1
					(PackedCollection) args.get(15), // w2
					(PackedCollection) args.get(16), // w3
					toProducer(args.get(17)),         // position
					toDouble(args.get(18)));          // epsilon
		}
		throw new PdslParseException(
				"transformer() expects 19 arguments, got " + args.size());
	}

	/**
	 * Builds a feed-forward (MLP) block from 4 or 5 evaluated weight arguments.
	 *
	 * @param args Evaluated arguments: RMSNorm weight, w1, w2, w3, and optionally epsilon
	 * @return A feed-forward {@link Block}
	 */
	private Object callFeedForward(List<Object> args) {
		if (args.size() == 4) {
			// feed_forward(rms, w1, w2, w3)
			return FEATURES.feedForward(
					(PackedCollection) args.get(0),
					(PackedCollection) args.get(1),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3));
		} else if (args.size() == 5) {
			// feed_forward(rms, w1, w2, w3, epsilon)
			return FEATURES.feedForward(
					(PackedCollection) args.get(0),
					(PackedCollection) args.get(1),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					toDouble(args.get(4)));
		}
		throw new PdslParseException(
				"feed_forward() expects 4 or 5 arguments, got " + args.size());
	}

	/**
	 * Constructs a {@link TraversalPolicy} from a variable number of integer dimension arguments.
	 *
	 * @param args Evaluated integer dimension values
	 * @return The corresponding traversal policy
	 */
	private Object callShape(List<Object> args) {
		int[] dims = new int[args.size()];
		for (int i = 0; i < args.size(); i++) {
			dims[i] = toInt(args.get(i));
		}
		return FEATURES.shape(dims);
	}

	/**
	 * Creates a zero-copy sub-view of a {@link PackedCollection} using
	 * {@link PackedCollection#range(TraversalPolicy, int)}.
	 *
	 * @param args [source: PackedCollection, shape: TraversalPolicy, offset: int]
	 * @return a zero-copy {@link PackedCollection} view of the requested sub-region
	 */
	private Object callRange(List<Object> args) {
		if (args.size() == 3) {
			PackedCollection source = (PackedCollection) args.get(0);
			TraversalPolicy shape = (TraversalPolicy) args.get(1);
			int offset = toInt(args.get(2));
			return source.range(shape, offset);
		}
		throw new PdslParseException(
				"range() expects 3 arguments (source, shape, offset), got " + args.size());
	}

	/**
	 * Builds a FIR (Finite Impulse Response) filter block that convolves the input signal
	 * with the provided coefficient array.
	 *
	 * @param args one weight argument: the FIR coefficient array ({@code filterOrder+1} elements)
	 * @return a factory that creates a FIR filter block for any input shape
	 */
	private Object callFir(List<Object> args) {
		if (args.size() == 1 && args.get(0) instanceof PackedCollection) {
			PackedCollection coefficients = (PackedCollection) args.get(0);
			return (Function<TraversalPolicy, Block>) (shape ->
					FEATURES.layer("fir", shape, shape,
							input -> MultiOrderFilter.create(input, FEATURES.p(coefficients))));
		}
		throw new PdslParseException(
				"fir() expects 1 weight argument (coefficients), got " + args.size());
	}

	/**
	 * Builds a scalar scaling block that multiplies each input element by the given factor.
	 *
	 * @param args one numeric argument: the scale factor
	 * @return a factory that creates a scale block for any input shape
	 */
	private Object callScale(List<Object> args) {
		if (args.size() == 1) {
			double factor = toDouble(args.get(0));
			return FEATURES.scale(factor);
		}
		throw new PdslParseException(
				"scale() expects 1 numeric argument (factor), got " + args.size());
	}

	/**
	 * Builds a low-pass FIR filter block using Hamming-windowed sinc coefficients.
	 *
	 * <p>Generates filter coefficients at block-build time using {@code lowPassCoefficients()}
	 * and wraps them in a {@link MultiOrderFilter} computation. The cutoff, sample rate,
	 * and filter order are fixed when the block is created.</p>
	 *
	 * @param args three numeric arguments: cutoff frequency (Hz), sample rate (Hz), filter order
	 * @return a factory that creates a low-pass FIR filter block for any input shape
	 */
	private Object callLowpass(List<Object> args) {
		if (args.size() == 3) {
			double cutoff = toDouble(args.get(0));
			int sampleRate = toInt(args.get(1));
			int order = toInt(args.get(2));
			CollectionProducer coefficients =
					FEATURES.lowPassCoefficients(FEATURES.c(cutoff), sampleRate, order);
			return (Function<TraversalPolicy, Block>) (shape ->
					FEATURES.layer("lowpass", shape, shape,
							input -> MultiOrderFilter.create(input, coefficients)));
		}
		throw new PdslParseException(
				"lowpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
	}

	/**
	 * Builds a high-pass FIR filter block using spectral inversion of the Hamming-windowed sinc.
	 *
	 * <p>Generates filter coefficients at block-build time using {@code highPassCoefficients()}
	 * and wraps them in a {@link MultiOrderFilter} computation. The cutoff, sample rate,
	 * and filter order are fixed when the block is created.</p>
	 *
	 * @param args three numeric arguments: cutoff frequency (Hz), sample rate (Hz), filter order
	 * @return a factory that creates a high-pass FIR filter block for any input shape
	 */
	private Object callHighpass(List<Object> args) {
		if (args.size() == 3) {
			double cutoff = toDouble(args.get(0));
			int sampleRate = toInt(args.get(1));
			int order = toInt(args.get(2));
			CollectionProducer coefficients =
					FEATURES.highPassCoefficients(FEATURES.c(cutoff), sampleRate, order);
			return (Function<TraversalPolicy, Block>) (shape ->
					FEATURES.layer("highpass", shape, shape,
							input -> MultiOrderFilter.create(input, coefficients)));
		}
		throw new PdslParseException(
				"highpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
	}

	/**
	 * Builds a stateful biquad IIR filter block.
	 *
	 * <p>The biquad difference equation applied per-sample is:
	 * {@code y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]}.
	 * The {@code history} collection {@code [x[n-1], x[n-2], y[n-1], y[n-2]]} is
	 * mutated in-place so state persists across successive block evaluations.</p>
	 *
	 * @param args six arguments: b0, b1, b2, a1, a2 (numeric), history (PackedCollection, size 4)
	 * @return a factory that creates a biquad filter block for any input shape
	 */
	private Object callBiquad(List<Object> args) {
		if (args.size() == 6) {
			double b0 = toDouble(args.get(0));
			double b1 = toDouble(args.get(1));
			double b2 = toDouble(args.get(2));
			double a1 = toDouble(args.get(3));
			double a2 = toDouble(args.get(4));
			PackedCollection history = (PackedCollection) args.get(5);
			return (Function<TraversalPolicy, Block>) (shape ->
					FEATURES.layer("biquad", shape, shape,
							input -> new DynamicCollectionProducer(shape,
									inputs -> a -> computeBiquad(inputs[0], b0, b1, b2, a1, a2, history),
									false, true, input)));
		}
		throw new PdslParseException(
				"biquad() expects 6 arguments (b0, b1, b2, a1, a2, history), got " + args.size());
	}

	/**
	 * Builds a stateful integer-sample delay line block.
	 *
	 * <p>Reads {@code delaySamples} samples behind the current write position
	 * from the circular {@code buffer} and writes the input signal into the buffer.
	 * Both {@code buffer} and {@code head} (the write-position counter) are mutated
	 * in-place so state persists across successive calls.</p>
	 *
	 * @param args three arguments: delaySamples (int), buffer (PackedCollection), head (PackedCollection, size 1)
	 * @return a factory that creates a delay-line block for any input shape
	 */
	private Object callDelay(List<Object> args) {
		if (args.size() == 3) {
			int delaySamples = toInt(args.get(0));
			PackedCollection buffer = (PackedCollection) args.get(1);
			PackedCollection head = (PackedCollection) args.get(2);
			return (Function<TraversalPolicy, Block>) (shape ->
					FEATURES.layer("delay", shape, shape,
							input -> new DynamicCollectionProducer(shape,
									inputs -> a -> computeDelay(inputs[0], delaySamples, buffer, head),
									false, true, input)));
		}
		throw new PdslParseException(
				"delay() expects 3 arguments (delaySamples, buffer, head), got " + args.size());
	}

	/**
	 * Builds a stateful sinusoidal LFO block.
	 *
	 * <p>Generates {@code n} samples of {@code sin(phase)} and advances the phase by
	 * {@code 2π * freqHz / sampleRate} per sample. The {@code phase} collection
	 * (size 1) is mutated in-place so phase is continuous across successive calls.</p>
	 *
	 * @param args three arguments: freqHz (numeric), sampleRate (numeric), phase (PackedCollection, size 1)
	 * @return a factory that creates an LFO block for any input shape
	 */
	private Object callLfo(List<Object> args) {
		if (args.size() == 3) {
			double freqHz = toDouble(args.get(0));
			double sampleRate = toDouble(args.get(1));
			PackedCollection phase = (PackedCollection) args.get(2);
			return (Function<TraversalPolicy, Block>) (shape ->
					FEATURES.layer("lfo", shape, shape,
							input -> new DynamicCollectionProducer(shape,
									inputs -> a -> computeLfo(shape.getSize(), freqHz, sampleRate, phase),
									false, true, input)));
		}
		throw new PdslParseException(
				"lfo() expects 3 arguments (freqHz, sampleRate, phase), got " + args.size());
	}

	/**
	 * Applies one block of samples through the biquad IIR filter in serial order,
	 * updating {@code history} in-place after each sample.
	 *
	 * @param signal  input signal block
	 * @param b0      feed-forward coefficient for x[n]
	 * @param b1      feed-forward coefficient for x[n-1]
	 * @param b2      feed-forward coefficient for x[n-2]
	 * @param a1      feedback coefficient for y[n-1]
	 * @param a2      feedback coefficient for y[n-2]
	 * @param history 4-element collection {@code [x[n-1], x[n-2], y[n-1], y[n-2]]}; mutated in-place
	 * @return filtered output block, same shape as {@code signal}
	 */
	private PackedCollection computeBiquad(PackedCollection signal,
											double b0, double b1, double b2,
											double a1, double a2,
											PackedCollection history) {
		int n = signal.getShape().getSize();
		PackedCollection output = new PackedCollection(signal.getShape());
		double xm1 = history.toDouble(0);
		double xm2 = history.toDouble(1);
		double ym1 = history.toDouble(2);
		double ym2 = history.toDouble(3);
		for (int i = 0; i < n; i++) {
			double x = signal.toDouble(i);
			double y = b0 * x + b1 * xm1 + b2 * xm2 - a1 * ym1 - a2 * ym2;
			output.setMem(i, y);
			xm2 = xm1;
			xm1 = x;
			ym2 = ym1;
			ym1 = y;
		}
		history.setMem(0, xm1);
		history.setMem(1, xm2);
		history.setMem(2, ym1);
		history.setMem(3, ym2);
		return output;
	}

	/**
	 * Reads a delayed copy of the input from a circular buffer and writes the input into it.
	 * Both {@code buffer} and {@code head} are mutated in-place.
	 *
	 * @param signal       input signal block
	 * @param delaySamples integer delay in samples (must be less than buffer length)
	 * @param buffer       circular delay-line buffer; mutated in-place
	 * @param head         1-element write-position counter; mutated in-place
	 * @return delayed output block, same shape as {@code signal}
	 */
	private PackedCollection computeDelay(PackedCollection signal,
										   int delaySamples,
										   PackedCollection buffer,
										   PackedCollection head) {
		int n = signal.getShape().getSize();
		int bufSize = buffer.getShape().getSize();
		PackedCollection output = new PackedCollection(signal.getShape());
		int writePos = (int) head.toDouble(0);
		for (int i = 0; i < n; i++) {
			int readPos = (writePos - delaySamples + bufSize) % bufSize;
			output.setMem(i, buffer.toDouble(readPos));
			buffer.setMem(writePos, signal.toDouble(i));
			writePos = (writePos + 1) % bufSize;
		}
		head.setMem(0, writePos);
		return output;
	}

	/**
	 * Generates {@code n} samples of a sinusoidal LFO, advancing the phase continuously.
	 * {@code phase} is mutated in-place so the next call resumes from the current phase.
	 *
	 * @param n          number of output samples to generate
	 * @param freqHz     LFO frequency in Hz
	 * @param sampleRate sample rate in Hz
	 * @param phase      1-element phase accumulator (radians); mutated in-place
	 * @return LFO output block of size {@code n}
	 */
	private PackedCollection computeLfo(int n, double freqHz, double sampleRate,
										 PackedCollection phase) {
		PackedCollection output = new PackedCollection(FEATURES.shape(n));
		double currentPhase = phase.toDouble(0);
		double phaseIncrement = 2.0 * Math.PI * freqHz / sampleRate;
		for (int i = 0; i < n; i++) {
			output.setMem(i, Math.sin(currentPhase));
			currentPhase += phaseIncrement;
			if (currentPhase >= 2.0 * Math.PI) {
				currentPhase -= 2.0 * Math.PI;
			}
		}
		phase.setMem(0, currentPhase);
		return output;
	}

	// ---- User-defined layer calls ----

	/**
	 * Instantiates a user-defined layer by binding arguments to its parameters
	 * and interpreting its body.
	 *
	 * @param name          Name of the layer definition to call
	 * @param evaluatedArgs Already-evaluated argument values
	 * @return The result of the layer body (typically a {@link Block})
	 */
	private Object callUserLayer(String name, List<Object> evaluatedArgs) {
		PdslNode.LayerDef def = layerDefs.get(name);
		List<PdslNode.Parameter> params = def.getParameters();

		if (evaluatedArgs.size() != params.size()) {
			throw new PdslParseException(
					"Layer '" + name + "' expects " + params.size()
							+ " arguments, got " + evaluatedArgs.size());
		}

		Map<String, Object> args = new HashMap<>();
		for (int i = 0; i < params.size(); i++) {
			args.put(params.get(i).getName(), evaluatedArgs.get(i));
		}

		// Determine input shape from the layer definition or from first weight parameter
		TraversalPolicy inputShape = inferInputShape(def, args);
		return buildLayer(name, inputShape, args);
	}

	/**
	 * Infers the input shape for a user-defined layer from its return-shape annotation
	 * or from the shape of the first weight parameter.
	 *
	 * @param def  The layer definition
	 * @param args Bound argument values keyed by parameter name
	 * @return The inferred input {@link TraversalPolicy}
	 * @throws PdslParseException If the shape cannot be determined
	 */
	private TraversalPolicy inferInputShape(PdslNode.LayerDef def,
											Map<String, Object> args) {
		// Try return shape annotation
		if (def.getReturnShape() != null) {
			Environment tempEnv = new Environment(null);
			populateDataDefs(args, tempEnv);
			for (Map.Entry<String, Object> entry : args.entrySet()) {
				tempEnv.set(entry.getKey(), entry.getValue());
			}
			return evaluateShape((PdslNode.ShapeLiteral) def.getReturnShape(), tempEnv);
		}

		// Infer from first weight parameter
		for (PdslNode.Parameter param : def.getParameters()) {
			if ("weight".equals(param.getTypeName())) {
				Object value = args.get(param.getName());
				if (value instanceof PackedCollection) {
					PackedCollection weight = (PackedCollection) value;
					int dim = weight.getShape().length(
							weight.getShape().getDimensions() - 1);
					return FEATURES.shape(1, dim);
				}
			}
		}

		throw new PdslParseException(
				"Cannot infer input shape for layer '" + def.getName()
						+ "'. Add a return shape annotation: -> [1, dim]");
	}

	// ---- Block construction helpers ----

	/**
	 * Appends a computed result to a sequential block, unwrapping factory functions if needed.
	 *
	 * @param block  Target block
	 * @param result Block, factory function, or other supported result type
	 */
	private void addToBlock(SequentialBlock block, Object result) {
		if (result instanceof Block) {
			block.add((Block) result);
		} else if (result instanceof Function) {
			block.add((Function<TraversalPolicy, ? extends Block>) result);
		} else {
			throw new PdslParseException(
					"Cannot add " + (result == null ? "null" : result.getClass().getSimpleName())
							+ " to block");
		}
	}

	/**
	 * Adds a computed result to a model as a block layer, unwrapping factory functions if needed.
	 *
	 * @param model  Target model
	 * @param result Block or factory function to add
	 */
	private void addToModel(Model model, Object result) {
		if (result instanceof Block) {
			model.add((Block) result);
		} else if (result instanceof Function) {
			model.add((Function<TraversalPolicy, ? extends Block>) result);
		} else {
			throw new PdslParseException(
					"Cannot add " + (result == null ? "null" : result.getClass().getSimpleName())
							+ " to model");
		}
	}

	/**
	 * Evaluates an expression and wraps the result as a {@link Block} with the given input shape.
	 *
	 * @param expr       Expression to evaluate (may be an inline block or a named block/factory)
	 * @param inputShape Input shape for factory-function blocks
	 * @param env        Current variable environment
	 * @return The resulting block
	 */
	private Block expressionToBlock(PdslNode.Expression expr,
									TraversalPolicy inputShape, Environment env) {
		if (expr instanceof PdslNode.InlineBlock) {
			PdslNode.InlineBlock inline = (PdslNode.InlineBlock) expr;
			SequentialBlock subBlock = new SequentialBlock(inputShape);
			interpretBody(inline.getBody(), subBlock, new Environment(env));
			return subBlock;
		}
		Object result = evaluateExpression(expr, env);
		return objectToBlock(result, inputShape);
	}

	/**
	 * Converts a generic object to a {@link Block}, applying factory functions if needed.
	 *
	 * @param result     Block or factory function
	 * @param inputShape Input shape to pass to factory functions
	 * @return The resulting block
	 */
	private Block objectToBlock(Object result, TraversalPolicy inputShape) {
		if (result instanceof Block) return (Block) result;
		if (result instanceof Function) {
			Function<TraversalPolicy, ? extends Block> factory =
					(Function<TraversalPolicy, ? extends Block>) result;
			return factory.apply(inputShape);
		}
		throw new PdslParseException(
				"Expected Block but got "
						+ (result == null ? "null" : result.getClass().getSimpleName()));
	}

	// ---- Type conversion helpers ----

	/**
	 * Converts a numeric object to an {@code int}.
	 *
	 * @param value Number value (Integer, Double, or other Number)
	 * @return Integer value
	 */
	private static int toInt(Object value) {
		if (value instanceof Integer) return (Integer) value;
		if (value instanceof Double) return ((Double) value).intValue();
		if (value instanceof Number) return ((Number) value).intValue();
		throw new PdslParseException("Expected int but got " + value);
	}

	/**
	 * Converts a numeric object to a {@code double}.
	 *
	 * @param value Number value (Double, Integer, or other Number)
	 * @return Double value
	 */
	private static double toDouble(Object value) {
		if (value instanceof Double) return (Double) value;
		if (value instanceof Integer) return (Integer) value;
		if (value instanceof Number) return ((Number) value).doubleValue();
		throw new PdslParseException("Expected number but got " + value);
	}

	/**
	 * Converts an object to a {@link Producer} of {@link PackedCollection}, wrapping
	 * a raw {@link PackedCollection} with {@code p()} if needed.
	 *
	 * @param value PackedCollection or already-wrapped Producer
	 * @return The producer
	 */
	private Producer<PackedCollection> toProducer(Object value) {
		if (value instanceof PackedCollection) {
			return FEATURES.p((PackedCollection) value);
		}
		if (value instanceof Producer) {
			return (Producer) value;
		}
		throw new PdslParseException("Expected PackedCollection or Producer but got " + value);
	}

	// ---- Environment ----

	/** Scoped variable environment with parent chain. */
	private static class Environment {
		/** Variable bindings in the current scope. */
		private final Map<String, Object> bindings = new HashMap<>();

		/** Enclosing scope, or {@code null} for the top-level scope. */
		private final Environment parent;

		/**
		 * Creates a new scope with the given parent.
		 *
		 * @param parent Enclosing scope, or {@code null} for the top-level scope
		 */
		Environment(Environment parent) {
			this.parent = parent;
		}

		/**
		 * Looks up a variable by name, walking the parent chain if not found locally.
		 *
		 * @param name Variable name
		 * @return The bound value, or {@code null} if not defined
		 */
		Object get(String name) {
			if (bindings.containsKey(name)) return bindings.get(name);
			if (parent != null) return parent.get(name);
			return null;
		}

		/**
		 * Binds a variable name to a value in the current scope.
		 *
		 * @param name  Variable name
		 * @param value Value to bind
		 */
		void set(String name, Object value) {
			bindings.put(name, value);
		}
	}

	/** Mixin type providing access to all framework feature default methods. */
	private static class Features implements AttentionFeatures, RotationFeatures, TemporalFeatures {
	}
}
