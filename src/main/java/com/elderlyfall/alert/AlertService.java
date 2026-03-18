// src/main/java/com/elderlyfall/alert/AlertService.java
package com.elderlyfall.alert;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Dispatches fall alerts via Twilio SMS, console log, and alerts.log.
 */
public final class AlertService {

    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);
    private static final String LOG_FILE = "alerts.log";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AlertService() {}

    public static void sendAlert(String message, String toPhone, int personId) {
        String ts   = LocalDateTime.now().format(FMT);
        String line = String.format("[%s] PersonID=%d | To=%s | %s",
                ts, personId, toPhone, message);

        // 1. Console
        logger.warn("ALERT: {}", line);

        // 2. Log file
        try (BufferedWriter w = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            w.write(line);
            w.newLine();
        } catch (IOException e) {
            logger.error("Could not write {}: {}", LOG_FILE, e.getMessage());
        }

        // 3. Twilio SMS
        String accountSid = System.getProperty("twilio.account.sid",  "").trim();
        String authToken  = System.getProperty("twilio.auth.token",   "").trim();
        String fromPhone  = System.getProperty("twilio.from.number",  "").trim();

        if (accountSid.isEmpty() || authToken.isEmpty() || fromPhone.isEmpty()) {
            logger.warn("Twilio credentials missing in .env — SMS not sent.");
            return;
        }

        try {
            Twilio.init(accountSid, authToken);

            String body = "ESMS ALERT: A person has been on the floor for too long "
                    + "and may need help. Please check immediately. "
                    + "Time: " + ts;

            Message msg = Message.creator(
                    new PhoneNumber(toPhone),
                    new PhoneNumber(fromPhone),
                    body
            ).create();

            logger.info("SMS sent successfully — SID: {}", msg.getSid());

        } catch (Exception e) {
            logger.error("Twilio SMS failed: {}", e.toString());
        }
    }
}