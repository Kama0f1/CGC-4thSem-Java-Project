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
 * Captures webcam frames and detects a person using MOG2 + convex hull merging.
 *
 * Key improvements over previous version:
 *
 * 1. CONVEX HULL MERGING — instead of picking only the largest contour,
 *    ALL significant contours are merged into one convex hull. This means
 *    if MOG2 detects your torso, arm, and leg separately, they all get
 *    wrapped into one single bounding box covering your full visible body.
 *
 * 2. SMARTER FALL DETECTION — pure width/height ratio breaks when the
 *    laptop is tilted (coordinate axes rotate). We now combine three signals:
 *      a) Aspect ratio (width/height)  — primary signal
 *      b) Box area relative to frame  — large area = person is close/upright
 *      c) Vertical position on screen — if box top is near bottom of frame,
 *         more likely to be on floor
 *    All three are weighted into a confidence score.
 *
 * 3. LARGER DILATE KERNEL — 70x70 (was 55x55) to better merge body parts
 *    when person is very close to the camera.
 *
 * 4. LOWER MIN CONTOUR AREA — 2000px (was 3500px) to catch close-range
 *    detection where the visible body area per contour is smaller.
 */
public class WebcamFrameGrabber {

    private static final Logger logger = LoggerFactory.getLogger(WebcamFrameGrabber.class);

    public static final int FRAME_W = 640;
    public static final int FRAME_H = 480;

    // Minimum contour area to count — lower for close-range
    private static final int MIN_CONTOUR_AREA = 2000;

    // Warmup frames before detection starts
    private static final int WARMUP_FRAMES = 50;

    // EMA smoothing factor
    private static final double SMOOTH = 0.20;

    // Grace frames before box disappears on no detection
    private static final int DISAPPEAR_GRACE = 15;

    // Fall confidence threshold (0.0 to 1.0) — tune if needed
    private static final double FALL_CONFIDENCE_THRESHOLD = 0.50;

    private final OpenCVFrameGrabber         grabber;
    private final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
    private final BackgroundSubtractorMOG2   mog2;
    private final Mat erodeKernel;
    private final Mat dilateKernel;

    private final int    blurKernel;
    private final double fallRatio;     // kept for reference, used as part of score
    private final long   frameDurationMs;

    private int     frameCounter  = 0;
    private long    lastGrabTime  = 0;
    private boolean opened        = false;

    // Smoothed box state
    private double sX = -1, sY = -1, sW = -1, sH = -1;
    private int    missingFrames  = 0;

    // Smoothed fall confidence (EMA applied separately)
    private double sFallConf = 0.0;

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

        // Slightly larger kernels for better close-range detection
        erodeKernel  = getStructuringElement(MORPH_ELLIPSE, new Size(5,  5));
        dilateKernel = getStructuringElement(MORPH_ELLIPSE, new Size(70, 70));

        logger.info("WebcamFrameGrabber (MOG2+Hull): fps={} blur={} fallRatio={}",
                fps, blurKernel, fallRatio);
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

            // Privacy blur for display
            Mat blurred = new Mat();
            GaussianBlur(resized, blurred, new Size(blurKernel, blurKernel), 0);

            // Grayscale + equalise
            Mat gray = new Mat();
            cvtColor(resized, gray, COLOR_BGR2GRAY);
            equalizeHist(gray, gray);

            // Background subtraction
            Mat fgMask = new Mat();
            double lr = frameCounter < WARMUP_FRAMES ? 0.05 : 0.005;
            mog2.apply(resized, fgMask, lr);

            // Cleanup
            erode(fgMask,  fgMask, erodeKernel);
            dilate(fgMask, fgMask, dilateKernel);

