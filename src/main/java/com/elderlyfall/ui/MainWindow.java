// src/main/java/com/elderlyfall/ui/MainWindow.java
package com.elderlyfall.ui;

import com.elderlyfall.capture.WebcamFrameGrabber;
import com.elderlyfall.config.ConfigLoader;
import com.elderlyfall.detection.FallDetector;
import com.elderlyfall.model.Frame;
import com.elderlyfall.model.Person;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Main JavaFX window — CCTV-style feed with MOG2-based detection overlay.
 */
public class MainWindow extends Application {

    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);

    private static final double W = 640;
    private static final double H = 480;

    private WebcamFrameGrabber grabber;
    private FallDetector       detector;

    private Label  statusLabel;
    private Label  timerLabel;
    private Label  infoLabel;
    private Canvas canvas;
    private Button startBtn;
    private Button stopBtn;

    private volatile Frame   latestFrame    = null;
    private volatile boolean alertTriggered = false;
    private          Thread  captureThread;
    private          AnimationTimer animTimer;
    private volatile boolean running = false;

    private final int    frameRate = Math.max(1, ConfigLoader.getFrameRate());
    private final double fallRatio = ConfigLoader.getFallRatio();
    private final Random rng       = new Random(7);

    private final double[] nX = new double[300];
    private final double[] nY = new double[300];
    private final double[] nR = new double[300];
    private final double[] nA = new double[300];

    @Override
    public void start(Stage stage) {
        ConfigLoader.load();
        prebakeNoise();
        buildUI(stage);
        stage.show();
    }

    @Override
    public void stop() { stopMonitoring(); }

    private void prebakeNoise() {
        for (int i = 0; i < nX.length; i++) {
            nX[i] = rng.nextDouble() * W;
            nY[i] = rng.nextDouble() * H;
            nR[i] = rng.nextDouble() * 2.0 + 0.5;
            nA[i] = rng.nextDouble() * 0.10 + 0.02;
        }
    }

    private void buildUI(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #080808;");

        statusLabel = new Label("● SYSTEM READY");
        statusLabel.setFont(Font.font("Courier New", FontWeight.BOLD, 15));
        statusLabel.setTextFill(Color.LIMEGREEN);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setAlignment(Pos.CENTER_LEFT);
        statusLabel.setPadding(new Insets(8, 14, 8, 14));
        statusLabel.setStyle("-fx-background-color: #0d0d0d;");

        infoLabel = new Label("CAM-01  |  MOTION DETECTION  |  MOG2");
        infoLabel.setFont(Font.font("Courier New", 11));
        infoLabel.setTextFill(Color.rgb(90, 90, 90));
        infoLabel.setPadding(new Insets(3, 14, 5, 14));
        infoLabel.setStyle("-fx-background-color: #0d0d0d;");

        VBox top = new VBox(0, statusLabel, infoLabel);
        root.setTop(top);

        canvas = new Canvas(W, H);
        drawIdleScreen();
        root.setCenter(canvas);

        timerLabel = new Label("TIME ON FLOOR:  0.0 s");
        timerLabel.setFont(Font.font("Courier New", FontWeight.BOLD, 13));
        timerLabel.setTextFill(Color.rgb(160, 160, 160));

        startBtn = new Button("▶  START");
        startBtn.setStyle(btnCss("#1a6e35"));
        startBtn.setOnAction(e -> startMonitoring());

        stopBtn = new Button("⏹  STOP");
        stopBtn.setStyle(btnCss("#6e1a1a"));
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> stopMonitoring());

        HBox bottom = new HBox(24, timerLabel, startBtn, stopBtn);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(10, 14, 10, 14));
        bottom.setStyle("-fx-background-color: #0d0d0d;");
        root.setBottom(bottom);

        stage.setScene(new Scene(root));
        stage.setTitle("ESMS — Elder Safety Monitoring System");
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> stop());
    }

    private String btnCss(String bg) {
        return "-fx-background-color:" + bg + ";-fx-text-fill:white;"
                + "-fx-font-family:'Courier New';-fx-font-weight:bold;"
                + "-fx-font-size:12px;-fx-padding:6 18 6 18;-fx-background-radius:2;";
    }

    private void startMonitoring() {
        if (running) return;

        grabber  = new WebcamFrameGrabber();
        detector = new FallDetector();
        detector.setOnAlertCallback(id -> Platform.runLater(() -> {
            alertTriggered = true;
            statusLabel.setText("⚠  ALERT — PERSON DOWN — NO RESPONSE");
            statusLabel.setTextFill(Color.RED);
        }));

        try {
            grabber.open();
        } catch (Exception e) {
            statusLabel.setText("✖  CAMERA ERROR: " + e.getMessage());
            statusLabel.setTextFill(Color.ORANGE);
            return;
        }

        running        = true;
        alertTriggered = false;
        startBtn.setDisable(true);
        stopBtn.setDisable(false);
        statusLabel.setText("⏳ LEARNING BACKGROUND — STAY STILL (5s)…");
        statusLabel.setTextFill(Color.ORANGE);

        captureThread = new Thread(() -> {
            while (running) {
                Frame f = grabber.nextFrame();
                if (f != null) {
                    // Update status when warmup completes
                    if (!grabber.isWarming() && alertTriggered == false) {
                        Platform.runLater(() -> {
                            if (!alertTriggered) {
                                statusLabel.setText("● MONITORING ACTIVE");
                                statusLabel.setTextFill(Color.LIMEGREEN);
                            }
                        });
                    }
                    detector.processFrame(f);
                    latestFrame = f;
                }
            }
            grabber.close();
        }, "esms-capture");
        captureThread.setDaemon(true);
        captureThread.start();

        animTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                Frame f = latestFrame;
                if (f != null) {
                    renderFrame(f, now);
                    updateHUD(f);
                }
            }
        };
        animTimer.start();
    }

    private void stopMonitoring() {
        if (!running) return;
        running = false;
        if (animTimer     != null) animTimer.stop();
        if (captureThread != null) captureThread.interrupt();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        statusLabel.setText("● SYSTEM STOPPED");
        statusLabel.setTextFill(Color.GRAY);
        timerLabel.setText("TIME ON FLOOR:  0.0 s");
        timerLabel.setTextFill(Color.rgb(160, 160, 160));
        drawIdleScreen();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void drawIdleScreen() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.rgb(8, 8, 10));
        gc.fillRect(0, 0, W, H);
        drawScanlines(gc);
        drawVignette(gc);
        gc.setFill(Color.rgb(55, 55, 55));
        gc.setFont(Font.font("Courier New", FontWeight.BOLD, 18));
        gc.fillText("NO SIGNAL", W / 2 - 62, H / 2 - 8);
        gc.setFont(Font.font("Courier New", 12));
        gc.fillText("Press START to begin monitoring", W / 2 - 118, H / 2 + 22);
        drawHUDChrome(gc, 0, false);
    }

    private void renderFrame(Frame frame, long now) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        int fn = frame.getFrameNumber();

        // 1. Blurred webcam image
        drawWebcamImage(gc, frame);

        // 2. Warmup overlay
        if (grabber != null && grabber.isWarming()) {
            drawWarmupOverlay(gc, fn);
        } else {
            // 3. Detection boxes
            for (Person p : frame.getPersons()) {
                drawPersonOverlay(gc, p, fn);
            }
        }

        // 4. Film grain
        drawGrain(gc, fn);

        // 5. Scanlines
        drawScanlines(gc);

        // 6. Vignette
        drawVignette(gc);

        // 7. Alert overlay
        if (alertTriggered) drawAlertOverlay(gc, now, fn);

        // 8. HUD
        drawHUDChrome(gc, fn, alertTriggered);
    }

    private void drawWebcamImage(GraphicsContext gc, Frame frame) {
        byte[] data = frame.getImageData();
        if (data == null) return;
        int fw = frame.getImageWidth();
        int fh = frame.getImageHeight();
        WritableImage img = new WritableImage(fw, fh);
        PixelWriter   pw  = img.getPixelWriter();
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                int idx = (y * fw + x) * 3;
                int b   = data[idx]     & 0xFF;
                int g   = data[idx + 1] & 0xFF;
                int r   = data[idx + 2] & 0xFF;
                pw.setArgb(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        gc.drawImage(img, 0, 0, W, H);
    }

    private void drawWarmupOverlay(GraphicsContext gc, int fn) {
        // Pulsing amber border during warmup
        double pulse = (Math.sin(fn * 0.4) + 1) / 2.0;
        gc.setStroke(Color.rgb(255, 180, 0, 0.4 + pulse * 0.5));
        gc.setLineWidth(5);
        gc.strokeRect(3, 3, W - 6, H - 6);
        gc.setFill(Color.rgb(255, 180, 0, 0.75));
        gc.setFont(Font.font("Courier New", FontWeight.BOLD, 14));
        gc.fillText("⏳ LEARNING BACKGROUND — PLEASE STEP OUT OF FRAME", 40, 30);
    }

    private void drawPersonOverlay(GraphicsContext gc, Person p, int fn) {
        boolean fallen = p.isOnFloor(fallRatio);

        double x = p.getX();
        double y = p.getY();
        double w = p.getWidth();
        double h = p.getHeight();

        Color boxColor = fallen
                ? Color.rgb(255, 60,  60,  0.90)
                : Color.rgb(60,  220, 100, 0.85);

        // Outer glow
        gc.setStroke(new Color(boxColor.getRed(), boxColor.getGreen(),
                boxColor.getBlue(), 0.20));
        gc.setLineWidth(8);
        gc.strokeRect(x - 3, y - 3, w + 6, h + 6);

        // Main box
        gc.setStroke(boxColor);
        gc.setLineWidth(2.5);
        gc.strokeRect(x, y, w, h);

        // Corner ticks
        double tick = Math.min(w, h) * 0.18;
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2.5);
        gc.strokeLine(x,     y,     x + tick, y);
        gc.strokeLine(x,     y,     x,     y + tick);
        gc.strokeLine(x + w, y,     x + w - tick, y);
        gc.strokeLine(x + w, y,     x + w, y + tick);
        gc.strokeLine(x,     y + h, x + tick, y + h);
        gc.strokeLine(x,     y + h, x,     y + h - tick);
        gc.strokeLine(x + w, y + h, x + w - tick, y + h);
        gc.strokeLine(x + w, y + h, x + w, y + h - tick);

        // Label
        double ratio = (double) p.getWidth() / Math.max(1, p.getHeight());
        String label = fallen
                ? String.format("▼ FALLEN  r=%.2f", ratio)
                : String.format("▲ UPRIGHT  r=%.2f", ratio);

        gc.setFill(new Color(boxColor.getRed(), boxColor.getGreen(),
                boxColor.getBlue(), 0.80));
        gc.fillRect(x, y - 18, label.length() * 7.5, 16);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("Courier New", FontWeight.BOLD, 11));
        gc.fillText(label, x + 3, y - 5);

        // Pulse ring when fallen
        if (fallen) {
            double pulse = (Math.sin(fn * 0.25) + 1) / 2.0;
            gc.setStroke(Color.rgb(255, 50, 50, 0.15 + pulse * 0.30));
            gc.setLineWidth(3);
            gc.strokeOval(x - pulse * 10, y - pulse * 10,
                          w + pulse * 20, h + pulse * 20);
        }
    }

    private void drawGrain(GraphicsContext gc, int fn) {
        double shift = (fn % 9) * 1.2;
        for (int i = 0; i < nX.length; i++) {
            double a = nA[i] * (0.6 + 0.4 * rng.nextDouble());
            gc.setFill(Color.rgb(255, 255, 255, a));
            gc.fillOval((nX[i] + shift) % W, nY[i], nR[i], nR[i]);
        }
    }

    private void drawScanlines(GraphicsContext gc) {
        gc.setStroke(Color.rgb(0, 0, 0, 0.13));
        gc.setLineWidth(1);
        for (double y = 0; y < H; y += 3) gc.strokeLine(0, y, W, y);
    }

    private void drawVignette(GraphicsContext gc) {
        RadialGradient v = new RadialGradient(0, 0, W/2, H/2,
                Math.max(W, H) * 0.68, false, CycleMethod.NO_CYCLE,
                new Stop(0.0,  Color.rgb(0,0,0, 0.00)),
                new Stop(0.60, Color.rgb(0,0,0, 0.00)),
                new Stop(1.0,  Color.rgb(0,0,0, 0.78)));
        gc.setFill(v);
        gc.fillRect(0, 0, W, H);
    }

    private void drawAlertOverlay(GraphicsContext gc, long now, int fn) {
        double pulse = (Math.sin(now / 2.8e8) + 1) / 2.0;
        gc.setFill(Color.rgb(160, 0, 0, 0.06 + pulse * 0.09));
        gc.fillRect(0, 0, W, H);
        gc.setStroke(Color.rgb(255, 30, 30, 0.45 + pulse * 0.55));
        gc.setLineWidth(7);
        gc.strokeRect(3.5, 3.5, W - 7, H - 7);
        gc.setFill(Color.rgb(255, 40, 40, 0.80 + pulse * 0.18));
        gc.setFont(Font.font("Courier New", FontWeight.BOLD, 15));
        gc.fillText("! PERSON DOWN — NO MOVEMENT DETECTED !", 62, 44);
    }

    private void drawHUDChrome(GraphicsContext gc, int fn, boolean alert) {
        gc.setFill(Color.rgb(160, 160, 160, 0.55));
        gc.setFont(Font.font("Courier New", 11));
        gc.fillText("CAM-01", W - 78, 18);
        if (fn > 0) gc.fillText(String.format("F:%04d", fn), W - 78, 32);

        if (running) {
            double blink = alert ? 1.0 : (0.5 + Math.abs(Math.sin(fn * 0.28)) * 0.5);
            gc.setFill(Color.rgb(230, 35, 35, blink));
            gc.fillOval(10, H - 22, 9, 9);
            gc.setFill(Color.rgb(190, 190, 190, 0.65));
            gc.setFont(Font.font("Courier New", 11));
            gc.fillText("REC", 24, H - 13);
        }

        Frame f = latestFrame;
        if (f != null && fn > 0) {
            String detStr = f.getPersons().isEmpty() ? "NO DETECTION" : "PERSON DETECTED";
            Color  detCol = f.getPersons().isEmpty()
                    ? Color.rgb(120, 120, 120, 0.5)
                    : Color.rgb(80, 220, 100, 0.75);
            gc.setFill(detCol);
            gc.setFont(Font.font("Courier New", 11));
            gc.fillText(detStr, W - 130, H - 13);
        }
    }

    private void updateHUD(Frame f) {
        if (detector == null) return;
        int    maxF    = detector.getMaxFloorFrames(1);
        double seconds = (double) maxF / frameRate;
        timerLabel.setText(String.format("TIME ON FLOOR:  %.1f s", seconds));

        if (alertTriggered) {
            timerLabel.setTextFill(Color.RED);
        } else if (seconds > ConfigLoader.getFallThresholdSeconds() * 0.6) {
            timerLabel.setTextFill(Color.ORANGE);
        } else {
            timerLabel.setTextFill(Color.rgb(160, 160, 160));
        }

        infoLabel.setText(String.format(
                "CAM-01  |  FRAME %04d  |  MOG2 MOTION DETECTION", f.getFrameNumber()));
    }
}