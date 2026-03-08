package org.almostrealism.generated;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.stream.IntStream;

public class JavaFileGenerator {
	public static final int start = 3999;
	public static final int count = 1000;

	public static void main(String[] args) {
		StringBuffer template = new StringBuffer();

		try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("src/main/resources/generated.javatemplate")))) {
			String line = null;

			while ((line = in.readLine()) != null) {
				template.append(line);
				template.append("\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		System.out.println("Loaded template");

		IntStream.range(start, start + count).forEach(i -> {
			save("src/main/java/org/almostrealism/generated/GeneratedOperation" + i + ".java",
					template.toString().replaceAll("%KIND%", "Operation").replaceAll("%NUMBER%", String.valueOf(i)));
//			save("src/main/java/org/almostrealism/generated/GeneratedProducer" + i + ".java",
//					template.toString().replaceAll("%KIND%", "Producer").replaceAll("%NUMBER%", String.valueOf(i)));
		});
	}

	public static void save(String location, String output) {
		File file;

		try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(location)))) {
			out.write(output);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
