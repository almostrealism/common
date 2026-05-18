package org.almostrealism.texture;

import org.almostrealism.color.RealizableImage;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/**
 * An abstract animation sequence that renders a series of frames by advancing through named images.
 *
 * <p>Subclasses implement {@link #next()} to provide the output filename for each frame.
 * The animation iterates indefinitely — {@link Iterator#hasNext()} always returns {@code true}.</p>
 *
 * <p>The {@link #render()} method spawns a background thread that writes each frame as a
 * JPEG file using {@link ImageCanvas#JPEGEncoding}.</p>
 *
 * @see Layered
 * @see org.almostrealism.color.RealizableImage
 * @author Michael Murray
 */
public abstract class Animation implements Layered<RealizableImage>, ConsoleFeatures {
	/** The current frame image returned by the iterator. */
	private RealizableImage image;

	/** The filename for the current frame, updated by each call to {@link #next()}. */
	private String name;

	/**
	 * Constructs an {@link Animation} with no initial frame image.
	 */
	public Animation() { }

	/**
	 * Constructs an {@link Animation} with the specified initial frame image.
	 *
	 * @param image the {@link org.almostrealism.color.RealizableImage} to render each frame
	 */
	public Animation(RealizableImage image) {
		this.image = image;
	}

	/**
	 * Sets the frame image used during rendering.
	 *
	 * @param image the new frame image
	 */
	public void setImage(RealizableImage image) { this.image = image; }

	/**
	 * Returns an iterator that advances the animation indefinitely.
	 *
	 * <p>Each call to {@link Iterator#next()} invokes {@link #next()} to advance
	 * the frame name and returns the current frame image.</p>
	 *
	 * @return an infinite iterator over the animation frames
	 */
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
	
	/**
	 * Starts a background thread that renders each animation frame to a JPEG file.
	 *
	 * <p>The thread iterates over this animation, evaluating each frame and writing it
	 * to a JPEG file named by the most recent call to {@link #next()}.</p>
	 *
	 * @return the background rendering thread (not yet started)
	 */
	public Thread render() {
		return new Thread(() -> {
			for (RealizableImage r : Animation.this) {
				try {
					ImageCanvas.encodeImageFile(r.get(), new File(name), ImageCanvas.JPEGEncoding);
				} catch (IOException e) {
					warn(e.getMessage(), e);
				}
			}
		});
	}
	
	/**
	 * Advances the animation to the next frame and returns the output filename for that frame.
	 *
	 * @return the filename to which the next frame should be written
	 */
	public abstract String next();
}
