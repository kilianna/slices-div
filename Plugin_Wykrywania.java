import ij.*;
import ij.gui.*;
import ij.plugin.*;
import ij.process.*;

import java.awt.*;
import java.util.*;

import javax.swing.SwingUtilities;

public class Plugin_Wykrywania implements PlugIn, RoiListener, DialogListener {

	// GUI
	private String[] params;
	private static String lastParams;
	private ImagePlus sourceImage;
	private ImagePlus previewImage;
	private ImagePlus pointsImage;
	private ImagePlus noiseImage;
	private NonBlockingGenericDialog dialog;
	private ByteProcessor previewProcessor;
	private Timer timer;
	private TimerTask updateNoiseTask;
	private TimerTask updatePointsTask;

	// Plot
	private Plot plot;
	private Roi ignoreRoi;
	private double[] oldPointsX;
	private double[] oldPointsY;

	// Parameters
	private int windowRadius;
	private int pointRadius;
	private float limitLineA;
	private float limitLineB;

	// Image
	private int width;
	private int height;
	private float[] pixels;

	// Window
	private int windowSize;
	private int[] indexUp;
	private int[] indexDown;
	private float[] weightUp;
	private float[] weightDown;

	// Histograms
	private int histSize;
	private float[] hist;

	@Override
	public void run(String arg) {
		logMethod();
		sourceImage = IJ.getImage();
		if (sourceImage == null) {
			IJ.error("Wybierz obrazek");
		}
		if (lastParams == null) {
			lastParams = "20; 5; 1; 0";
		}
		showDialog(false);
		if (getCheckbox(MANUAL_MODE_CHECK_BOX) && !dialog.wasCanceled()) {
			manualProcess();
		}
		if (!dialog.wasCanceled()) {
			imageProcess();
			lastParams = params[0];
		}
		closed();
	}

	private void imageProcess() {
		logMethod();
		double[] p = parseParams();
		windowRadius = (int) (p[0] + 0.5);
		pointRadius = (int) (p[1] + 0.5);
		limitLineA = (float) p[2];
		limitLineB = (float) p[3];
		makeWindow();
		boolean allSlices = getCheckbox(ALL_SLICES_CHECK_BOX);
		boolean cutLower = getCheckbox(CUT_LOWER_CHECK_BOX);
		boolean cutHigher = getCheckbox(CUT_HIGHER_CHECK_BOX);
		ImageStack stack = sourceImage.getStack();
		if (allSlices && stack != null && stack.size() > 1) {
			ImageStack is = new ImageStack(sourceImage.getWidth(), sourceImage.getHeight());
			for (int i = 1; i <= stack.size(); i++) {
				ImageProcessor ip = stack.getProcessor(i);
				ImageProcessor r = processSingleImage(ip, cutLower, cutHigher, i - 1, stack.size());
				is.addSlice(r);
			}
			ImagePlus outputImage = new ImagePlus("Wykryte Punkty", is);
			outputImage.show();
		} else {
			ImageProcessor r = processSingleImage(sourceImage.getProcessor(), cutLower, cutHigher, 0, 1);
			ImagePlus outputImage = new ImagePlus("Wykryte Punkty", r);
			outputImage.show();
		}
	}

