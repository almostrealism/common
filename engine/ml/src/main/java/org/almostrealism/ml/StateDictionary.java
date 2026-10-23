/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.persist.assets.Asset;
import org.almostrealism.persist.assets.AssetGroup;
import org.almostrealism.persist.assets.AssetGroupInfo;
import org.almostrealism.hardware.mem.FileMapping;
import org.almostrealism.persist.assets.CollectionDataMemoryProvider;
import org.almostrealism.persist.assets.CollectionDataReference;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.persist.assets.EncodedMessage;
import org.almostrealism.persist.assets.SafetensorsReference;
import org.almostrealism.persist.assets.ValueEncoding;
import org.almostrealism.protobuf.Collections;

import io.almostrealism.code.Precision;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link StateDictionary} provides access to model weights stored in protobuf format.
 * <p>
 * It reads {@link org.almostrealism.protobuf.Collections.CollectionLibraryData}
 * from binary protobuf files from a directory and uses {@link CollectionEncoder}
 * to decode them into {@link PackedCollection}s.
 *
 * @author  Michael Murray
 */
public class StateDictionary extends AssetGroup implements Destroyable, ConsoleFeatures {
	/**
	 * When {@code true}, weight tensors are decoded eagerly into freshly
	 * allocated collections. When {@code false}, weight tensors keep their
	 * protobuf messages as backing store via
	 * {@link CollectionEncoder#decode(Collections.CollectionData, boolean)},
	 * deferring device involvement until a kernel first uses each weight.
	 * Passed directly as that method's {@code materialize} parameter.
	 */
	public static boolean enableMaterializeWeights = false;

	/**
	 * File name extension of a safetensors checkpoint. A directory holding any such file is read
	 * as a published checkpoint: its safetensors files are the weights, and its other files
	 * (configuration, tokenizer) are ignored.
	 */
	public static final String SAFETENSORS_EXTENSION = ".safetensors";

	/** Field number of {@code collections} within {@code CollectionLibraryData}. */
	private static final int LIBRARY_COLLECTIONS_FIELD = 1;

	/** Field number of {@code key} within {@code CollectionLibraryEntry}. */
	private static final int ENTRY_KEY_FIELD = 1;

	/** Field number of {@code collection} within {@code CollectionLibraryEntry}. */
	private static final int ENTRY_COLLECTION_FIELD = 2;

	/** The in-memory map from weight key names to their decoded {@link PackedCollection} tensors. */
	private Map<String, PackedCollection> weights;

	/**
	 * The source {@link ValueEncoding} of each entry that was loaded from an integer payload. An
	 * entry here is written back as that integer payload by {@link #save}, regardless of the
	 * precision argument, so a checkpoint's index arrays keep their integers through a round trip
	 * without the caller naming them. Entries loaded from a floating-point source are not recorded;
	 * the precision argument governs those.
	 */
	private Map<String, ValueEncoding> sourceEncodings;

	/**
	 * Whether this dictionary owns the native memory of its tensors. A dictionary that loads its
	 * weights from files or is constructed with its own map owns them, so {@link #destroy()}
	 * releases them. A {@link #group} shares another dictionary's tensors rather than copying them,
	 * so it is non-owning: destroying a group clears its own view without touching the shared
	 * weights the root dictionary (and any sibling group) still rely on.
	 */
	private final boolean owning;

	/**
	 * Create a {@link StateDictionary} by loading weights from the specified directory.
	 *
	 * @param weightsDirectory Directory containing protobuf weight files
	 * @throws IOException if files cannot be read or parsed
	 */
	public StateDictionary(String weightsDirectory) throws IOException {
		super(weightsDirectory);
		this.owning = true;
		init();
	}

	/**
	 * Create a {@link StateDictionary} by loading weights identified by an {@link AssetGroupInfo}.
	 *
	 * @param assets AssetGroupInfo pointing to the weights and metadata.
	 * @throws IOException  if the assets cannot be obtained, read or parsed
	 */
	public StateDictionary(AssetGroupInfo assets) throws IOException {
		super(assets);
		this.owning = true;
		init();
	}

	/**
	 * Create a {@link StateDictionary} by loading weights from provided {@link Asset}s.
	 *
	 * @param assets Assets containing the weights.
	 * @throws IOException  if the assets cannot be read or parsed
	 */
	public StateDictionary(List<Asset> assets) throws IOException {
		super(assets);
		this.owning = true;
		init();
	}

