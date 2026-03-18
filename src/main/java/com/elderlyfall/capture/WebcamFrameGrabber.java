// src/main/java/com/elderlyfall/capture/WebcamFrameGrabber.java
package com.elderlyfall.capture;

import com.elderlyfall.config.ConfigLoader;
import com.elderlyfall.model.Frame;
import com.elderlyfall.model.Person;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_video.BackgroundSubtractorMOG2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_video.createBackgroundSubtractorMOG2;

/**
 * Captures webcam frames and detects a person using MOG2 background subtraction.
 *
 * Stability fixes:
 *  - Bounding box is smoothed using Exponential Moving Average (EMA)
 *    so it glides toward the new position instead of jumping each frame
 *  - Large dilate kernel merges all body motion into one solid blob
 *  - Box only disappears after DISAPPEAR_GRACE frames of no detection
 *    (avoids flickering when you briefly stay still)
 */
public class WebcamFrameGrabber {

    private static final Logger logger = LoggerFactory.getLogger(WebcamFrameGrabber.class);

    public static final int FRAME_W = 640;
    public static final int FRAME_H = 480;

    private static final int    MIN_CONTOUR_AREA  = 3500;
    private static final int    WARMUP_FRAMES     = 50;

    /**
     * EMA smoothing factor — lower = smoother but slower to react.
     * 0.2 means each frame moves 20% toward the raw detection.
     * Tune between 0.1 (very smooth/slow) and 0.4 (snappier).
     */
    private static final double SMOOTH = 0.20;

    /**
     * How many frames to keep showing the last known box
     * when detection temporarily drops out.
     */
    private static final int DISAPPEAR_GRACE = 12;

    private final OpenCVFrameGrabber         grabber;
    private final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
    private final BackgroundSubtractorMOG2   mog2;
    private final Mat erodeKernel;
    private final Mat dilateKernel;

    private final int    blurKernel;
    private final double fallRatio;
    private final long   frameDurationMs;

    private int     frameCounter   = 0;
    private long    lastGrabTime   = 0;
    private boolean opened         = false;

    // ── Smoothed box state ────────────────────────────────────────────────────
    /** Current smoothed box (doubles for sub-pixel accuracy during interpolation). */
    private double sX = -1, sY = -1, sW = -1, sH = -1;

    /** Frames since we last had a real detection. */
    private int missingFrames = 0;

    public WebcamFrameGrabber() {
        int    cameraIndex = ConfigLoader.getCameraIndex();
        double blurRadius  = ConfigLoader.getBlurRadius();
        this.fallRatio     = ConfigLoader.getFallRatio();

        int fps = Math.max(1, ConfigLoader.getFrameRate());
        this.frameDurationMs = 1000L / fps;

        int k = (int)(blurRadius * 2 + 1);
        if (k % 2 == 0) k++;
        this.blurKernel = Math.max(3, k);

        grabber = new OpenCVFrameGrabber(cameraIndex);
        grabber.setImageWidth(FRAME_W);
        grabber.setImageHeight(FRAME_H);

        mog2 = createBackgroundSubtractorMOG2(300, 50, false);

        // Erode removes tiny noise specks
        erodeKernel  = getStructuringElement(MORPH_ELLIPSE, new Size(5, 5));
        // Large dilate merges body parts into one big blob
        dilateKernel = getStructuringElement(MORPH_ELLIPSE, new Size(55, 55));

        logger.info("WebcamFrameGrabber (MOG2+EMA): fps={} blur={} fallRatio={} smooth={}",
                fps, blurKernel, fallRatio, SMOOTH);
    }

    public void open() throws FrameGrabber.Exception {
        grabber.start();
        opened       = true;
        lastGrabTime = System.currentTimeMillis();
        logger.info("Webcam opened. Warming up ({} frames)…", WARMUP_FRAMES);
    }