	private ImageProcessor processSingleImage(ImageProcessor ip, boolean cutLower, boolean cutHigher, int index,
			int total) {
		ImageProcessor src = ip.convertToFloat();
		ImageProcessor dst = ip.convertToByte(true);
		if (ip == dst)
			dst = dst.duplicate();
		pixels = (float[]) src.getPixels();
		width = src.getWidth();
		height = src.getHeight();
		makeHist(index, total);
		byte[] outputPixels = (byte[]) dst.getPixels();
		float[] results = new float[outputPixels.length];
		float minResult = Float.POSITIVE_INFINITY;
		float maxResult = Float.NEGATIVE_INFINITY;
		int half = (histSize + 1) / 2;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int histOffset = histSize * (x + y * width);
				float firstValue = 0;
				for (int k = 0; k < pointRadius; k++) {
					firstValue += hist[histOffset + k];
				}
				firstValue /= (float) pointRadius;
				float lastValue = 0;
				for (int k = half; k < histSize; k++) {
					lastValue += hist[histOffset + k];
				}
				lastValue /= (float) (histSize - half);
				float yy = firstValue;
				float xx = lastValue;
				float res = yy - (limitLineA * xx + limitLineB);
				if (res > maxResult)
					maxResult = res;
				if (res < minResult)
					minResult = res;
				results[x + y * width] = res;
			}
		}
		if (cutLower && cutHigher) {
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					outputPixels[x + y * width] = results[x + y * width] < 0 ? (byte) 0
							: (byte) 255;
		} else if (cutLower && !cutHigher) {
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					outputPixels[x + y * width] = results[x + y * width] < 0 ? (byte) 0
							: (byte) (255.0f * (results[x + y * width] / maxResult));
		} else if (!cutLower && cutHigher) {
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					outputPixels[x + y * width] = results[x + y * width] < 0
							? (byte) (255.0f * (1.0f - results[x + y * width] / minResult))
							: (byte) 255;
		} else {
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					outputPixels[x + y * width] = results[x + y * width] < 0
							? (byte) (127.0f * (1.0f - results[x + y * width] / minResult))
							: (byte) (128.0f + 127.0f
									* (results[x + y * width] / maxResult));
		}
		return dst;
	}

	private void manualProcess() {
		logMethod();

		// Read image
		ImageProcessor ip = sourceImage.getProcessor();
		ImageProcessor fp = ip.convertToFloat();
		if (ip == fp) {
			fp = fp.duplicate();
		}
		pixels = (float[]) fp.getPixels();
		width = fp.getWidth();
		height = fp.getHeight();

		// Create helper image windows
		pointsImage = new ImagePlus("Punkty", fp.duplicate());
		pointsImage.show();
		noiseImage = new ImagePlus("Szum", fp.duplicate());
		noiseImage.show();

		// Create preview image
		ImageStack is = new ImageStack(width, height);
		previewProcessor = (ByteProcessor) fp.convertToByte(true);
		is.addSlice(previewProcessor);
		is.addSlice(previewProcessor.duplicate());
		previewImage = new ImagePlus("Podglad", is);
		previewImage.show();

		// Create plot
		plot = new Plot("Wykres", "Otoczenie", "Srodek");
		plot.setColor(Color.BLUE);
		plot.add("circle", new double[0], new double[0]);
		plot.setColor(Color.RED);
		plot.add("circle", new double[0], new double[0]);
		plot.show();

		// Listen for ROI changes
		Roi.addRoiListener(this);

		do {
			double[] p = parseParams();
			windowRadius = (int) (p[0] + 0.5);
			pointRadius = (int) (p[1] + 0.5);
			limitLineA = (float) p[2];
			limitLineB = (float) p[3];
			makeWindow();
			makeHist(0, 1);
			updatePoints(true);
			updateNoise();
			updatePreview();
			showDialog(true);
		} while (getCheckbox(MANUAL_MODE_CHECK_BOX) && !dialog.wasCanceled());
	}

	static final int ALL_SLICES_CHECK_BOX = 0;
	static final int CUT_LOWER_CHECK_BOX = 1;
	static final int CUT_HIGHER_CHECK_BOX = 2;
	static final int MANUAL_MODE_CHECK_BOX = 3;

	private boolean getCheckbox(int id) {
		return ((Checkbox) dialog.getCheckboxes().get(id)).getState();
	}

	private void showDialog(boolean manual) {
		logMethod();
		if (lastParams == null) {
			lastParams = "20; 5; 1; 0";
		}
		boolean[] initCheckBox = new boolean[] { false, true, true, false };
		if (dialog != null) {
			initCheckBox[0] = ((Checkbox) dialog.getCheckboxes().get(0)).getState();
			initCheckBox[1] = ((Checkbox) dialog.getCheckboxes().get(1)).getState();
			initCheckBox[2] = ((Checkbox) dialog.getCheckboxes().get(2)).getState();
			initCheckBox[3] = ((Checkbox) dialog.getCheckboxes().get(3)).getState();
			dialog.dispose();
		}
		dialog = new NonBlockingGenericDialog("Parametry");
		String initialValue;
		if (params == null) {
			params = new String[] { "[!]", "", "", "", "" };
			initialValue = lastParams;
		} else {
			initialValue = params[0];
		}
		dialog.addStringField("Parametry (W, R, A, B)", initialValue, 30);
		dialog.addStringField("Promień okna (W)" + (manual ? " *" : ""), params[1], 10);
		dialog.addStringField("Promień punktu (R)", params[2], 10);
		dialog.addStringField("Wsp. kierunkowy (A)", params[3], 10);
		dialog.addStringField("Wyr. wolny (B)", params[4], 10);
		dialog.addCheckbox("Wszystkie warstwy", initCheckBox[0]);
		dialog.addCheckbox("Przytnij wartości tła", initCheckBox[1]);
		dialog.addCheckbox("Przytnij wartości punktów", initCheckBox[2]);
		dialog.addCheckbox("Tryb ręczny" + (manual ? " - odznacz, aby zakończyć tryb ręczny" : ""),
				initCheckBox[3]);
		if (manual) {
			dialog.addMessage("* - zmiana wymaga wciśnięcia 'OK'.");
		}
		dialog.addHelp(helpText);
		updateDialog();
		dialog.addDialogListener(this);
		dialog.showDialog();
		Vector<TextField> vect = dialog.getStringFields();
		params[0] = vect.get(0).getText();
		params[1] = vect.get(1).getText();
		params[2] = vect.get(2).getText();
		params[3] = vect.get(3).getText();
		params[4] = vect.get(4).getText();
	}

	private void closed() {
		logMethod();
		Roi.removeRoiListener(this);
		if (previewImage != null)
			previewImage.getWindow().dispose();
		if (pointsImage != null)
			pointsImage.getWindow().dispose();
		if (noiseImage != null)
			noiseImage.getWindow().dispose();
		if (plot != null)
			plot.getImagePlus().getWindow().dispose();
		if (dialog != null)
			dialog.dispose();
		if (timer != null)
			timer.cancel();
		previewImage = null;
		pointsImage = null;
		noiseImage = null;
		plot = null;
		dialog = null;
		timer = null;
		pixels = null;
		indexUp = null;
		indexDown = null;
		weightUp = null;
		weightDown = null;
		hist = null;
		params = null;
		System.gc();
	}

	private void makeHist(int index, int total) {
		logMethod();
		histSize = windowRadius + 1;
		if (hist == null || hist.length != width * height * histSize) {
			hist = new float[width * height * histSize];
		}
		for (int centerY = 0; centerY < height; centerY++) {
			if (centerY % 10 == 9) {
				IJ.showProgress(index * height + centerY, total * height);
			}
			for (int centerX = 0; centerX < width; centerX++) {
				int startX = centerX - windowRadius;
				int startY = centerY - windowRadius;
				int offset = (centerX + centerY * width) * histSize;
				Arrays.fill(hist, offset, offset + histSize, 0.0f);
				if (startX < 0 || startY < 0 || startX + windowSize > width
						|| startY + windowSize > height) {
					continue;
				}
				for (int x = 0; x < windowSize; x++) {
					for (int y = 0; y < windowSize; y++) {
						hist[offset + indexUp[x + y * windowSize]] += weightUp[x
								+ y * windowSize]
								* pixels[startX + x + (startY + y) * width];
						hist[offset + indexDown[x + y * windowSize]] += weightDown[x
								+ y * windowSize]
								* pixels[startX + x + (startY + y) * width];
					}
				}
			}
		}
		IJ.showProgress(index * height, total * height);
	}

	private void makeWindow() {
		logMethod();
		windowSize = 2 * windowRadius + 1;
		indexUp = new int[windowSize * windowSize];
		indexDown = new int[windowSize * windowSize];
		weightUp = new float[windowSize * windowSize];
		weightDown = new float[windowSize * windowSize];
		float[] radiusWeight = new float[windowSize];

		for (int x = 0; x < windowSize; x++) {
			for (int y = 0; y < windowSize; y++) {
				int dx = x - windowRadius;
				int dy = y - windowRadius;
				float d = (float) Math.sqrt((float) (dx * dx + dy * dy)) - 1.0f;
				if (d < 0.0f)
					d = 0.0f;
				if (d >= (float) windowRadius - 0.05f)
					continue;
				int up = (int) Math.ceil(d);
				int down = (int) d;
				indexUp[x + y * windowSize] = up;
				indexDown[x + y * windowSize] = down;
				if (up == down) {
					weightUp[x + y * windowSize] = 1.0f;
					radiusWeight[up] += 1.0f;
				} else {
					weightUp[x + y * windowSize] = d - (float) down;
					weightDown[x + y * windowSize] = (float) up - d;
					radiusWeight[up] += d - (float) down;
					radiusWeight[down] += (float) up - d;
				}
			}
		}
		for (int x = 0; x < windowSize; x++) {
			for (int y = 0; y < windowSize; y++) {
				weightUp[x + y * windowSize] /= radiusWeight[indexUp[x + y * windowSize]];
				weightDown[x + y * windowSize] /= radiusWeight[indexDown[x + y * windowSize]];
			}
		}
	}

	@Override
	public void roiModified(ImagePlus imp, int id) {
		if (imp == null)
			return;
		logMethod();
		if (imp == pointsImage) {
			if (updatePointsTask != null)
				updatePointsTask.cancel();
			updatePointsTask = invokeLater(new Runnable() {
				public void run() {
					updatePoints(false);
				}
			}, 500, 500);
			updatePoints(true);
		} else if (imp == noiseImage) {
			if (updateNoiseTask != null)
				updateNoiseTask.cancel();
			updateNoiseTask = invokeLater(new Runnable() {
				public void run() {
					updateNoise();
				}
			}, 100, -1);
		} else {
			ImagePlus plotImage = plot.getImagePlus();
			if (imp == plotImage && id != RoiListener.DELETED) {
				updateLimit();
			}
		}
	}

	private TimerTask invokeLater(Runnable doRun, int ms, int period) {
		TimerTask tt = new TimerTask() {
			public void run() {
				SwingUtilities.invokeLater(doRun);
			}
		};
		if (timer == null)
			timer = new Timer();
		if (period < 0) {
			timer.schedule(tt, ms);
		} else {
			timer.schedule(tt, ms, period);
		}
		return tt;
	}

	private void updateLimit() {
		ImagePlus plotImage = plot.getImagePlus();
		Roi roi = plotImage.getRoi();
		if (roi == null || !(roi instanceof Line) || roi == ignoreRoi) {
			ignoreRoi = null;
			return;
		}
		logMethod();
		ignoreRoi = null;
		Line line = (Line) roi;
		Polygon poly = line.getPoints();
		assert poly.npoints == 2;
		float xa = (float) plot.descaleX(poly.xpoints[0]);
		float ya = (float) plot.descaleY(poly.ypoints[0]);
		float xb = (float) plot.descaleX(poly.xpoints[1]);
		float yb = (float) plot.descaleY(poly.ypoints[1]);
		limitLineA = (ya - yb) / (xa - xb);
		limitLineB = ya - (ya - yb) / (xa - xb) * xa;
		Vector<TextField> vect = dialog.getStringFields();
		params[3] = toShortNumber(limitLineA);
		params[4] = toShortNumber(limitLineB);
		params[0] = joinParams();
		vect.get(0).setText(params[0]);
		vect.get(3).setText(params[3]);
		vect.get(4).setText(params[4]);
		updatePreview();
	}

	private void updateLimitLine() {
		if (plot == null)
			return;
		logMethod();
		ImagePlus plotImage = plot.getImagePlus();
		Rectangle rect = plot.getDrawingFrame();
		float rx1 = (float) plot.descaleX(rect.x + 4);
		float ry1 = (float) plot.descaleY(rect.y + 4);
		float rx2 = (float) plot.descaleX(rect.x + rect.width - 3);
		float ry2 = (float) plot.descaleY(rect.y + rect.height - 3);
		float x1 = rx1;
		float y1 = limitLineA * x1 + limitLineB;
		float x2 = rx2;
		float y2 = limitLineA * x2 + limitLineB;
		if (y1 > ry1) {
			y1 = ry1;
			x1 = (y1 - limitLineB) / limitLineA;
		} else if (y1 < ry2) {
			y1 = ry2;
			x1 = (y1 - limitLineB) / limitLineA;
		}
		if (y2 > ry1) {
			y2 = ry1;
			x2 = (y2 - limitLineB) / limitLineA;
		} else if (y2 < ry2) {
			y2 = ry2;
			x2 = (y2 - limitLineB) / limitLineA;
		}
		if (x1 < x2) {
			double px1 = plot.scaleXtoPxl(x1);
			double py1 = plot.scaleYtoPxl(y1);
			double px2 = plot.scaleXtoPxl(x2);
			double py2 = plot.scaleYtoPxl(y2);
			ignoreRoi = Line.create(px1, py1, px2, py2);
		} else {
			ignoreRoi = new Roi(0, 0, 0, 0);
		}
		plotImage.setRoi(ignoreRoi);
	}

	private static String toShortNumber(double number) {
		int num = (int) Math.ceil(Math.log10(Math.abs(number * 1.000001)));
		if (num > 5)
			num = 5;
		if (num < -10)
			return String.format("%e", number);
		num = 5 - num;
		String res = String.format("%.0" + num + "f", number);
		if (res.contains(".")) {
			while (res.endsWith("0"))
				res = res.substring(0, res.length() - 1);
			if (res.endsWith("."))
				res = res.substring(0, res.length() - 1);
		}
		return res;
	}

	private void updatePreview() {
		logMethod();
		byte[] pixels = (byte[]) previewProcessor.getPixels();
		int half = (histSize + 1) / 2;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int histOffset = histSize * (x + y * width);
				float firstValue = 0;
				for (int k = 0; k < pointRadius; k++) {
					firstValue += hist[histOffset + k];
				}
				firstValue /= (float) pointRadius;
				float lastValue = 0;
				for (int k = half; k < histSize; k++) {
					lastValue += hist[histOffset + k];
				}
				lastValue /= (float) (histSize - half);
				float yy = firstValue;
				float xx = lastValue;
				pixels[x + y * width] = yy < limitLineA * xx + limitLineB ? (byte) 255 : (byte) 0;
			}
		}
		previewImage.updateAndDraw();
	}

	private void updateNoise() {
		Roi roi = noiseImage.getRoi();
		if (roi == null) {
			return;
		}
		logMethod();
		Point[] points = roi.getContainedPoints();
		int half = (histSize + 1) / 2;
		double[] xx = new double[points.length];
		double[] yy = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			int histOffset = histSize * (points[i].x + points[i].y * width);
			float firstValue = 0;
			for (int k = 0; k < pointRadius; k++) {
				firstValue += hist[histOffset + k];
			}
			firstValue /= (float) pointRadius;
			float lastValue = 0;
			for (int k = half; k < histSize; k++) {
				lastValue += hist[histOffset + k];
			}
			lastValue /= (float) (histSize - half);
			yy[i] = firstValue;
			xx[i] = lastValue;
		}
		plot.setColor(Color.BLUE);
		plot.replace(0, "circle", xx, yy);
		plot.setLimitsToFit(true);
		updateLimitLine();
	}

	private void updatePoints(boolean force) {
		Roi roi = pointsImage.getRoi();
		if (roi == null || !(roi instanceof PointRoi)) {
			return;
		}
		logMethod();
		PointRoi pr = (PointRoi) roi;
		Point[] points = pr.getContainedPoints();
		int half = (histSize + 1) / 2;
		double[] xx = new double[points.length];
		double[] yy = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			int histOffset = histSize * (points[i].x + points[i].y * width);
			float firstValue = 0;
			for (int k = 0; k < pointRadius; k++) {
				firstValue += hist[histOffset + k];
			}
			firstValue /= (float) pointRadius;
			float lastValue = 0;
			for (int k = half; k < histSize; k++) {
				lastValue += hist[histOffset + k];
			}
			lastValue /= (float) (histSize - half);
			yy[i] = firstValue;
			xx[i] = lastValue;
		}
		boolean update = true;
		if (oldPointsX != null && !force && oldPointsX.length == xx.length) {
			update = false;
			for (int i = 0; i < xx.length; i++) {
				if (xx[i] != oldPointsX[i] || yy[i] != oldPointsY[i]) {
					update = true;
					break;
				}
			}
		}
		oldPointsX = xx;
		oldPointsY = yy;
		if (update) {
			plot.setColor(Color.RED);
			plot.replace(1, "circle", xx, yy);
			plot.setLimitsToFit(true);
			updateLimitLine();
		}
	}

	@Override
	public boolean dialogItemChanged(GenericDialog arg0, AWTEvent arg1) {
		logMethod();
		return updateDialog();
	}

	private boolean updateDialog() {
		logMethod();
		Vector<TextField> vect = dialog.getStringFields();
		String p = vect.get(0).getText();
		String w = vect.get(1).getText();
		String r = vect.get(2).getText();
		String a = vect.get(3).getText();
		String b = vect.get(4).getText();
		boolean updatePointRadius = false;
		boolean updatePlotRoi = false;
		if (!p.equals(params[0])) {
			params[0] = p;
			String[] parts = splitParams(p);
			if (parts == null) {
				return false;
			}
			updatePointRadius = !params[2].equals(parts[1]);
			updatePlotRoi = !params[3].equals(parts[2]) || !params[4].equals(parts[3]);
			params[1] = parts[0];
			params[2] = parts[1];
			params[3] = parts[2];
			params[4] = parts[3];
			vect.get(1).setText(params[1]);
			vect.get(2).setText(params[2]);
			vect.get(3).setText(params[3]);
			vect.get(4).setText(params[4]);
		} else if (!(w.equals(params[1]) && r.equals(params[2]) && a.equals(params[3])
				&& b.equals(params[4]))) {
			updatePointRadius = !params[2].equals(r);
			updatePlotRoi = !params[3].equals(a) || !params[4].equals(b);
			params[1] = w;
			params[2] = r;
			params[3] = a;
			params[4] = b;
			params[0] = joinParams();
			vect.get(0).setText(params[0]);
		}
		double[] parsed = parseParams();
		if (parsed != null && plot != null) {
			if (updatePlotRoi) {
				limitLineA = (float) parsed[2];
				limitLineB = (float) parsed[3];
				updateLimitLine();
				updatePreview();
			}
			if (updatePointRadius) {
				pointRadius = (int) (parsed[1] + 0.5);
				updatePoints(true);
				updateNoise();
				updatePreview();
			}
		}
		return parsed != null;
	}

	private String joinParams() {
		return params[1] + "; " + params[2] + "; " + params[3] + "; " + params[4];
	}

	private double[] parseParams() {
		try {
			double[] res = new double[4];
			res[0] = Integer.parseInt(params[1].replace(",", ".").trim());
			res[1] = Integer.parseInt(params[2].replace(",", ".").trim());
			res[2] = Double.parseDouble(params[3].replace(",", ".").trim());
			res[3] = Double.parseDouble(params[4].replace(",", ".").trim());
			if (res[0] < 4.5 || res[0] > 150 || res[1] < 1.5 || res[1] > res[0] * 0.75) {
				return null;
			}
			return res;
		} catch (Exception ex) {
			return null;
		}
	}

	private String[] splitParams(String p) {
		String[] arr = p.split(";");
		if (arr.length == 1) {
			arr = p.split(",");
		}
		if (arr.length != 4) {
			return null;
		}
		for (int i = 0; i < arr.length; i++) {
			arr[i] = arr[i].replace(",", ".").trim();
		}
		return arr;
	}

	private void logMethod() {
		if (true)
			return;
		StackTraceElement[] s = Thread.currentThread().getStackTrace();
		IJ.log("METHOD: " + s[2].getMethodName());
	}

	private void logStack() {
		if (true)
			return;
		StackTraceElement[] s = Thread.currentThread().getStackTrace();
		IJ.log("STACK TRACE:");
		for (int i = 0; i < s.length; i++) {
			IJ.log("    " + s[i].toString());
		}
	}

	static private String helpText = "<html>" +
			"<h1>Wykrywanie punktów</h1>" +
			"<p>TODO: Pomoc</p>";
}
