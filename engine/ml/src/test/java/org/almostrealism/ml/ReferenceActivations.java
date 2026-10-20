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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;

import java.io.File;
import java.io.IOException;

/**
 * A directory of reference activations written by the extraction scripts'
 * {@code dump_reference_activations}, as consumed by the gated numerical-parity tests. The
 * directory is located among candidate paths at test time and is never committed.
 *
 * <p>The dumps are protobuf collection data — the same format a weight export is written in, and
 * the format tensors cross the language boundary in generally — so they are read here with
 * {@link StateDictionary}, which maps the shards rather than materializing them and carries each
 * tensor's shape alongside its values. Nothing in this class parses a serialization of its own.</p>
 *
 * <p>A reference is named by a key rather than by a file. Some weight dumps do still write a file
 * per tensor, so {@link #firstExisting} accepts a marker that names either.</p>
 *
 * <p>The shards are opened once, on first use, and every reference read afterwards is a view into
 * them; {@link #destroy()} releases them, after which the collections already returned must no
 * longer be used and a later read opens the shards again.</p>
 */
public class ReferenceActivations implements Destroyable {

	/** Suffix a per-tensor weight dump names its files with. */
	private static final String FILE_SUFFIX = ".bin";

	/** The directory holding the reference shards. */
	private final File directory;

	/** The shards, opened on first use. */
	private StateDictionary references;

	/**
	 * Wraps a directory of reference shards.
	 *
	 * @param directory the directory
	 */
	public ReferenceActivations(File directory) {
		this.directory = directory;
	}

	/**
	 * The directory holding the reference shards.
	 *
	 * @return the directory
	 */
	public File getDirectory() { return directory; }

	/**
	 * The tensor a marker names, without the file suffix a per-tensor dump would give it.
	 *
	 * @param name a tensor key, or the file name a per-tensor dump would write it to
	 * @return the key
	 */
	public static String key(String name) {
		return name.endsWith(FILE_SUFFIX)
				? name.substring(0, name.length() - FILE_SUFFIX.length()) : name;
	}

	/**
	 * The key under which {@code name} is stored, preferring an exact match so a tensor whose key
	 * itself ends in {@link #FILE_SUFFIX} stays readable, and falling back to the suffix-stripped
	 * {@link #key(String)} so a legacy per-tensor {@code .bin} file marker still resolves to the
	 * tensor it names.
	 *
	 * @param references the shards to resolve against
	 * @param name       a tensor key, or the file name a per-tensor dump would write it to
	 * @return the resolved key
	 */
	private static String resolveKey(StateDictionary references, String name) {
		return references.containsKey(name) ? name : key(name);
	}

	/**
	 * Whether {@code references} holds {@code name} under its exact key or, failing that, under the
	 * suffix-stripped {@link #key(String)}.
	 *
	 * @param references the shards
	 * @param name       a tensor key, or the file name a per-tensor dump would write it to
	 * @return whether the tensor is present
	 */
	private static boolean containsResolved(StateDictionary references, String name) {
		return references.containsKey(name) || references.containsKey(key(name));
	}

	/**
	 * The shards in this directory, read on first use.
	 *
	 * @return the references
	 * @throws IOException if the directory cannot be read
	 */
	public StateDictionary getReferences() throws IOException {
		if (references == null) {
			references = new StateDictionary(directory.getPath());
		}

		return references;
	}

	/**
	 * Whether this directory's shards hold the named reference.
	 *
	 * @param name the reference key, or the file name a per-tensor dump would write it to
	 * @return whether the reference is present
	 * @throws IOException if the shards cannot be read
	 */
	public boolean contains(String name) throws IOException {
		return containsResolved(getReferences(), name);
	}

	/**
	 * Releases the shards, if they have been opened. Collections read before this call are views
	 * into the released shards and must not be used afterwards.
	 */
	@Override
	public void destroy() {
		if (references != null) {
			references.destroy();
			references = null;
		}
	}