	/**
	 * Create a {@link StateDictionary} with manually provided weights (for testing). The dictionary
	 * owns the supplied tensors, so {@link #destroy()} releases them.
	 *
	 * @param weights Map of weight names to PackedCollections
	 */
	public StateDictionary(Map<String, PackedCollection> weights) {
		this(weights, new HashMap<>(), true);
	}

	/**
	 * Create a {@link StateDictionary} over the given weight map, owning or sharing them. A sharing
	 * (non-owning) dictionary is the backing of {@link #group}: it exposes tensors held by another
	 * dictionary without taking responsibility for their native memory.
	 *
	 * @param weights         Map of weight names to PackedCollections
	 * @param sourceEncodings the integer source encoding of each entry that has one
	 * @param owning          whether this dictionary owns the tensors' native memory
	 */
	private StateDictionary(Map<String, PackedCollection> weights,
							Map<String, ValueEncoding> sourceEncodings, boolean owning) {
		this.weights = weights;
		this.sourceEncodings = sourceEncodings;
		this.owning = owning;
	}

	/**
	 * Initializes the weight map and loads all weight tensors from the underlying assets.
	 *
	 * @throws IOException if any asset cannot be read or decoded
	 */
	protected void init() throws IOException {
		this.weights = new HashMap<>();
		this.sourceEncodings = new HashMap<>();

		try {
			loadWeights();
		} catch (IOException | RuntimeException e) {
			// The dictionary is never returned to a caller who could destroy it, so the
			// tensors located before the failure would otherwise keep their files mapped.
			destroy();
			throw e;
		}
	}

	/**
	 * Load weights from the {@link org.almostrealism.persist.assets.Asset}s: the safetensors
	 * files among them if there are any, and otherwise every file as a protobuf library.
	 *
	 * <p>Each asset's file is resolved once. Resolving an asset verifies its checksum and may
	 * download it, which is too costly to repeat for each pass over the files.</p>
	 *
	 * @throws IOException if a safetensors file cannot be read
	 * @throws IllegalArgumentException if a safetensors file is malformed, or a tensor is defined
	 *         by more than one of them
	 */
	private void loadWeights() throws IOException {
		List<File> available = files()
				.filter(Objects::nonNull)
				.filter(File::exists)
				.collect(Collectors.toList());

		List<File> safetensors = available.stream()
				.filter(f -> f.getName().endsWith(SAFETENSORS_EXTENSION))
				.collect(Collectors.toList());
		if (!safetensors.isEmpty()) {
			// A published checkpoint directory also holds its configuration and tokenizer;
			// only the safetensors files hold weights.
			Map<String, File> shards = new HashMap<>();
			for (File file : safetensors) {
				log("Located " + locateSafetensors(file, shards) + " weight tensors in " + file.getName());
			}
			logLoaded(safetensors.size(), "safetensors");
			return;
		}

		int total = available.stream()
				.filter(f -> !f.getName().startsWith("."))
				.mapToInt(weightFile -> {
			try {
				int loaded = enableMaterializeWeights ?
						readWeights(weightFile) : locateWeights(weightFile);

				log("Loaded " + loaded + " weight tensors from " +
						weightFile.getName());
				return 1;
			} catch (Exception e) {
				warn("Error reading weights from file " + weightFile.getName() + ": " + e.getMessage());
				return 0;
			}
		}).sum();

		logLoaded(total, "protobuf");
	}