            // Find all contours
            MatVector contours = new MatVector();
            findContours(fgMask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            List<Person> persons = new ArrayList<>();

            if (frameCounter >= WARMUP_FRAMES) {

                // ── STEP 1: Collect ALL significant contour points into one list
                // This is the key fix — instead of picking the largest contour,
                // we merge ALL large contours into a single convex hull so the
                // whole visible body (torso + arms + legs) becomes one box.
                MatVector significantContours = new MatVector();
                int sigCount = 0;
                for (int i = 0; i < contours.size(); i++) {
                    if (contourArea(contours.get(i)) >= MIN_CONTOUR_AREA) {
                        sigCount++;
                    }
                }

                if (sigCount > 0) {
                    // Build array of significant contours
                    Mat[] sigArray = new Mat[sigCount];
                    int idx = 0;
                    for (int i = 0; i < contours.size(); i++) {
                        Mat c = contours.get(i);
                        if (contourArea(c) >= MIN_CONTOUR_AREA) {
                            sigArray[idx++] = c;
                        }
                    }

                    // Concatenate all points into one big mat
                    // Then compute convex hull of all points → unified bounding box
                    Mat allPoints = new Mat();
                    vconcat(new MatVector(sigArray), allPoints);

                    Mat hull = new Mat();
                    convexHull(allPoints, hull);

                    // Bounding rect of the hull = box around FULL visible body
                    Rect box = boundingRect(hull);

                    double rx = Math.max(0, box.x());
                    double ry = Math.max(0, box.y());
                    double rw = Math.min(FRAME_W - rx, box.width());
                    double rh = Math.min(FRAME_H - ry, box.height());

                    // ── STEP 2: EMA smooth the box ────────────────────────────
                    if (sX < 0) {
                        sX = rx; sY = ry; sW = rw; sH = rh;
                    } else {
                        sX += SMOOTH * (rx - sX);
                        sY += SMOOTH * (ry - sY);
                        sW += SMOOTH * (rw - sW);
                        sH += SMOOTH * (rh - sH);
                    }
                    missingFrames = 0;

                    // ── STEP 3: Smarter fall confidence score ─────────────────
                    //
                    // Signal A — aspect ratio (width/height)
                    // Standing: ~0.4-0.7 | Fallen: ~1.5+
                    double ratio   = sH > 0 ? sW / sH : 0;
                    double sigA    = Math.min(1.0, Math.max(0, (ratio - 0.9) / (fallRatio - 0.9)));

                    // Signal B — box area relative to frame
                    // When fallen, box tends to cover more horizontal area but less vertical
                    // A very TALL box (large area, small ratio) = clearly standing
                    double boxArea  = sW * sH;
                    double frameArea = FRAME_W * FRAME_H;
                    double relArea  = boxArea / frameArea;
                    // Penalise if box is very tall relative to its width (clearly standing)
                    double sigB = ratio < 0.6 ? 0.0 : Math.min(1.0, relArea * 3.0);

                    // Signal C — vertical position
                    // If the box top edge is in the lower half of the frame, more likely floor
                    double boxTopRel = (sY + sH * 0.3) / FRAME_H;
                    double sigC = Math.min(1.0, Math.max(0, (boxTopRel - 0.3) / 0.5));

                    // Weighted confidence: ratio is most important
                    double rawConf = sigA * 0.60 + sigB * 0.20 + sigC * 0.20;

                    // Smooth the confidence too so it doesn't flicker
                    sFallConf += 0.25 * (rawConf - sFallConf);

                    boolean fallen = sFallConf >= FALL_CONFIDENCE_THRESHOLD;

                    logger.debug("ratio={:.2f} sigA={:.2f} sigB={:.2f} sigC={:.2f} conf={:.2f} fallen={}",
                            ratio, sigA, sigB, sigC, sFallConf, fallen);

                    // Create person with a flag encoded in ID:
                    // ID=1 → detected normally, use isOnFloor(ratio) for fall check
                    // We override isOnFloor() check via FallDetector callback instead
                    // So we encode fall state directly in the width/height we report:
                    // If fallen → make w > h artificially to pass isOnFloor(fallRatio)
                    // If standing → make h > w to fail isOnFloor(fallRatio)
                    int bx = (int) Math.round(sX);
                    int by = (int) Math.round(sY);
                    int bw, bh;

                    if (fallen) {
                        // Ensure ratio > fallRatio so FallDetector sees "on floor"
                        bw = (int) Math.round(sW);
                        bh = (int) Math.max(1, Math.round(bw / (fallRatio + 0.2)));
                    } else {
                        // Ensure ratio < fallRatio so FallDetector sees "upright"
                        bh = (int) Math.round(sH);
                        bw = (int) Math.max(1, Math.round(bh * (fallRatio - 0.3)));
                    }

                    persons.add(new Person(1, bx, by, bw, bh));

                } else {
                    missingFrames++;
                    // Decay fall confidence when nothing detected
                    sFallConf *= 0.85;
                }

                // Keep showing smoothed box during grace period
                if (sX >= 0 && missingFrames > 0 && missingFrames <= DISAPPEAR_GRACE) {
                    boolean fallen = sFallConf >= FALL_CONFIDENCE_THRESHOLD;
                    int bx = (int) Math.round(sX);
                    int by = (int) Math.round(sY);
                    int bw = fallen
                            ? (int) Math.round(sW)
                            : (int) Math.max(1, Math.round(sH * (fallRatio - 0.3)));
                    int bh = fallen
                            ? (int) Math.max(1, Math.round(sW / (fallRatio + 0.2)))
                            : (int) Math.round(sH);
                    persons.add(new Person(1, bx, by, bw, bh));
                } else if (missingFrames > DISAPPEAR_GRACE) {
                    sX = -1;
                    sFallConf = 0;
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