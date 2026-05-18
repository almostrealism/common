package org.almostrealism.chem;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.physics.BlackBody;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.texture.GraphicsConverter;

import javax.swing.JPanel;
import java.awt.Graphics;

/**
 * A {@link org.almostrealism.physics.BlackBody} that visualizes absorbed photon energies
 * as a spectral line diagram.
 * <p>
 * Incoming photons are mapped to wavelength columns across the visible spectrum (violet
 * to red). Each absorbed photon increments a count in the corresponding column and
 * accumulates color into the image buffer. The diagram can optionally render to a Swing
 * {@link JPanel} at configurable intervals.
 * </p>
 */
public class SpectralLineDiagram extends BlackBody implements ConsoleFeatures {
    /** Per-column absorption counts across the visible spectrum. */
    private long[] absorbed;

    /** The 2D image buffer holding accumulated spectral color data (columns x rows). */
    private RGB[][] image;

	/** When {@code true}, suppresses rendering to the Swing display panel. */
	private boolean noDisplay;

	/** Tick counter used to throttle display updates, and the sleep interval between updates. */
	private int displayTicks, displaySleep = 1000;

	/** The Swing panel used to render the spectral line diagram, lazily initialized. */
	private JPanel display;

	/** Constructs an empty diagram; fields must be initialized before use. */
	public SpectralLineDiagram() { }

	/**
	 * Constructs a diagram with the specified number of wavelength columns and pixel height.
	 *
	 * @param columns  the number of spectral columns (resolution along the wavelength axis)
	 * @param height   the number of pixel rows in the image buffer
	 */
    public SpectralLineDiagram(int columns, int height) {
    	absorbed = new long[columns];
    	this.image = new RGB[columns][height];
    }

	public long[] getAbsorbed() { return absorbed; }
	public void setAbsorbed(long[] absorbed) { this.absorbed = absorbed; }
	public RGB[][] getImageData() { return this.image; }
	public void setImageData(RGB[][] image) { this.image = image; }
	public boolean isNoDisplay() { return noDisplay; }
	public void setNoDisplay(boolean noDisplay) { this.noDisplay = noDisplay; }
	public int getDisplayTicks() { return displayTicks; }
	public void setDisplayTicks(int displayTicks) { this.displayTicks = displayTicks; }
	public int getDisplaySleep() { return displaySleep; }
	public void setDisplaySleep(int displaySleep) { this.displaySleep = displaySleep; }
	public void setDisplay(JPanel display) { this.display = display; }

	@Override
    public boolean absorb(Vector x, Vector p, double energy) {
        boolean absorb = super.absorb(x, p, energy);
        if (!absorb) return false;

        Photon ph = Photon.withEnergy(energy);
        double position = (ph.getWavelength() - violet) / (red - violet);
        if (position > 1.0 || position < 0.0) return false;
        int l = (int) (position * (absorbed.length - 1));
        absorbed[l]++;

		double n = 1000 * PhysicalConstants.HC / energy;

		if (absorbed[l] < 1000) {
			for (int j = 0; j < this.image[l].length; j++) {
				if (this.image[l][j] == null)
					this.image[l][j] = new RGB(n);
				else
					this.image[l][j].addTo(new RGB(n).divide(1000));
			}
		}

		// TODO  this should be abstracted out, since it appears in
		//       this class and absorption plane
		if (!noDisplay && displayTicks % displaySleep == 0) {
			if (this.display != null && this.display.getGraphics() != null) {
				Graphics g = this.display.getGraphics();
				this.drawImage(g);
				displayTicks = 1;
			}
		} else {
			displayTicks++;
		}

        return true;
    }

	/**
	 * Returns the accumulated spectral image buffer.
	 * <p>
	 * If no image data has been set, returns a 1x0 empty array.
	 * </p>
	 *
	 * @return the image buffer as a 2D array of {@link RGB} values (columns x rows)
	 */
	public RGB[][] getImage() {
		if (this.image == null) return new RGB[1][0];
		return this.image;
	}

	/**
	 * Renders the current spectral image to the given graphics context.
	 *
	 * @param g  the AWT {@link Graphics} context to draw into
	 */
	public void drawImage(Graphics g) {
		log("drawImage");
		g.drawImage(GraphicsConverter.convertToAWTImage(getImage()), 0, 0, display);
	}

	/** Enables rendering to the Swing display panel. */
	public void enableDisplay() { this.noDisplay = false; }

	/** Disables rendering to the Swing display panel. */
	public void disableDisplay() { this.noDisplay = true; }

	/**
	 * Returns the Swing display panel, creating it lazily if it does not yet exist.
	 * <p>
	 * The panel's {@code paint} method delegates to {@link #drawImage(Graphics)}.
	 * </p>
	 *
	 * @return the Swing panel used to display the spectral diagram
	 */
	public JPanel getDisplay() {
		if (this.display != null) return this.display;

		this.display = new JPanel() {
			@Override
			public void paint(Graphics g) {
				SpectralLineDiagram.this.drawImage(g);
			}
		};

		return this.display;
	}
}