    public Frame nextFrame() {
        if (!opened) return null;

        // FPS throttle
        long elapsed = System.currentTimeMillis() - lastGrabTime;
        if (elapsed < frameDurationMs) {
            try { Thread.sleep(frameDurationMs - elapsed); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        lastGrabTime = System.currentTimeMillis();

        try {
            org.bytedeco.javacv.Frame raw = grabber.grab();
            if (raw == null) return null;

            Mat bgr = converter.convert(raw);
            if (bgr == null || bgr.empty()) return null;

            Mat resized = new Mat();
            resize(bgr, resized, new Size(FRAME_W, FRAME_H));

            // Privacy blur (display only)
            Mat blurred = new Mat();
            GaussianBlur(resized, blurred, new Size(blurKernel, blurKernel), 0);

            // Grayscale + equalise for stable detection
            Mat gray = new Mat();
            cvtColor(resized, gray, COLOR_BGR2GRAY);
            equalizeHist(gray, gray);

            // Background subtraction
            Mat fgMask = new Mat();
            double lr = frameCounter < WARMUP_FRAMES ? 0.05 : 0.005;
            mog2.apply(resized, fgMask, lr);

            // Clean up mask
            erode(fgMask,  fgMask, erodeKernel);
            dilate(fgMask, fgMask, dilateKernel);

            // Find contours
            MatVector contours = new MatVector();
            findContours(fgMask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            // ── Detect & smooth ───────────────────────────────────────────────
            List<Person> persons = new ArrayList<>();

            if (frameCounter >= WARMUP_FRAMES) {

                // Find the largest contour above minimum area
                Mat    bestContour = null;
                double bestArea    = 0;
                for (int i = 0; i < contours.size(); i++) {
                    double area = contourArea(contours.get(i));
                    if (area > bestArea && area >= MIN_CONTOUR_AREA) {
                        bestArea    = area;
                        bestContour = contours.get(i);
                    }
                }

                if (bestContour != null) {
                    // Raw detection box
                    Rect r  = boundingRect(bestContour);
                    double rx = Math.max(0, r.x());
                    double ry = Math.max(0, r.y());
                    double rw = Math.min(FRAME_W - rx, r.width());
                    double rh = Math.min(FRAME_H - ry, r.height());

                    if (sX < 0) {
                        // First detection — initialise smoothed box directly
                        sX = rx; sY = ry; sW = rw; sH = rh;
                    } else {
                        // EMA: smoothed = smoothed + SMOOTH * (raw - smoothed)
                        sX += SMOOTH * (rx - sX);
                        sY += SMOOTH * (ry - sY);
                        sW += SMOOTH * (rw - sW);
                        sH += SMOOTH * (rh - sH);
                    }
                    missingFrames = 0;

                } else {
                    // No detection this frame
                    missingFrames++;
                }

                // Show the smoothed box as long as we have one and haven't
                // been missing for too long
                if (sX >= 0 && missingFrames <= DISAPPEAR_GRACE) {
                    int bx = (int) Math.round(sX);
                    int by = (int) Math.round(sY);
                    int bw = (int) Math.round(sW);
                    int bh = (int) Math.round(sH);
                    persons.add(new Person(1, bx, by, bw, bh));
                } else if (missingFrames > DISAPPEAR_GRACE) {
                    // Reset smoothed box after prolonged absence
                    sX = -1;
                }
            }

            frameCounter++;
            byte[] pixels = new byte[FRAME_W * FRAME_H * 3];
            blurred.data().get(pixels);

            bgr.release();
            resized.release();
            gray.release();
            fgMask.release();
            blurred.release();

            return new Frame(frameCounter, persons, pixels, FRAME_W, FRAME_H);

        } catch (Exception e) {
            logger.error("Frame grab error: {}", e.getMessage());
            return null;
        }
    }

    public void close() {
        if (!opened) return;
        try {
            grabber.stop();
            opened = false;
            logger.info("Webcam closed.");
        } catch (FrameGrabber.Exception e) {
            logger.warn("Error closing webcam: {}", e.getMessage());
        }
    }

    public boolean isWarming() { return frameCounter < WARMUP_FRAMES; }
    public boolean isOpened()  { return opened; }
}