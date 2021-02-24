package org.almostrealism.space;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shader;
import io.almostrealism.code.UnicodeResource;
import io.almostrealism.code.ResourceTranscoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class RawResource extends UnicodeResource {
	public static class SceneTranscoder implements ResourceTranscoder<SceneResource<Triangle>, RawResource> {
		@Override
		public RawResource transcode(SceneResource<Triangle> r) {
			return null;
		}
	}

	public static class SceneReader implements ResourceTranscoder<RawResource, SceneResource<Triangle>> {
		@Override
		public SceneResource<Triangle> transcode(RawResource r) throws IOException {
			Scene<Triangle> scene = new Scene<>();

			BufferedReader in = new BufferedReader(new InputStreamReader(r.getInputStream()));

			String line = in.readLine();
			int lineCount = 0;

			t: while (line != null) {
				if (line.startsWith("#") == true) {
					line = in.readLine();
					lineCount++;
				} else {
					break t;
				}
			}

			boolean polyData = false;
			int pointCount = 0, polyCount = 0;

			if (line.indexOf(" ", line.indexOf(" ") + 1) < 0) {
				polyData = true;

				pointCount = Integer.parseInt(line.substring(0, line.indexOf(" ")));
				polyCount = Integer.parseInt(line.substring(line.indexOf(" ") + 1));

				line = in.readLine();
				lineCount++;
			}

			l: while (line != null) {
				int tCount = 0;

				if (line.startsWith("#") == true) {
					line = in.readLine();
					lineCount++;

					continue l;
				}

				if (polyData == true) {
					Vector points[] = new Vector[pointCount];

					for (int p = 0; p < points.length; p++) {
						t: while (true) {
							if (line.startsWith("#") == true) {
								line = in.readLine();
								lineCount++;
							} else {
								break t;
							}
						}

						double data[] = new double[3];

						boolean broken = false;

						i: for (int i = 0; i < data.length; i++) {
							int index = line.indexOf(" ");

							if (i == data.length - 1) {
								data[i] = Double.parseDouble(line);

								break i;
							}

							if (index > 0) {
								data[i] = Double.parseDouble(line.substring(0, index));
								line = line.substring(index + 1);
							} else {
								System.out.println("Line " + lineCount + " of RAW file does not contain the proper number of terms, " +
										"or is not properly delimited");

								broken = true;
								break i;
							}
						}

						if (broken == false) {
							points[p] = new Vector(data[0], data[1], data[2]);
						}

						line = in.readLine();
						lineCount++;
					}

					for (int p = 0; p < polyCount; p++) {
						t: while (true) {
							if (line.startsWith("#") == true) {
								line = in.readLine();
								lineCount++;
							} else {
								break t;
							}
						}

						int data[] = new int[3];

						boolean broken = false;

						i: for (int i = 0; i < data.length; i++) {
							int index = line.indexOf(" ");

							if (i == data.length - 1) {
								data[i] = Integer.parseInt(line.trim());

								break i;
							}

							if (index > 0) {
								data[i] = Integer.parseInt(line.substring(0, index).trim());
								line = line.substring(index + 1);
							} else {
								System.out.println("Line " + lineCount + " of RAW file does not contain the proper number of terms, " +
										"or is not properly delimited");

								broken = true;
								break i;
							}
						}

						if (broken == false) {
							Triangle newSurface = new Triangle(points[data[0]], points[data[1]], points[data[2]]);
							newSurface.setColor(new RGB(1.0, 1.0, 1.0));
							newSurface.setShaders(new Shader[0]);
							scene.add(newSurface);
						}

						line = in.readLine();
						lineCount++;
					}

					break l;
				} else {
					try {
						double data[] = new double[9];

						boolean broken = false;

						i: for (int i = 0; i < data.length; i++) {
							int index = line.indexOf(" ");

							if (i == data.length - 1) {
								data[i] = Double.parseDouble(line);

								break i;
							}

							if (index > 0) {
								data[i] = Double.parseDouble(line.substring(0, index));
								line = line.substring(index + 1);
							} else {
								System.out.println("Line " + lineCount + " of RAW file does not contain the proper number of terms, " +
										"or is not properly delimited");

								broken = true;
								break i;
							}
						}

						if (broken == false) {
							Vector p1 = new Vector(data[0], data[1], data[2]);
							Vector p2 = new Vector(data[3], data[4], data[5]);
							Vector p3 = new Vector(data[6], data[7], data[8]);

							Triangle newSurface = new Triangle(p1, p2, p3);
							newSurface.setColor(new RGB(1.0, 1.0, 1.0));
							newSurface.setShaders(new Shader[0]);

							scene.add(newSurface);
							tCount++;
						}
					} catch (NumberFormatException nfe) {
						System.out.println("Line " + lineCount + " of RAW file contains nonnumerical data");
					}
				}

				line = in.readLine();
				lineCount++;
			}

			return new SceneResource<>(scene);
		}
	}
}
