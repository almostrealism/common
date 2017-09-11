package org.almostrealism.texture;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import org.almostrealism.color.RealizableImage;

public abstract class Animation implements Layered<RealizableImage> {
	private RealizableImage image;
	private String name;

	public Animation() { }
	
	public Animation(RealizableImage image) {
		this.image = image;
	}
	
	public void setImage(RealizableImage image) { this.image = image; }
	
	@Override
	public Iterator<RealizableImage> iterator() {
		return new Iterator<RealizableImage>() {

			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public RealizableImage next() {
				name = Animation.this.next();
				return image;
			}
			
		};
	}
	
	public Thread render() {
		return new Thread(() -> {
			for (RealizableImage r : Animation.this) {
				try {
					ImageCanvas.encodeImageFile(r.evaluate(null), new File(name), ImageCanvas.JPEGEncoding);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}
	
	public abstract String next();
}