	/**
	 * Locates each tensor in the given library without reading any of them.
	 *
	 * <p>The file is walked for structure only: what each tensor is called,
	 * what shape it is, and where its values are. The values themselves stay
	 * in the file until something reads them, so a model's weights cost the
	 * Java heap nothing between being opened and being used, and a tensor no
	 * kernel ever asks for costs it nothing at all.</p>
	 *
	 * <p>The mapping opened to walk the structure is held only for that walk and
	 * released before returning: each located tensor holds its own mapping of the
	 * file for the deferred read of its values, so this reader is redundant once
	 * the walk is done. Releasing it in a {@code finally} also keeps a file that
	 * fails to parse — a metadata sidecar or a legacy {@code .bin} left beside the
	 * shards — from leaking a native mapping.</p>
	 *
	 * @param weightFile the library to locate tensors in
	 * @return the number of tensors located
	 * @throws IOException if the file cannot be read
	 */
	private int locateWeights(File weightFile) throws IOException {
		FileMapping mapping = FileMapping.of(weightFile,
				CollectionDataMemoryProvider.VALUE_ORDER);

		try {
			EncodedMessage library = new EncodedMessage(mapping.buffer(), 0);

			List<EncodedMessage> entries = library.fields(LIBRARY_COLLECTIONS_FIELD);

			for (EncodedMessage entry : entries) {
				String key = entry.stringOf(ENTRY_KEY_FIELD);
				CollectionDataReference reference =
						CollectionDataReference.within(entry, ENTRY_COLLECTION_FIELD);

				if (key != null && reference != null) {
					weights.put(key, CollectionEncoder.decode(reference, weightFile, false));
					recordEncoding(key, reference.getEncoding());
				}
			}

			return entries.size();
		} finally {
			mapping.release();
		}
	}

	/**
	 * Locates each tensor of a safetensors checkpoint file by its header, as
	 * {@link #locateWeights} does for a protobuf library: the values stay in the file until
	 * something reads them, unless {@link #enableMaterializeWeights} asks for them to be copied
	 * into freshly allocated memory now.
	 *
	 * <p>A sharded checkpoint assigns each tensor to exactly one shard, and the order the shards
	 * are read in is not part of that contract. A name that a shard read earlier already defined
	 * is therefore rejected, before any tensor of this file is added, rather than letting the
	 * filesystem's ordering decide which of the two definitions survives.</p>
	 *
	 * @param file   the safetensors file
	 * @param shards the shard each tensor read so far came from, updated with this file's tensors
	 * @return the number of tensors located
	 * @throws IOException if the file cannot be read
	 * @throws IllegalArgumentException if a tensor in the file is also defined by an earlier shard
	 */
	private int locateSafetensors(File file, Map<String, File> shards) throws IOException {
		Map<String, SafetensorsReference> tensors = SafetensorsReference.locate(file);
		for (String key : tensors.keySet()) {
			File earlier = shards.get(key);
			if (earlier != null) {
				throw new IllegalArgumentException("The tensor " + key + " is defined by both "
						+ earlier.getName() + " and " + file.getName());
			}
		}

		tensors.keySet().forEach(key -> shards.put(key, file));
		tensors.forEach((key, reference) -> {
			weights.put(key, CollectionEncoder.decode(reference, file, enableMaterializeWeights));
			recordEncoding(key, reference.getEncoding());
		});
		return tensors.size();
	}

	/**
	 * Records the source encoding of a loaded entry when it is an integer encoding, so {@link #save}
	 * can keep it an integer payload. A floating-point source is not recorded; the precision
	 * argument governs those entries.
	 *
	 * @param key      the entry's key
	 * @param encoding the encoding the entry was loaded from, or {@code null}
	 */
	private void recordEncoding(String key, ValueEncoding encoding) {
		if (encoding != null && encoding.isInteger()) {
			sourceEncodings.put(key, encoding);
		}
	}

	/**
	 * Reads every tensor in the given library into freshly allocated memory.
	 *
	 * @param weightFile the library to read
	 * @return the number of tensors read
	 * @throws IOException if the file cannot be read
	 */
	private int readWeights(File weightFile) throws IOException {
		try (FileInputStream fis = new FileInputStream(weightFile)) {
			Collections.CollectionLibraryData libraryData =
					Collections.CollectionLibraryData.parseFrom(fis);

			for (Collections.CollectionLibraryEntry entry : libraryData.getCollectionsList()) {
				PackedCollection collection =
						CollectionEncoder.decode(entry.getCollection(), true);

				if (collection != null) {
					weights.put(entry.getKey(), collection);
					recordEncoding(entry.getKey(), ValueEncoding.of(entry.getCollection()));
				}
			}

			return libraryData.getCollectionsCount();
		}
	}

	/**
	 * Reports what was loaded, and from how many files.
	 *
	 * @param total  the number of files read
	 * @param format the name of the on-disk format the files are in, for the diagnostic message
	 */
	private void logLoaded(int total, String format) {
		log("StateDictionary loaded " + weights.size() +
				" total weight tensors from " + total + " " + format + " files");
	}

