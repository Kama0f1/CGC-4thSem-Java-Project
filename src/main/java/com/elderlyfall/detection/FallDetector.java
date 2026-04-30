// src/main/java/com/elderlyfall/detection/FallDetector.java
package com.elderlyfall.detection;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.elderlyfall.alert.AlertService;
import com.elderlyfall.config.ConfigLoader;
import com.elderlyfall.model.Frame;
import com.elderlyfall.model.Person;

/**
 * Analyses each Frame for prolonged falls.
 *
 * Logic:
 *  - Tracks each detected person by ID across frames
 *  - A person is "on floor" when their bounding box width/height ratio >= fall.ratio
 *  - If any person stays on the floor for >= fall.threshold.seconds, alert fires
 *  - Alert fires only once per fall episode; resets when person stands
 */
public class FallDetector {

    private static final Logger logger = LoggerFactory.getLogger(FallDetector.class);

    private final int    alertFrameThreshold;
    private final int    frameRate;
    private final double fallRatio;
    private final String alertPhoneNumber;

    // Per-person state
    private final Map<Integer, Integer> floorFrameCounts      = new HashMap<>();
    private final Map<Integer, Boolean> alertAlreadySent     = new HashMap<>();
    private final Map<Integer, Integer> maxFloorFrames       = new HashMap<>();
    private final Map<Integer, Integer> previousHeight       = new HashMap<>();
    private final Map<Integer, Integer> consecutiveFloorFrames = new HashMap<>();

    // UI callback
    private Consumer<Integer> onAlertCallback;

    // Global alert flag for UI overlay
    private volatile boolean alertActive = false;

    public FallDetector() {
        this.frameRate           = Math.max(1, ConfigLoader.getFrameRate());
        this.alertFrameThreshold = ConfigLoader.getFallThresholdSeconds() * frameRate;
        this.fallRatio           = ConfigLoader.getFallRatio();
        this.alertPhoneNumber    = ConfigLoader.getAlertPhoneNumber();
        logger.info("FallDetector ready: threshold={}f ({}s x {}fps), fallRatio={}",
                alertFrameThreshold, ConfigLoader.getFallThresholdSeconds(), frameRate, fallRatio);
    }

    public void processFrame(Frame frame) {
        // If no persons detected this frame, decay all counters slowly
        // (avoids false resets from momentary detection gaps)
        if (frame.getPersons().isEmpty()) {
            return;
        }

        for (Person p : frame.getPersons()) {
            int id = p.getId();
            int currHeight = p.getHeight();
            int prevHeight = previousHeight.getOrDefault(id, currHeight);
            previousHeight.put(id, currHeight);
            
            // Detect sudden vertical drop (sign of falling)
            boolean suddenDrop = prevHeight > 0 && (prevHeight - currHeight) > (prevHeight * 0.3); // >30% height loss
            
            if (p.isOnFloor(fallRatio) || suddenDrop) {
                handleOnFloor(p, suddenDrop);
            } else {
                handleOffFloor(p);
            }
        }
    }

    private void handleOnFloor(Person p, boolean suddenDrop) {
        int id    = p.getId();
        
        // If sudden drop detected, immediately trigger alert (likely a real fall)
        if (suddenDrop) {
            consecutiveFloorFrames.put(id, alertFrameThreshold);
            logger.warn("Person {} detected sudden drop! Likely falling.", id);
        } else {
            // Increment floor frame count for gradual detection
            consecutiveFloorFrames.merge(id, 1, Integer::sum);
        }
        
        int count = floorFrameCounts.merge(id, 1, Integer::sum);
        maxFloorFrames.merge(id, count, Integer::max);

        logger.debug("Person {} on floor for {} consecutive frames (ratio={:.2f})",
                id, consecutiveFloorFrames.getOrDefault(id, 0), 
                (double) p.getWidth() / Math.max(1, p.getHeight()));

        int consecutiveCount = consecutiveFloorFrames.getOrDefault(id, 0);
        if (consecutiveCount >= alertFrameThreshold) {
            boolean sent = alertAlreadySent.getOrDefault(id, false);
            if (!sent) {
                alertActive = true;
                alertAlreadySent.put(id, true);
                String msg = String.format(
                        "Person (ID %d) has been on the floor for %d+ seconds!",
                        id, ConfigLoader.getFallThresholdSeconds());
                AlertService.sendAlert(msg, alertPhoneNumber, id);
                if (onAlertCallback != null) onAlertCallback.accept(id);
            }
        }
    }

    private void handleOffFloor(Person p) {
        int id  = p.getId();
        int prev = floorFrameCounts.getOrDefault(id, 0);
        if (prev > 0) {
            logger.info("Person {} stood up after {} floor frames.", id, prev);
            if (alertActive) {
                alertActive = false;
                logger.info("Alert cleared — person {} is upright.", id);
            }
        }
        floorFrameCounts.put(id, 0);
        consecutiveFloorFrames.put(id, 0);
        alertAlreadySent.put(id, false);
    }

    public void setOnAlertCallback(Consumer<Integer> cb) { this.onAlertCallback = cb; }
    public boolean isAlertActive()                       { return alertActive; }
    public int getMaxFloorFrames(int personId)           { return maxFloorFrames.getOrDefault(personId, 0); }
    public int getCurrentFloorFrames(int personId)       { return floorFrameCounts.getOrDefault(personId, 0); }

    public void reset() {
        floorFrameCounts.clear();
        alertAlreadySent.clear();
        maxFloorFrames.clear();
        previousHeight.clear();
        consecutiveFloorFrames.clear();
        alertActive = false;
        logger.info("FallDetector reset.");
    }
}
