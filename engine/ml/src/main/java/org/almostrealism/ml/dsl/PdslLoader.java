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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public API for loading Producer DSL (.pdsl) files and constructing
 * {@link Block} and {@link Model} objects from them.
 *
 * <p>Usage example:
 * <pre>{@code
 * PdslLoader loader = new PdslLoader();
 *
 * // Parse a .pdsl file
 * PdslNode.Program program = loader.parse(Paths.get("model.pdsl"));
 *
 * // Build a layer with explicit arguments
 * Map<String, Object> args = new HashMap<>();
 * args.put("weights", myWeights);
 * args.put("epsilon", 1e-6);
 * Block block = loader.buildLayer(program, "my_layer",
 *                                  shape(1, 512), args);
 *
 * // Build a model with weights from StateDictionary
 * Model model = loader.buildModel(program, "my_model",
 *                                  shape(1, 512), stateDict);
 * }</pre>
 */
public class PdslLoader {

	/**
	 * Parse PDSL source text into an AST.
	 *
	 * @param source the PDSL source code
	 * @return the parsed program
	 */
	public PdslNode.Program parse(String source) {
		PdslLexer lexer = new PdslLexer(source);
		List<PdslToken> tokens = lexer.tokenize();
		PdslParser parser = new PdslParser(tokens);
		return parser.parse();
	}

	/**
	 * Parse a .pdsl file into an AST.
	 *
	 * @param path path to the .pdsl file
	 * @return the parsed program
	 * @throws IOException if the file cannot be read
	 */
	public PdslNode.Program parse(Path path) throws IOException {
		String source = new String(Files.readAllBytes(path));
		return parse(source);
	}

	/**
	 * Build a {@link Block} from a named layer definition.
	 *
	 * @param program    the parsed PDSL program
	 * @param layerName  the name of the layer to build
	 * @param inputShape the input tensor shape
	 * @param args       parameter bindings (name to value)
	 * @return the constructed Block
	 */
	public Block buildLayer(PdslNode.Program program, String layerName,
							TraversalPolicy inputShape, Map<String, Object> args) {
		PdslInterpreter interpreter = new PdslInterpreter(program);
		return interpreter.buildLayer(layerName, inputShape, args);
	}

	/**
	 * Build a {@link Model} from a named model definition.
	 *
	 * @param program    the parsed PDSL program
	 * @param modelName  the name of the model to build
	 * @param inputShape the input tensor shape
	 * @param args       parameter bindings
	 * @return the constructed Model
	 */
	public Model buildModel(PdslNode.Program program, String modelName,
							TraversalPolicy inputShape, Map<String, Object> args) {
		PdslInterpreter interpreter = new PdslInterpreter(program);
		return interpreter.buildModel(modelName, inputShape, args);
	}

	/**
	 * Build a {@link Model} from a PDSL model definition, binding
	 * weight parameters from a {@link StateDictionary}.
	 *
	 * <p>Weight parameters in the PDSL are matched to StateDictionary
	 * keys by name convention. Parameters whose names match the pattern
	 * of StateDictionary keys (e.g., "model.layers.0.self_attn.q_proj.weight")
	 * are automatically bound.
	 *
	 * @param program    the parsed PDSL program
	 * @param modelName  the name of the model to build
	 * @param inputShape the input tensor shape
	 * @param stateDict  weight source
	 * @param extraArgs  additional non-weight parameters (position, config values, etc.)
	 * @return the constructed Model
	 */
	public Model buildModel(PdslNode.Program program, String modelName,
							TraversalPolicy inputShape,
							StateDictionary stateDict,
							Map<String, Object> extraArgs) {
		PdslInterpreter interpreter = new PdslInterpreter(program);

		// Merge state dict weights into args
		Map<String, Object> args = new HashMap<>(extraArgs);
		args.put("state_dict", stateDict);

		return interpreter.buildModel(modelName, inputShape, args);
	}

	/**
	 * Evaluate a config block from the program.
	 *
	 * @param program    the parsed PDSL program
	 * @param configName the config name
	 * @return config entries as a map
	 */
	public Map<String, Object> evaluateConfig(PdslNode.Program program,
											  String configName) {
		PdslInterpreter interpreter = new PdslInterpreter(program);
		return interpreter.evaluateConfig(configName);
	}
}
