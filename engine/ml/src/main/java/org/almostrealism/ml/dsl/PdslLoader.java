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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
	 * Parsed programs of classpath .pdsl resources, keyed by absolute resource path. A resource
	 * on the classpath does not change while the JVM runs, and parsing it is a pure function of
	 * its text, so the parse is done once and the resulting {@link PdslNode.Program} is reused.
	 *
	 * <p>The cached program is safe to share across every build: interpretation reads the AST
	 * and never writes to it (a {@link PdslInterpreter} copies the definitions into its own maps,
	 * and each build evaluates {@code data}/{@code state} derivations into a fresh
	 * {@code Environment}), and the mutable state a layer needs — key and value caches and the
	 * like — is allocated by the caller and passed in as arguments, never held in a node. The
	 * cache is what keeps a model build from re-parsing an asset once per layer: every
	 * per-layer {@code new PdslLoader().parseResource(...)} after the first returns the same
	 * program.</p>
	 */
	private static final Map<String, PdslNode.Program> RESOURCE_CACHE = new ConcurrentHashMap<>();

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
	 * Build a {@link Model} from a PDSL model definition whose weights come from a
	 * {@link StateDictionary}. The dictionary is bound to every parameter the model declares
	 * with the type {@code checkpoint}; the model body then reads each weight by name, as in
	 * {@code weights.model.norm.weight}, or repeats over a group of them with
	 * {@code stack weights.model.layers as block { ... }}.
	 *
	 * @param program      the parsed PDSL program
	 * @param modelName    the name of the model to build
	 * @param inputShape   the input tensor shape
	 * @param stateDict    weight source
	 * @param extraArgs    the model's other parameters (position, config values, etc.)
	 * @param requirements compute requirements applied to every layer the model constructs
	 * @return the constructed Model
	 * @throws PdslParseException if the model does not exist or declares no {@code checkpoint}
	 *         parameter
	 */
	public Model buildModel(PdslNode.Program program, String modelName,
							TraversalPolicy inputShape,
							StateDictionary stateDict,
							Map<String, Object> extraArgs,
							ComputeRequirement... requirements) {
		return newInterpreter(program).buildModel(modelName, inputShape, stateDict, extraArgs, requirements);
	}

	/**
	 * Parse a PDSL program from a classpath resource, resolving its {@code import} statements.
	 *
	 * <p>The resource path must be absolute (e.g. {@code "/pdsl/midi/skytnt_block.pdsl"}).
	 * An {@link IllegalStateException} is thrown if the resource cannot be found or read.</p>
	 *
	 * <p>Imports are resolved transitively — the returned program holds this resource's own
	 * definitions together with those of every asset it imports, and every asset those import,
	 * so a caller building a layer from this asset need only name it. A resource reached by more
	 * than one import path (a diamond) is merged once; an import cycle and a name defined by two
	 * distinct resources are both rejected with a {@link PdslParseException}. A resource that
	 * imports nothing is returned exactly as it is cached, so repeated loads share one instance.</p>
	 *
	 * @param classpathResource absolute classpath path to the .pdsl resource
	 * @return the parsed program, with imported definitions merged in
	 * @throws IllegalStateException if the resource, or one it imports, is not found or cannot be read
	 * @throws PdslParseException    if an import cycle is detected, or a name is defined by two
	 *                               distinct resources for the same kind of definition
	 */
	public PdslNode.Program parseResource(String classpathResource) {
		PdslNode.Program parsed = parseSingleResource(classpathResource);
		if (parsed.getImports().isEmpty()) {
			return parsed;
		}
		return mergeResources(classpathResource);
	}

	/**
	 * Parse several classpath .pdsl resources into one program, so that a layer of one asset
	 * can call the layers of another. Prefer declaring an {@code import} in the asset that
	 * depends on another; this method remains the way to assemble genuinely unrelated assets
	 * into one program. Each resource is parsed on its own (so a parse error reports its line
	 * within that resource) together with everything it imports, and the definitions are
	 * gathered in the order given, imported dependencies before the resource that imports them.
	 *
	 * @param classpathResources absolute classpath paths of the .pdsl resources
	 * @return the program holding every definition of every resource and its imports
	 * @throws IllegalStateException if a resource is not found or cannot be read
	 * @throws PdslParseException    if an import cycle is detected, or a name is defined more than
	 *                               once for the same kind of definition, which would otherwise
	 *                               leave all but one of the definitions silently unreachable
	 */
	public PdslNode.Program parseResources(String... classpathResources) {
		return mergeResources(classpathResources);
	}

	/**
	 * Parse a single classpath .pdsl resource, without resolving its imports. The result is
	 * memoized in {@link #RESOURCE_CACHE} — a resource does not change while the JVM runs and
	 * parsing it is a pure function of its text — so it is parsed at most once per JVM. The
	 * cached program is treated as read-only: {@link #mergeResources} copies its definitions
	 * into a fresh list rather than mutating it.
	 *
	 * @param classpathResource absolute classpath path to the .pdsl resource
	 * @return the resource's own parsed program, imports unresolved
	 */
	private PdslNode.Program parseSingleResource(String classpathResource) {
		return RESOURCE_CACHE.computeIfAbsent(normalizeResource(classpathResource),
				resource -> parse(readResource(resource)));
	}

	/**
	 * Normalize a classpath resource path to a canonical form so that two spellings of the same
	 * resource resolve to one key everywhere the loader compares resources — the parse cache, cycle
	 * detection, the diamond-dedup {@code merged} set, and the {@code definedBy} duplicate check.
	 * Without this, an absolute alias such as {@code /pdsl/imports/leaf.pdsl} and
	 * {@code /pdsl/imports/../imports/leaf.pdsl} would be treated as two resources: a diamond using
	 * both spellings would merge the file twice and report a false duplicate-definition error, and
	 * the same text would be parsed and cached under two keys.
	 *
	 * <p>Collapses empty and {@code .} segments and resolves {@code ..} segments against the
	 * segments already accumulated, preserving whether the path is absolute. A {@code ..} that would
	 * climb above an absolute root is dropped, matching how a classpath resource of that shape is
	 * itself resolved.</p>
	 *
	 * @param classpathResource the resource path as written in an {@code import} or passed by a caller
	 * @return the canonical path, with {@code .} and {@code ..} segments resolved
	 */
	private static String normalizeResource(String classpathResource) {
		boolean absolute = classpathResource.startsWith("/");
		Deque<String> segments = new ArrayDeque<>();
		for (String segment : classpathResource.split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				if (!segments.isEmpty() && !segments.peekLast().equals("..")) {
					segments.removeLast();
				} else if (!absolute) {
					segments.addLast("..");
				}
				continue;
			}
			segments.addLast(segment);
		}
		String joined = String.join("/", segments);
		return absolute ? "/" + joined : joined;
	}

	/**
	 * Resolve {@code roots} and their transitive imports into one program. Each resource is
	 * merged once (so a diamond of imports pulls a shared dependency in a single time); a
	 * resource reached while it is still being resolved is an import cycle; and a name defined
	 * by two distinct resources for the same kind of definition is a collision. Definitions are
	 * gathered depth-first, imports before importer, in the order the roots are given.
	 *
	 * @param roots absolute classpath paths of the resources to resolve
	 * @return the merged program built from copies of the cached definitions
	 * @throws PdslParseException if an import cycle or a duplicate definition name is detected
	 */
	private PdslNode.Program mergeResources(String... roots) {
		List<PdslNode.Definition> definitions = new ArrayList<>();
		Map<String, String> definedBy = new HashMap<>();
		Set<String> merged = new HashSet<>();
		List<String> importing = new ArrayList<>();
		for (String root : roots) {
			mergeResource(root, definitions, definedBy, merged, importing);
		}
		return new PdslNode.Program(definitions);
	}

	/**
	 * Merge one resource and everything it imports into {@code definitions}, depth-first.
	 *
	 * @param resource    the resource to merge
	 * @param definitions accumulates the definitions of every resource merged
	 * @param definedBy   maps each definition key to the resource that first defined it
	 * @param merged      resources already fully merged, so a diamond dependency is added once
	 * @param importing   the chain of resources currently being resolved, for cycle detection
	 * @throws PdslParseException if {@code resource} closes an import cycle, or redefines a name
	 */
	private void mergeResource(String resource, List<PdslNode.Definition> definitions,
							   Map<String, String> definedBy, Set<String> merged, List<String> importing) {
		resource = normalizeResource(resource);
		if (merged.contains(resource)) {
			return;
		}
		if (importing.contains(resource)) {
			throw new PdslParseException("'" + resource + "' imports itself ("
					+ importCycle(importing, resource) + "); a PDSL asset cannot import itself,"
					+ " directly or transitively");
		}
		importing.add(resource);
		PdslNode.Program parsed = parseSingleResource(resource);
		for (PdslNode.Import imported : parsed.getImports()) {
			mergeResource(imported.getResource(), definitions, definedBy, merged, importing);
		}
		for (PdslNode.Definition definition : parsed.getDefinitions()) {
			String key = definition.getClass().getSimpleName() + " " + definition.getName();
			String prior = definedBy.putIfAbsent(key, resource);
			if (prior != null) {
				throw new PdslParseException("'" + definition.getName() + "' is defined more than once"
						+ " (in " + prior + " and " + resource + ")");
			}
			definitions.add(definition);
		}
		merged.add(resource);
		importing.remove(importing.size() - 1);
	}

	/**
	 * Describes the chain of imports currently being resolved that leads back to {@code resource},
	 * for the message of an import-cycle {@link PdslParseException}. Mirrors the layer
	 * self-recursion cycle reported by the interpreter.
	 *
	 * @param importing the resources currently being resolved, outermost first
	 * @param resource  the resource whose re-entry closes the cycle
	 * @return the cycle chain, e.g. {@code a.pdsl -> b.pdsl -> a.pdsl}
	 */
	private static String importCycle(List<String> importing, String resource) {
		List<String> chain = new ArrayList<>(importing.subList(importing.indexOf(resource), importing.size()));
		chain.add(resource);
		return String.join(" -> ", chain);
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
