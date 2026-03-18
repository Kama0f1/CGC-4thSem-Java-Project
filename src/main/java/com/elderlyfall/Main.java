// src/main/java/com/elderlyfall/Main.java
package com.elderlyfall;

import com.elderlyfall.config.ConfigLoader;
import com.elderlyfall.ui.MainWindow;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Elder Safety Monitoring System (ESMS).
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        ConfigLoader.load();
        logger.info("ESMS starting. Java {}", System.getProperty("java.version"));
        Application.launch(MainWindow.class, args);
    }
}
