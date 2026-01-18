package org.almostrealism.chem;

import org.almostrealism.electrostatic.Photon;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.physics.BlackBody;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.texture.GraphicsConverter;

import javax.swing.JPanel;
import java.awt.Graphics;

public class SpectralLineDiagram extends BlackBody {
    private long[] absorbed;
    private RGB[][] image;

	private boolean noDisplay;
	private int displayTicks, displaySleep = 1000;
	private JPanel display;

	public SpectralLineDiagram() { }

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

	public RGB[][] getImage() {
		if (this.image == null) return new RGB[1][0];
		return this.image;
	}

	public void drawImage(Graphics g) {
    	System.out.println("SpectralLineDiagram.drawImage");
		g.drawImage(GraphicsConverter.convertToAWTImage(getImage()), 0, 0, display);
	}

	public void enableDisplay() { this.noDisplay = false; }
	public void disableDisplay() { this.noDisplay = true; }

	public JPanel getDisplay() {
		if (this.display != null) return this.display;

		this.display = new JPanel() {
			public void paint(Graphics g) {
				SpectralLineDiagram.this.drawImage(g);
			}
		};

		return this.display;
	}
}
