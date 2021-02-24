package org.almostrealism.swing.displays;

import org.almostrealism.color.RGB;
import org.almostrealism.texture.GraphicsConverter;

import javax.swing.*;
import java.awt.*;

public class ImageDisplay extends JPanel {
	private Image displayableImage;

	public ImageDisplay(RGB image[][]) {
		displayableImage = GraphicsConverter.convertToAWTImage(image);
	}

	@Override
	public void paint(Graphics g) {
		super.paint(g);
		g.drawImage(displayableImage, 0, 0, this);
	}
}
