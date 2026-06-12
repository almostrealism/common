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
import org.almostrealism.io.Console;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.time.TemporalFeatures;

import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toDouble;
import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toInt;

import java.util.Objects;

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
 *   <li>{@code identity()} - pass-through block, forward and backward unchanged</li>
 *   <li>{@code scale(factor)} - element-wise multiply by a scalar producer</li>
 *   <li>{@code repeat(n)} - replicate the input along axis 0 to produce {@code n}
 *       times as many leading rows (equivalent to {@code CollectionProducer.repeat(0, n)})</li>
 *   <li>{@code sum_channels()} - collapse a {@code [C, S]} tensor to {@code [1, S]}
 *       by summing along axis 0</li>
 *   <li>{@code rope_rotation(shape, freq_cis, position)}</li>
 *   <li>{@code attention(...)}, {@code transformer(...)},
 *       {@code feed_forward(...)}</li>
 *   <li>{@code embedding(table)}</li>
 * </ul>
 *
 * <p>Domain-specific primitives (audio DSP, multi-channel routing, etc.) are not
 * defined in this class. They are contributed by higher-level modules via
 * {@link #registerPrimitive(String, PdslPrimitive)} so that the interpreter core
 * stays free of any domain-specific dispatch. See
 * {@code org.almostrealism.studio.dsl.audio.AudioDspPrimitives} for the audio set.</p>
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
	private static final PdslFeatures FEATURES = PdslFeatures.INSTANCE;

	/**
	 * Registry of domain primitives contributed by higher-level modules via
	 * {@link #registerPrimitive(String, PdslPrimitive)}. The interpreter consults
	 * this map first when dispatching a function call so domain primitives can
	 * shadow built-in names if needed. The registry is heterogeneous — each
	 * primitive declares its own result type via the {@code T} parameter on
	 * {@link PdslPrimitive} — so the map values are typed as
	 * {@code PdslPrimitive<?>}.
	 */
	private final Map<String, PdslPrimitive<?>> registeredPrimitives;

	/**
	 * When true, {@code for each channel} first attempts to compile its body ONCE over
	 * the full {@code [channels, signalSize]} shape, with {@code [channel]} subscripts
	 * yielding whole {@link PdslChannelBank banks} for bank-aware primitives. Bodies
	 * containing any construct that cannot take the bank form fall back to the
	 * per-channel dispatch automatically (the bank wrapper cannot pass through the
	 * standard argument paths, so non-bank-aware primitives fail loudly rather than
	 * misinterpret it). Vectorized bodies avoid the per-channel slice/dispatch/concat
	 * structure, whose per-stage dispatch overhead dominates small per-channel kernels.
	 *
	 * <p>OFF BY DEFAULT (enable via {@code AR_PDSL_VECTOR_FOREACH=enabled}): vectorized
	 * bodies built for different channel layouts in one JVM can be structurally
	 * identical, and the signature-keyed instruction reuse currently rebinds such twins
	 * incorrectly across models (silent wrong results — reproduced by running
	 * {@code MixdownManagerPdslTest}'s square and rectangular efx bus tests together;
	 * {@code AR_INSTRUCTION_SET_REUSE=disabled} makes the combination safe). Flip the
	 * default once instruction rebinding handles structural twins.</p>
	 */
	public static boolean enableVectorizedForEach =
			SystemUtils.isEnabled("AR_PDSL_VECTOR_FOREACH").orElse(false);

	/**
	 * Sentinel bound to {@code channel} during vectorized {@code for each channel}
	 * interpretation; subscripts that resolve their index to this value produce a
	 * {@link PdslChannelBank} instead of a single channel's row.
	 */
	private static final Object ALL_CHANNELS = new Object();

	/** Builds the multi-channel block backing the {@code for each channel} statement. */
	private PdslMultiChannelDispatcher multiChannelDispatcher;

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
		this.registeredPrimitives = new HashMap<>();
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
	 * Register a domain-specific primitive. The interpreter consults the registry
	 * before any built-in dispatch so a registered primitive shadows any built-in
	 * of the same name.
	 *
	 * @param name      the function name as it appears in PDSL source
	 * @param primitive the dispatcher for this primitive
	 */
	public <T> void registerPrimitive(String name, PdslPrimitive<T> primitive) {
		registeredPrimitives.put(Objects.requireNonNull(name, "name"),
				Objects.requireNonNull(primitive, "primitive"));
	}

	/**
	 * Set the dispatcher used to interpret the {@code for each channel} statement.
	 *
	 * @param dispatcher the multi-channel dispatcher; may be {@code null} to detach
	 */
	public void setMultiChannelDispatcher(PdslMultiChannelDispatcher dispatcher) {
		this.multiChannelDispatcher = dispatcher;
	}

	/**
	 * Normalises a heterogeneous PDSL argument value into a
	 * {@link CollectionProducer}{@code <PackedCollection>} of the requested shape.
	 * This is the single conversion point shared by parameter binding and primitive
	 * dispatch — {@link PdslPrimitiveContext#toProducer toProducer} delegates here.
	 *
	 * @param value         the raw argument value
	 * @param expectedShape the required shape
	 * @param contextName   prefix used in error messages
	 * @return a {@link CollectionProducer} of the declared shape
	 * @throws PdslParseException if the value is unsupported or the shape mismatches
	 */
	static CollectionProducer normalizeToProducer(
			Object value, TraversalPolicy expectedShape, String contextName) {
		CollectionProducer base;
		if (value instanceof Number) {
			base = FEATURES.c(((Number) value).doubleValue());
		} else if (value instanceof PackedCollection) {
			base = FEATURES.cp((PackedCollection) value);
		} else if (value instanceof Producer) {
			base = FEATURES.c((Producer<PackedCollection>) value);
		} else {
			throw new PdslParseException(contextName + " expects Number, PackedCollection, or Producer; got "
					+ (value == null ? "null" : value.getClass().getSimpleName()));
		}

		if (expectedShape == null) {
			return base;
		}

		TraversalPolicy actual = FEATURES.shape(base);
		if (actual.getTotalSize() == expectedShape.getTotalSize()) {
			return actual.equals(expectedShape) ? base : base.reshape(expectedShape);
		}
		if (actual.getTotalSize() == 1) {
			// Scalar broadcast: a size-1 value satisfies any declared shape. A
			// per-channel argument (e.g. producer([channels])) supplied as a single
			// scalar is shared across every channel — the per-channel subscript
			// (arg[channel]) returns the scalar as-is for each channel.
			return base;
		}
		throw new PdslParseException(contextName + " expects shape " + expectedShape
				+ " but value has total size " + actual.getTotalSize());
	}

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
			if ("producer".equals(param.getTypeName())) {
				value = bindProducerParameter(param, value, env);
			}
			env.set(param.getName(), value);
		}
		SequentialBlock block = new SequentialBlock(inputShape);
		interpretBody(def.getBody(), block, env);
		return block;
	}

	/**
	 * Binds a {@code producer([shape])} parameter to a {@link Producer} of
	 * {@link PackedCollection}. The actual conversion (Number, PackedCollection, or
	 * Producer with shape validation) is delegated to
	 * {@link #normalizeToProducer(Object, TraversalPolicy, String)} so that parameter
	 * binding and primitive-argument normalisation share one definition.
	 *
	 * @param param the parameter declaration (with declared shape)
	 * @param value the caller-supplied value from the args map
	 * @param env   current environment, used to evaluate the declared shape expression
	 * @return a {@link Producer} of {@link PackedCollection}
	 */
	private Object bindProducerParameter(PdslNode.Parameter param, Object value, Environment env) {
		if (param.getShape() == null) {
			throw new PdslParseException(
					"Parameter '" + param.getName()
							+ "' declared as producer must have a shape, e.g. producer([1])");
		}
		TraversalPolicy declaredShape = evaluateShape((PdslNode.ShapeLiteral) param.getShape(), env);
		return normalizeToProducer(value, declaredShape,
				"Parameter '" + param.getName() + "'");
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
			// Skip state blocks whose parameters are not in args; this state block
			// is not used by the layer being built and its variables need not be
			// populated into the environment.
			boolean allPresent = true;
			for (PdslNode.Parameter param : def.getParameters()) {
				if (!args.containsKey(param.getName())) {
					allPresent = false;
					break;
				}
			}
			if (!allPresent) continue;

			for (PdslNode.Parameter param : def.getParameters()) {
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
		} else if (stmt instanceof PdslNode.ForEachChannelStatement) {
			interpretForEachChannel((PdslNode.ForEachChannelStatement) stmt, block, env);
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
	 * Interprets an {@code accum_blocks} statement by applying N sub-blocks to the
	 * same input and accumulating their outputs element-wise.
	 */
	private void interpretAccumBlocks(PdslNode.AccumBlocksStatement accumStmt,
									  SequentialBlock block, Environment env) {
		TraversalPolicy inputShape = block.getOutputShape();
		List<Block> subBlocks = new ArrayList<>();
		for (PdslNode.Expression expr : accumStmt.getBlocks()) {
			subBlocks.add(expressionToBlock(expr, inputShape, env));
		}
		if (subBlocks.isEmpty()) {
			throw new PdslParseException("accum_blocks requires at least one branch body");
		}
		block.add(FEATURES.accumBlocks(inputShape, subBlocks));
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
		} else if (expr instanceof PdslNode.Subscript) {
			PdslNode.Subscript subscript = (PdslNode.Subscript) expr;
			Object obj = evaluateExpression(subscript.getObject(), env);
			Object indexValue = evaluateExpression(subscript.getIndex(), env);
			if (indexValue == ALL_CHANNELS
					&& (obj instanceof PackedCollection || obj instanceof CollectionProducer)) {
				// Vectorized for-each: the subscript covers every channel at once, so the
				// whole per-channel bank is handed to the primitive (wrapped, so only
				// bank-aware primitives can accept it).
				return new PdslChannelBank(obj, toInt(env.get("channels")));
			}
			if (obj instanceof PackedCollection) {
				PackedCollection coll = (PackedCollection) obj;
				int index = toInt(indexValue);
				int channels = toInt(env.get("channels"));
				int stride = coll.getShape().getSize() / channels;
				return coll.range(FEATURES.shape(stride), index * stride);
			}
			if (obj instanceof CollectionProducer) {
				CollectionProducer producer = (CollectionProducer) obj;
				int channels = toInt(env.get("channels"));
				int totalSize = FEATURES.shape(producer).getTotalSize();
				if (totalSize < channels) {
					// Scalar/sub-channel broadcast: a size-1 producer is shared
					// across every channel, so each channel reads the same value.
					return producer;
				}
				int index = toInt(indexValue);
				int stride = totalSize / channels;
				CollectionProducer flat = producer.reshape(FEATURES.shape(totalSize));
				return FEATURES.subset(FEATURES.shape(stride), flat, index * stride);
			}
			throw new PdslParseException(
					"Subscript not supported on " + (obj == null ? "null" : obj.getClass().getSimpleName()));
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

		// Domain primitives (audio DSP, multi-channel routing, etc.) are looked up
		// before built-ins so registered primitives may shadow built-in names.
		PdslPrimitive<?> registered = registeredPrimitives.get(name);
		if (registered != null) {
			return registered.dispatch(args, new EnvContext(env));
		}

		// Try built-in functions
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
	 * Attempts to resolve and execute a built-in function by name. The built-in
	 * library itself lives in {@link PdslBuiltins}; the interpreter only routes
	 * already-evaluated arguments to it.
	 *
	 * @param name Name of the function
	 * @param args Evaluated arguments
	 * @return The result of the built-in, or {@code null} if the name is not a built-in
	 */
	private Object tryCallBuiltin(String name, List<Object> args) {
		return PdslBuiltins.call(name, args);
	}

	// ---- Multi-channel statement-level dispatch ----
	//
	// `for each channel { ... }` is a language-level statement (a PdslNode.Statement)
	// rather than a primitive call. The body of the per-channel sub-block is
	// interpreted here (it requires interpretBody recursion, which is private to the
	// interpreter), but the multi-channel block factory is delegated through
	// {@link PdslMultiChannelDispatcher} so the interpreter does not have to know
	// about audio-domain block construction.

	/** Interprets {@code for each channel}, vectorizing the body when its primitives allow. */
	private void interpretForEachChannel(PdslNode.ForEachChannelStatement stmt,
										  SequentialBlock block, Environment env) {
		if (multiChannelDispatcher == null) {
			throw new PdslParseException(
					"`for each channel` requires a PdslMultiChannelDispatcher to be registered "
							+ "via PdslInterpreter.setMultiChannelDispatcher(...)");
		}
		int channels = toInt(env.get("channels"));
		TraversalPolicy currentShape = block.getOutputShape();
		int signalSize = currentShape.getDimensions() >= 2
				? currentShape.length(currentShape.getDimensions() - 1)
				: currentShape.getSize() / channels;

		// Vectorization assumes the upstream signal actually spans `channels` rows; a
		// rectangular route can leave a DIFFERENT channel count flowing into a later
		// for-each, which only the per-channel dispatch handles.
		boolean channelAligned = currentShape.getDimensions() >= 2
				&& currentShape.length(0) == channels;

		if (enableVectorizedForEach && channelAligned) {
			// Attempt to compile the body ONCE over [channels, signalSize]: subscripted
			// per-channel arguments resolve to whole banks, and bank-aware primitives
			// apply them in a single computation. Any construct that cannot take the
			// bank form rejects it, and the per-channel dispatch below applies instead.
			try {
				Environment bankEnv = new Environment(env);
				bankEnv.set("channel", ALL_CHANNELS);
				SequentialBlock bankBlock =
						new SequentialBlock(FEATURES.shape(channels, signalSize));
				interpretBody(stmt.getBody(), bankBlock, bankEnv);
				block.add(bankBlock);
				Console.root().features(PdslInterpreter.class)
						.log("forEachChannel vectorized=true channels=" + channels
								+ " signalSize=" + signalSize);
				return;
			} catch (PdslParseException | UnsupportedOperationException
					| IllegalArgumentException e) {
				Console.root().features(PdslInterpreter.class)
						.log("forEachChannel vectorized=false channels=" + channels
								+ " signalSize=" + signalSize + " cause=" + e.getMessage());
				// Fall through to per-channel dispatch.
			}
		}

		TraversalPolicy singleChannelShape = FEATURES.shape(1, signalSize);
		List<Block> channelBlocks = new ArrayList<>();
		for (int i = 0; i < channels; i++) {
			Environment channelEnv = new Environment(env);
			channelEnv.set("channel", i);
			SequentialBlock channelBlock = new SequentialBlock(singleChannelShape);
			interpretBody(stmt.getBody(), channelBlock, channelEnv);
			channelBlocks.add(channelBlock);
		}
		block.add(multiChannelDispatcher.perChannel(channelBlocks, channels, signalSize));
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

	// ---- Environment ----

	/** Scoped variable environment with parent chain for PDSL layer interpretation. */
	private static class Environment {
		/** Variable bindings in the current scope. */
		private final Map<String, Object> bindings = new HashMap<>();
		/** Enclosing scope, or {@code null} for the top-level scope. */
		private final Environment parent;
		/** Creates a new scope with the given enclosing scope. */
		Environment(Environment parent) { this.parent = parent; }
		/** Returns the value bound to {@code name}, walking the parent chain. */
		Object get(String name) {
			if (bindings.containsKey(name)) return bindings.get(name);
			return parent != null ? parent.get(name) : null;
		}
		/** Binds a name to a value in the current scope. */
		void set(String name, Object value) { bindings.put(name, value); }
	}

	/**
	 * Adapter from a PDSL {@link Environment} to {@link PdslPrimitiveContext}, created
	 * once per primitive call. Reads {@code channels} and {@code signal_size} from the
	 * environment on demand and routes argument normalisation through
	 * {@link #normalizeToProducer(Object, TraversalPolicy, String)}.
	 */
	private static class EnvContext implements PdslPrimitiveContext {
		/** The interpreter's environment from the layer being built. */
		private final Environment env;

		EnvContext(Environment env) { this.env = env; }

		@Override
		public int channels() {
			Object value = env.get("channels");
			if (value == null) {
				throw new PdslParseException(
						"Primitive requires `channels` to be defined in the PDSL environment");
			}
			return toInt(value);
		}

		@Override
		public int signalSize() {
			Object value = env.get("signal_size");
			if (value == null) {
				throw new PdslParseException(
						"Primitive requires `signal_size` to be defined in the PDSL environment");
			}
			return toInt(value);
		}

		@Override
		public void setChannels(int channels) {
			env.set("channels", channels);
		}

		@Override
		public CollectionProducer toProducer(Object value,
															  TraversalPolicy expectedShape,
															  String contextName) {
			return normalizeToProducer(value, expectedShape, contextName);
		}
	}
}
