// src/main/java/com/elderlyfall/config/ConfigLoader.java
package com.elderlyfall.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Loads configuration from .env file.
 * Also pushes Twilio credentials into System properties
 * so AlertService can read them.
 */
public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Properties props = new Properties();

    private static final int    DEFAULT_FALL_THRESHOLD = 5;
    private static final String DEFAULT_PHONE          = "+1234567890";
    private static final int    DEFAULT_FRAME_RATE     = 10;
    private static final int    DEFAULT_CAMERA_INDEX   = 0;
    private static final double DEFAULT_BLUR_RADIUS    = 12.0;
    private static final double DEFAULT_FALL_RATIO     = 1.3;

    static { load(); }

    public static void load() {
        File f = new File(".env");
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                props.load(fis);
                logger.info("Config loaded from .env");
            } catch (IOException e) {
                logger.warn("Could not read .env: {}", e.getMessage());
            }
        } else {
            logger.warn(".env not found in '{}' — using defaults.",
                    System.getProperty("user.dir"));
        }

        // Push Twilio credentials into System properties
        // so AlertService can access them without importing ConfigLoader
        pushToSystemProp("twilio.account.sid");
        pushToSystemProp("twilio.auth.token");
        pushToSystemProp("twilio.from.number");
    }

    /**
     * Copies a key from .env props into System.setProperty.
     */
    private static void pushToSystemProp(String key) {
        String val = props.getProperty(key, "").trim();
        if (!val.isEmpty()) {
            System.setProperty(key, val);
            logger.info("Twilio config loaded: {}", key);
        } else {
            logger.warn("Missing .env key: {}", key);
        }
    }

    public static int    getFallThresholdSeconds() { return getInt("fall.threshold.seconds",  DEFAULT_FALL_THRESHOLD); }
    public static String getAlertPhoneNumber()     { return getString("alert.phone.number",   DEFAULT_PHONE); }
    public static int    getFrameRate()            { return getInt("camera.frame.rate",        DEFAULT_FRAME_RATE); }
    public static int    getCameraIndex()          { return getInt("camera.index",             DEFAULT_CAMERA_INDEX); }
    public static double getBlurRadius()           { return getDouble("privacy.blur.radius",   DEFAULT_BLUR_RADIUS); }
    public static double getFallRatio()            { return getDouble("fall.ratio",            DEFAULT_FALL_RATIO); }

    private static String getString(String key, String def) {
        String v = props.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static double getDouble(String key, double def) {
        String v = props.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}