	/**
	 * Reads one reference as a collection, mapped rather than materialized, with the shape the
	 * dump recorded for it.
	 *
	 * @param name the reference key
	 * @return the values
	 * @throws IOException if the shards cannot be read
	 * @throws IllegalStateException if the directory holds no such reference
	 */
	public PackedCollection collection(String name) throws IOException {
		StateDictionary references = getReferences();
		String resolved = resolveKey(references, name);
		PackedCollection result = references.get(resolved);

		if (result == null) {
			throw new IllegalStateException(resolved + " is not among the references in "
					+ directory + " (" + references.keySet() + ")");
		}

		return result;
	}

	/**
	 * Reads one reference as a collection, checking it against the shape the caller expects.
	 *
	 * <p>A mismatch fails here rather than being reinterpreted: the dump records the shape it
	 * wrote, so a disagreement means the capture and the test no longer describe the same tensor.
	 * Two shapes of the same rank must name the same axes — a transposed capture (a recorded
	 * {@code (3, 4)} read as {@code (4, 3)}) is rejected rather than silently reshaped. A rank
	 * change with the same element count is still permitted, so a scalar or flat reference may be
	 * shaped to the caller's layout.</p>
	 *
	 * @param name  the reference key
	 * @param shape the expected shape
	 * @return the values
	 * @throws IOException if the shards cannot be read
	 * @throws IllegalStateException if the reference is absent or of another shape
	 */
	public PackedCollection collection(String name, TraversalPolicy shape) throws IOException {
		PackedCollection result = collection(name);
		TraversalPolicy actual = result.getShape();

		boolean sameCount = actual.getTotalSize() == shape.getTotalSize();
		boolean axesAgree = actual.getDimensions() != shape.getDimensions()
				|| actual.equalsIgnoreAxis(shape);
		if (!sameCount || !axesAgree) {
			throw new IllegalStateException(key(name) + ": reference holds "
					+ actual + " while the test expects " + shape);
		}

		return result.reshape(shape);
	}

	/**
	 * Reads one reference as a flat array of values.
	 *
	 * @param name the reference key
	 * @return the values
	 * @throws IOException if the shards cannot be read
	 */
	public float[] load(String name) throws IOException {
		PackedCollection values = collection(name);
		double[] read = values.toArray(0, values.getShape().getTotalSize());
		float[] result = new float[read.length];

		for (int i = 0; i < read.length; i++) {
			result[i] = (float) read[i];
		}

		return result;
	}

	/**
	 * Returns the first directory among {@code candidates} that exists and holds {@code marker},
	 * either as a file of its own or as a key among its shards, or {@code null} if none does.
	 *
	 * <p>Both are accepted because the marker names a tensor, and a directory of tensors is a
	 * protobuf shard set whichever kind of dump wrote it — while some weight dumps still write a
	 * file per tensor.</p>
	 *
	 * @param candidates candidate directory paths (entries may be {@code null})
	 * @param marker     a tensor that must be present within the directory
	 * @return the resolved directory, or {@code null}
	 */
	public static File firstExisting(String[] candidates, String marker) {
		for (String candidate : candidates) {
			if (candidate == null) continue;

			File dir = new File(candidate);
			if (!dir.isDirectory()) continue;

			if (new File(dir, marker).exists() || holds(dir, marker)) {
				return dir;
			}
		}

		return null;
	}

	/**
	 * Whether the given directory's shards hold the named tensor.
	 *
	 * <p>A directory that is not a shard set at all — one holding a file per tensor, say — simply
	 * does not hold it; a caller asking whether a candidate is the right directory does not want
	 * the read of a wrong candidate to be an error.</p>
	 *
	 * @param dir    the directory
	 * @param marker a tensor key, or the file name a per-tensor dump would write it to
	 * @return whether the tensor is there
	 */
	private static boolean holds(File dir, String marker) {
		try (StateDictionary shards = new StateDictionary(dir.getPath())) {
			return containsResolved(shards, marker);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Locates a reference directory among the candidates.
	 *
	 * @param candidates candidate directory paths (entries may be {@code null})
	 * @param marker     a tensor that must be present within the directory
	 * @return the references, or {@code null} when no candidate holds the marker
	 */
	public static ReferenceActivations locate(String[] candidates, String marker) {
		File dir = firstExisting(candidates, marker);
		return dir == null ? null : new ReferenceActivations(dir);
	}
}
