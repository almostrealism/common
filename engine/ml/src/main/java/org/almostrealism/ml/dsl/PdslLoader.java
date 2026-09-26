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
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
	 * Hook applied to every freshly-constructed {@link PdslInterpreter}. Domain modules
	 * supply registration code here (for example
	 * {@code AudioDspPrimitives::registerWith}) so that the interpreter is configured
	 * with the appropriate primitives before any layer or model is built. Defaults to
	 * a no-op for callers that only use the built-in ML primitives.
	 */
	private final Consumer<PdslInterpreter> primitiveRegistrar;

	/**
	 * Default constructor — produces a loader that uses only built-in ML primitives.
	 * Use {@link #PdslLoader(Consumer)} to register domain primitives such as the
	 * audio DSP set.
	 */
	public PdslLoader() {
		this(interpreter -> {});
	}

	/**
	 * Construct a loader that applies {@code primitiveRegistrar} to every interpreter
	 * it creates. Typical usage:
	 * <pre>{@code
	 * PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
	 * }</pre>
	 *
	 * @param primitiveRegistrar consumer invoked once per interpreter; must not be null
	 */
	public PdslLoader(Consumer<PdslInterpreter> primitiveRegistrar) {
		this.primitiveRegistrar = primitiveRegistrar == null
				? interpreter -> {}
				: primitiveRegistrar;
	}

	/**
	 * Builds a fresh interpreter for {@code program} and applies the configured
	 * {@link #primitiveRegistrar} so domain primitives are available before any
	 * layer or model construction begins.
	 *
	 * @param program the parsed program
	 * @return a configured interpreter
	 */
	private PdslInterpreter newInterpreter(PdslNode.Program program) {
		PdslInterpreter interpreter = new PdslInterpreter(program);
		primitiveRegistrar.accept(interpreter);
		return interpreter;
	}

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
		return buildLayer(program, layerName, inputShape, args, new ComputeRequirement[0]);
	}

	/**
	 * Build a {@link Block} from a named layer definition, applying compute requirements
	 * to every layer the definition constructs.
	 *
	 * @param program      the parsed PDSL program
	 * @param layerName    the name of the layer to build
	 * @param inputShape   the input tensor shape
	 * @param args         parameter bindings (name to value)
	 * @param requirements compute requirements applied to every constructed layer
	 * @return the constructed Block
	 */
	public Block buildLayer(PdslNode.Program program, String layerName,
							TraversalPolicy inputShape, Map<String, Object> args,
							ComputeRequirement... requirements) {
		PdslInterpreter interpreter = newInterpreter(program);
		return interpreter.buildLayer(layerName, inputShape, args, requirements);
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
		PdslInterpreter interpreter = newInterpreter(program);
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
		PdslInterpreter interpreter = newInterpreter(program);

		// Merge state dict weights into args
		Map<String, Object> args = new HashMap<>(extraArgs);
		args.put("state_dict", stateDict);

		return interpreter.buildModel(modelName, inputShape, args);
	}

	/**
	 * Parse a PDSL program from a classpath resource.
	 *
	 * <p>The resource path must be absolute (e.g. {@code "/pdsl/midi/skytnt_block.pdsl"}).
	 * An {@link IllegalStateException} is thrown if the resource cannot be found or read.</p>
	 *
	 * @param classpathResource absolute classpath path to the .pdsl resource
	 * @return the parsed program
	 * @throws IllegalStateException if the resource is not found or cannot be read
	 */
	public PdslNode.Program parseResource(String classpathResource) {
		return parse(readResource(classpathResource));
	}

	/**
	 * Parse several classpath .pdsl resources into one program, so that a layer of one asset
	 * can call the layers of another (as {@code transformer.pdsl} calls the attention layers of
	 * {@code attention.pdsl} and the {@code swiglu_ffn} layer of {@code feed_forward.pdsl}).
	 * Each resource is parsed on its own, so a parse error reports its line within that
	 * resource, and the definitions of all resources are gathered in the order given.
	 *
	 * @param classpathResources absolute classpath paths of the .pdsl resources
	 * @return the program holding every definition of every resource
	 * @throws IllegalStateException if a resource is not found or cannot be read
	 * @throws PdslParseException    if a name is defined more than once for the same kind of
	 *                               definition, which would otherwise leave all but one of the
	 *                               definitions silently unreachable
	 */
	public PdslNode.Program parseResources(String... classpathResources) {
		List<PdslNode.Definition> definitions = new ArrayList<>();
		Set<String> defined = new HashSet<>();
		for (String resource : classpathResources) {
			for (PdslNode.Definition definition : parseResource(resource).getDefinitions()) {
				String key = definition.getClass().getSimpleName() + " " + definition.getName();
				if (!defined.add(key)) {
					throw new PdslParseException("'" + definition.getName() + "' is defined more than once"
							+ " among " + String.join(", ", classpathResources) + " (again in " + resource + ")");
				}
				definitions.add(definition);
			}
		}
		return new PdslNode.Program(definitions);
	}

	/**
	 * Read the raw text of a classpath .pdsl resource, without parsing it.
	 *
	 * <p>The resource path must be absolute (e.g. {@code "/pdsl/midi/skytnt_block.pdsl"}).
	 * An {@link IllegalStateException} is thrown if the resource cannot be found or read.
	 * Useful for combining multiple resources into one source string (for example, a
	 * production asset with a test-only wrapper layer) before parsing.</p>
	 *
	 * @param classpathResource absolute classpath path to the .pdsl resource
	 * @return the resource's source text
	 * @throws IllegalStateException if the resource is not found or cannot be read
	 */
	public String readResource(String classpathResource) {
		try (InputStream is = PdslLoader.class.getResourceAsStream(classpathResource)) {
			if (is == null) {
				throw new IllegalStateException("PDSL resource not found on classpath: " + classpathResource);
			}
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load PDSL resource: " + classpathResource, e);
		}
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
		PdslInterpreter interpreter = newInterpreter(program);
		return interpreter.evaluateConfig(configName);
	}

	/**
	 * Evaluate a data block from the program, binding external inputs from
	 * {@code args} and computing all derived views in declaration order.
	 *
	 * <p>This is useful when Java code needs access to the derived
	 * {@link org.almostrealism.collect.PackedCollection} sub-views produced by
	 * {@code range()} expressions without building a full layer.
	 *
	 * @param program  the parsed PDSL program
	 * @param dataName the data block name
	 * @param args     external input values (name → value)
	 * @return all data block entries (parameters + derivations) as a map
	 */
	public Map<String, Object> evaluateDataDef(PdslNode.Program program,
											   String dataName,
											   Map<String, Object> args) {
		PdslInterpreter interpreter = newInterpreter(program);
		return interpreter.evaluateDataDef(dataName, args);
	}
}
