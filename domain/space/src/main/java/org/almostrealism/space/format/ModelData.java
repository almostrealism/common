/*
 * Copyright 2017 Michael Murray
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

import io.almostrealism.resource.IOStreams;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.io.Console;
import org.almostrealism.io.DecodePostProcessing;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.Scene;

import java.beans.ExceptionListener;
import java.beans.XMLDecoder;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes scene data from a stream in one of the supported encodings, chosen by an integer
 * encoding code.
 *
 * @author  Michael Murray
 */
public class ModelData {
	/** The integer code for a Java Beans XML encoding of a whole {@link Scene}. */
	public static final int XMLEncoding = 2;

	/** The integer code for a {@link RawResource RAW} encoding. */
	public static final int RAWEncoding = 4;

	/** The integer code for a {@link GtsResource GTS} encoding. */
	public static final int GTSEncoding = 8;

	/** The integer code for a {@link PlyResource PLY} encoding. */
	public static final int PLYEncoding = 16;

	/** The integer code for an {@link ObjResource OBJ} encoding. */
	public static final int OBJEncoding = 32;

	/**
	 * Decodes scene data from the given stream, as
	 * {@link #decodeScene(InputStream, int, boolean, ExceptionListener, ShadableSurface)} does
	 * with no initial mesh.
	 *
	 * @param fileIn   the stream to read
	 * @param encoding the encoding code of the data
	 * @param ui       no longer has any effect
	 * @param listener notified of exceptions while decoding XML; may be {@code null}
	 * @return the scene, or {@code null} if the encoding is not supported
	 * @throws IOException if reading or decoding fails
	 */
	public static Scene decodeScene(InputStream fileIn, int encoding,
									boolean ui, ExceptionListener listener) throws IOException {
		return ModelData.decodeScene(fileIn, encoding, ui, listener, null);
	}

	/**
	 * Decodes scene data from the given stream using the given encoding.
	 *
	 * <p>A mesh format (GTS, PLY or OBJ) is returned as a scene holding the single mesh. An XML
	 * scene has {@link DecodePostProcessing#afterDecoding()} called on its camera, surfaces and
	 * lights where they implement it.</p>
	 *
	 * @param fileIn   the stream to read
	 * @param encoding the encoding code of the data
	 * @param ui       no longer has any effect
	 * @param listener notified of exceptions while decoding XML; may be {@code null}
	 * @param s        for GTS, a mesh to add the data to in place of a new one; ignored, with
	 *                 a warning, by the other mesh formats; may be {@code null}
	 * @return the scene, or {@code null} if the encoding is not supported
	 * @throws IOException if reading or decoding fails
	 */
	public static Scene decodeScene(InputStream fileIn, int encoding, boolean ui,
									ExceptionListener listener, ShadableSurface s) throws IOException {
		if (encoding == XMLEncoding) {
			return decodeXmlScene(fileIn, listener);
		} else if (encoding == RAWEncoding) {
			RawResource r = new RawResource();
			r.load(new IOStreams(fileIn));
			return new RawResource.SceneReader().transcode(r).getScene();
		}

		Mesh mesh;

		if (encoding == GTSEncoding) {
			GtsResource r = new GtsResource();
			r.load(new IOStreams(fileIn));

			GtsResource.MeshReader reader = new GtsResource.MeshReader();
			if (s instanceof Mesh) reader.setInitialMesh((Mesh) s);
			mesh = reader.transcode(r).getMesh();
		} else if (encoding == PLYEncoding) {
			PlyResource r = new PlyResource();
			r.load(new IOStreams(fileIn));
			mesh = new PlyResource.MeshReader().transcode(r).getMesh();
		} else if (encoding == OBJEncoding) {
			ObjResource r = new ObjResource();
			r.load(new IOStreams(fileIn));
			mesh = new ObjResource.MeshReader().transcode(r).getMesh();
		} else {
			return null;
		}

		if (s instanceof Mesh && encoding != GTSEncoding) {
			Console.root().warn("An initial mesh is only used for GTS data, not encoding " + encoding);
		}

		return new Scene<>(new ShadableSurface[] { mesh });
	}

	/**
	 * Decodes a Java Beans XML scene and lets its camera, surfaces and lights finish decoding.
	 *
	 * @param fileIn   the stream to read
	 * @param listener notified of exceptions while decoding; may be {@code null}
	 * @return the scene
	 */
	private static Scene decodeXmlScene(InputStream fileIn, ExceptionListener listener) {
		XMLDecoder decoder = new XMLDecoder(fileIn);
		decoder.setExceptionListener(listener);

		Scene<?> scene = (Scene<?>) decoder.readObject();
		afterDecoding(scene.getCamera());

		for (ShadableSurface surface : scene.getSurfaces()) {
			afterDecoding(surface);
		}

		scene.getLights().forEach(ModelData::afterDecoding);
		return scene;
	}

	/**
	 * Calls {@link DecodePostProcessing#afterDecoding()} on a decoded object that implements it.
	 *
	 * @param decoded the decoded object, which may be {@code null}
	 */
	private static void afterDecoding(Object decoded) {
		if (decoded instanceof DecodePostProcessing) {
			((DecodePostProcessing) decoded).afterDecoding();
		}
	}
}