	/**
	 * Get a weight by key.
	 *
	 * @param key Weight key
	 * @return PackedCollection containing the weight data, or null if not found
	 */
	public PackedCollection get(String key) {
		return weights.get(key);
	}

	/**
	 * Get a weight by key, failing when the dictionary has none by that name. The failure names
	 * the keys that share the longest leading part with the requested one, so a misspelled or
	 * misplaced name (a wrong layer index, {@code q_proj} for {@code k_proj}) points at what the
	 * dictionary actually holds.
	 *
	 * @param key Weight key
	 * @return PackedCollection containing the weight data
	 * @throws IllegalArgumentException if there is no weight named {@code key}
	 */
	public PackedCollection require(String key) {
		PackedCollection weight = weights.get(key);
		if (weight != null) return weight;

		int bestShared = -1;
		List<String> nearest = new ArrayList<>();
		for (String candidate : weights.keySet()) {
			int shared = 0;
			int limit = Math.min(candidate.length(), key.length());
			while (shared < limit && candidate.charAt(shared) == key.charAt(shared)) shared++;
			if (shared > bestShared) {
				bestShared = shared;
				nearest.clear();
			}
			if (shared == bestShared) nearest.add(candidate);
		}

		nearest.sort(Comparator.naturalOrder());
		throw new IllegalArgumentException("No weight named '" + key + "' among "
				+ weights.size() + " weights"
				+ (nearest.isEmpty() ? "" : "; the closest names are "
						+ String.join(", ", nearest.subList(0, Math.min(3, nearest.size())))));
	}

	/**
	 * Returns the weights under one name of this dictionary's dotted hierarchy, as a dictionary
	 * of its own whose keys omit that name: in a checkpoint, {@code group("model")} holds
	 * {@code layers.0.mlp.up_proj.weight} for {@code model.layers.0.mlp.up_proj.weight}. The
	 * group shares this dictionary's tensors rather than copying them, and is a non-owning view:
	 * destroying a group releases nothing and leaves the root dictionary (and any sibling group)
	 * intact, while destroying the root releases the weights every group drew from.
	 *
	 * @param name a name, or dotted path of names, within this dictionary
	 * @return the weights under {@code name}; empty if there are none
	 */
	public StateDictionary group(String name) {
		String prefix = name + ".";
		Map<String, PackedCollection> members = new HashMap<>();
		Map<String, ValueEncoding> memberEncodings = new HashMap<>();
		weights.forEach((key, weight) -> {
			if (key.startsWith(prefix)) {
				String member = key.substring(prefix.length());
				members.put(member, weight);
				ValueEncoding encoding = sourceEncodings.get(key);
				if (encoding != null) memberEncodings.put(member, encoding);
			}
		});
		return new StateDictionary(members, memberEncodings, false);
	}

	/**
	 * Returns the names at the top of this dictionary's dotted hierarchy, in order: the
	 * {@link #group} names that, with the weights named directly, make up the dictionary. Names
	 * that are all whole numbers, such as the layers of a checkpoint's {@code model.layers}, are
	 * in numeric order ({@code 2} before {@code 10}), whatever their magnitude, with names of
	 * equal value ({@code 1} and {@code 01}) in alphabetical order; any other names are in
	 * alphabetical order.
	 *
	 * @return the distinct first segments of this dictionary's keys
	 */
	public List<String> members() {
		List<String> names = new ArrayList<>(weights.keySet().stream()
				.map(key -> key.contains(".") ? key.substring(0, key.indexOf('.')) : key)
				.collect(Collectors.toSet()));
		if (names.stream().allMatch(n -> n.matches("\\d+"))) {
			names.sort(Comparator.comparing((String n) -> new BigInteger(n)).thenComparing(Comparator.naturalOrder()));
		} else {
			names.sort(Comparator.naturalOrder());
		}
		return names;
	}

	/**
	 * Check if a weight exists for the given key.
	 *
	 * @param key Weight key
	 * @return true if weight exists, false otherwise
	 */
	public boolean containsKey(String key) {
		return weights.containsKey(key);
	}

	/**
	 * Get all weight keys.
	 *
	 * @return Set of all weight keys
	 */
	public Set<String> keySet() {
		return weights.keySet();
	}

	/**
	 * Get the number of loaded weights.
	 *
	 * @return Number of weights
	 */
	public int size() {
		return weights.size();
	}

