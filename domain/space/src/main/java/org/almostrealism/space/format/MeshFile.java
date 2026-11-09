/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.space.format;

import io.almostrealism.code.Operator;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshSource;
import org.almostrealism.space.ShadableSurfaceWrapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * A surface whose {@link Mesh} is stored in a file, or at a URL, and loaded the first time
 * the surface is used. Every surface operation is delegated to the loaded mesh.
 *
 * <p>The file is named by {@link #setFile(String)}; if {@link #setURL(String)} is set, the
 * file name is appended to it and the mesh is read from there instead. Its encoding is one of
 * the {@link ModelData} codes.</p>
 */
public class MeshFile implements MeshSource, ShadableSurfaceWrapper, ShadableSurface, ConsoleFeatures {
	/** The name of the file holding the mesh. */
	private String name;

	/** The {@link ModelData} encoding code of the file. */
	private int format;

	/** The URL the file name is appended to, or {@code null} to read a local file. */
	private String url;

	/** The loaded mesh, or {@code null} until it is loaded. */
	private Mesh mesh;

	/** For GTS, a mesh to add the data to in place of a new one, or {@code null}. */
	private ShadableSurface s;

	/**
	 * Sets the name of the file holding the mesh.
	 *
	 * @param f the file name
	 */
	public void setFile(String f) { this.name = f; }

	/**
	 * Returns the name of the file holding the mesh.
	 *
	 * @return the file name
	 */
	public String getFile() { return this.name; }

	/**
	 * Sets the {@link ModelData} encoding code of the file.
	 *
	 * @param f the encoding code
	 */
	public void setFormat(int f) { this.format = f; }

	/**
	 * Returns the {@link ModelData} encoding code of the file.
	 *
	 * @return the encoding code
	 */
	public int getFormat() { return this.format; }

	/**
	 * Sets the URL the file name is appended to.
	 *
	 * @param url the base URL, or {@code null} to read a local file
	 */
	public void setURL(String url) { this.url = url; }

	/**
	 * Returns the URL the file name is appended to.
	 *
	 * @return the base URL, or {@code null} if a local file is read
	 */
	public String getURL() { return this.url; }

	/**
	 * Sets a mesh that GTS data is added to, in place of a new one.
	 *
	 * @param s the mesh, or {@code null}
	 */
	public void setSurface(ShadableSurface s) { this.s = s; }

	/**
	 * Returns the mesh, loading it the first time.
	 *
	 * @return the mesh, or {@code null}, with a warning, if it cannot be loaded
	 */
	@Override
	public ShadableSurface getSurface() {
		if (this.mesh == null) {
			try {
				if (this.url != null) {
					try (InputStream in = new URL(this.url + this.name).openStream()) {
						this.mesh = (Mesh) ModelData.decodeScene(in, this.format, false, null, this.s)
								.getSurfaces()[0];
					}
				} else {
					this.mesh = (Mesh) FileDecoder.decodeSurfaceFile(
							new File(this.name), this.format, false, null, this.s);
				}
			} catch (IOException ioe) {
				warn("Unable to load mesh data from " + this.name, ioe);
			}

			if (this.mesh == null) {
				warn("No mesh was loaded from " + this.name);
				return null;
			}

			this.mesh.setMeshSource(this);
			this.mesh.loadTree();
		}

		return this.mesh;
	}

	@Override public boolean getShadeFront() { return this.getSurface().getShadeFront(); }
	@Override public boolean getShadeBack() { return this.getSurface().getShadeBack(); }
	@Override public Producer<PackedCollection> getValueAt(Producer<PackedCollection> point) { return this.getSurface().getValueAt(point); }

	@Override
	public BoundingSolid calculateBoundingSolid() { return getSurface().calculateBoundingSolid(); }

	@Override public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> point) { return this.getSurface().getNormalAt(point); }
	@Override public ContinuousField intersectAt(Producer<?> ray) { return this.getSurface().intersectAt(ray); }

	@Override public Operator<PackedCollection> get() { return getSurface().get(); }

	@Override
	public Operator<PackedCollection> expect() { return getSurface().expect(); }

	@Override public Producer<PackedCollection> shade(ShaderContext p) { return this.getSurface().shade(p); }
}
