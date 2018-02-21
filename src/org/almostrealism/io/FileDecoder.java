/*
 * Copyright 2017 Michael Murray
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

package org.almostrealism.io;

import java.beans.ExceptionListener;
import java.beans.XMLDecoder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.almostrealism.color.RGB;
import org.almostrealism.space.Scene;
import org.almostrealism.space.ShadableSurface;
import org.almostrealism.space.SurfaceGroup;

/**
 * The FileDecoder class provides static methods for decoding scene and surface data
 * that has been stored in a file.
 */
public class FileDecoder extends SpatialData {
  /** The integer code for an XML encoding. */
  public static final int XMLEncoding = 2;
  
  /** The integer code for a RAW encoding. */
  public static final int RAWEncoding = 4;

	/**
	 * Decodes the surface data stored in the file represented by the specified File object
	 * using the encoding specified by the integer encoding code and returns the new Surface object.
	 * If ui is true a SurfaceUI object will be returned. The specified ExceptionListener is notified
	 * if an exception occurs when using an XMLDecoder.
	 * The decodeSurfaceFile method returns null if the encoding is not supported.
	 * 
	 * @throws IOException  If an IO error occurs.
	 * @throws FileNotFoundException  If the file is not found.
	 */
	public static ShadableSurface decodeSurfaceFile(File file, int encoding, boolean ui, ExceptionListener listener, ShadableSurface s) throws IOException {
		try (FileInputStream fileIn = new FileInputStream(file)) {
			if (encoding == FileDecoder.XMLEncoding) {
				XMLDecoder decoder = new XMLDecoder(fileIn);
				decoder.setExceptionListener(listener);

				return ((ShadableSurface) decoder.readObject());
			} else if (encoding == FileDecoder.RAWEncoding) {
				Scene scene = SpatialData.decodeScene(fileIn, FileDecoder.RAWEncoding, ui, listener, s);

				ShadableSurface group = new SurfaceGroup(scene.getSurfaces());

				if (ui == true) {
					System.out.println("FileDecoder: UI mode no longer supported.");
//				group = SurfaceUIFactory.createSurfaceUI((AbstractSurface)group);
//				((AbstractSurfaceUI)group).setName("Surface (Triangles)");
				}

				return group;
			} else if (encoding == SpatialData.GTSEncoding ||
					encoding == SpatialData.PLYEncoding ||
					encoding == SpatialData.OBJEncoding) {
				Scene<ShadableSurface> scene = SpatialData.decodeScene(fileIn, encoding, ui, listener, s);
				return scene.get(0);
			} else {
				return null;
			}
		}
	}
	
	/**
	 * Parses a series of double values separated by spaces and returnes an array containing the values.
	 * 
	 * @throws NumberFormatException  If a number is not correctly formatted.
	 * 
	 * TODO  Move this method to the graph.io package
	 */
	public static double[] parseDoubles(String s) {
		java.util.Vector values = new java.util.Vector();
		
		s = s.concat(" ");
		int index = s.indexOf(" ");
		
		w: while (index >= 0) {
			String t = s.substring(0, index);
			t = t.trim();
			
			if (t.equals("")) break w;
			
			values.addElement(new Double(t));
			
			s = s.substring(index + 1);
			index = s.indexOf(" ");
		}
		
		double d[] = new double[values.size()];
		for (int i = 0; i < d.length; i++) d[i] = ((Double)values.elementAt(i)).doubleValue();
		
		return d;
	}
	
	public static RGB[][] readRGBList(InputStream in, RGB buf[][]) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		
		String line = null;
		int i = 0, j = 0;
		
		w: while ((line = reader.readLine()) != null) {
			if (j >= buf[i].length) { i++; j = 0; }
			if (i >= buf.length) break w;
			
			String s[] = line.split(" ");
			
			double r = Double.parseDouble(s[0]);
			double g = Double.parseDouble(s[1]);
			double b = Double.parseDouble(s[2]);
			
			buf[i][j] = new RGB(r, g, b);
		}
		
		return buf;
	}
}
