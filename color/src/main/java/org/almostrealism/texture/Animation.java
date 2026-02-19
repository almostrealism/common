package org.almostrealism.texture;

import org.almostrealism.color.RealizableImage;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/** The Animation class. */
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
	
	/** Performs the render operation. */
	public Thread render() {
		return new Thread(() -> {
			for (RealizableImage r : Animation.this) {
				try {
					ImageCanvas.encodeImageFile(r.get(), new File(name), ImageCanvas.JPEGEncoding);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}
	
	/** Performs the next operation. */
	public abstract String next();
}