	/**
	 * Get all weights as a map (for compatibility with existing code).
	 *
	 * @return Map of all weights
	 */
	public Map<String, PackedCollection> getAllWeights() {
		return new HashMap<>(weights);
	}

	/**
	 * Add or replace a weight in this dictionary.
	 *
	 * @param key Weight key
	 * @param weight PackedCollection containing the weight data
	 */
	public void put(String key, PackedCollection weight) {
		if (weights == null) {
			weights = new HashMap<>();
		}
		weights.put(key, weight);
	}

	/**
	 * Save all weights to a single protobuf file.
	 *
	 * @param outputPath Path to write the weights file
	 * @throws IOException if writing fails
	 */
	public void save(Path outputPath) throws IOException {
		save(outputPath, Precision.FP32);
	}

	/**
	 * Save all weights to a single protobuf file with specified precision.
	 *
	 * @param outputPath Path to write the weights file
	 * @param precision Precision for encoding (FP32 or FP64)
	 * @throws IOException if writing fails
	 */
	public void save(Path outputPath, Precision precision) throws IOException {
		Collections.CollectionLibraryData libraryData = encode(weights, precision, sourceEncodings);
		try (OutputStream out = Files.newOutputStream(outputPath)) {
			libraryData.writeTo(out);
		}
	}

	/**
	 * Encode a map of weights to protobuf format.
	 *
	 * @param weights Map of weight names to PackedCollections
	 * @return Encoded protobuf data
	 */
	public static Collections.CollectionLibraryData encode(Map<String, PackedCollection> weights) {
		return encode(weights, Precision.FP32);
	}

	/**
	 * Encode a map of weights to protobuf format with specified precision.
	 *
	 * @param weights Map of weight names to PackedCollections
	 * @param precision Precision for encoding (FP32 or FP64)
	 * @return Encoded protobuf data
	 */
	public static Collections.CollectionLibraryData encode(Map<String, PackedCollection> weights, Precision precision) {
		return encode(weights, precision, Map.of());
	}

	/**
	 * Encode a map of weights to protobuf format, writing each entry named in {@code encodings} as
	 * that encoding's payload and every other entry at the given floating-point precision.
	 *
	 * <p>This is how a caller that builds an index collection itself asks for it to be written as an
	 * integer payload: name it in {@code encodings} with {@link ValueEncoding#I32} or
	 * {@link ValueEncoding#I64}. It is also how {@link #save} keeps an entry loaded from an integer
	 * source an integer, by passing the encodings it recorded while loading.</p>
	 *
	 * @param weights   Map of weight names to PackedCollections
	 * @param precision Precision for encoding floating-point entries (FP32 or FP64)
	 * @param encodings The integer encoding to write a named entry as; other entries use {@code precision}
	 * @return Encoded protobuf data
	 * @throws IllegalArgumentException if an entry named in {@code encodings} holds a value that is
	 *         not a whole number in range for the named integer encoding
	 */
	public static Collections.CollectionLibraryData encode(Map<String, PackedCollection> weights,
			Precision precision, Map<String, ValueEncoding> encodings) {
		Collections.CollectionLibraryData.Builder libraryBuilder = Collections.CollectionLibraryData.newBuilder();

		for (Map.Entry<String, PackedCollection> entry : weights.entrySet()) {
			ValueEncoding encoding = encodings.get(entry.getKey());
			Collections.CollectionData collection = encoding != null && encoding.isInteger() ?
					CollectionEncoder.encode(entry.getValue(), encoding) :
					CollectionEncoder.encode(entry.getValue(), precision);

			libraryBuilder.addCollections(Collections.CollectionLibraryEntry.newBuilder()
					.setKey(entry.getKey())
					.setCollection(collection)
					.build());
		}

		return libraryBuilder.build();
	}

	/**
	 * Destroy all loaded weight data this dictionary owns. A {@link #group} is a non-owning view,
	 * so destroying one clears its own view of the shared tensors without releasing them — the
	 * root dictionary and any sibling group keep working — while destroying an owning dictionary
	 * releases the tensors every group drew from.
	 *
	 * @see PackedCollection#destroy()
	 */
	@Override
	public void destroy() {
		if (weights != null) {
			if (owning) weights.values().forEach(PackedCollection::destroy);
			weights.clear();
			weights = null;
		}
	}
}