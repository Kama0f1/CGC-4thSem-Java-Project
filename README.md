# 🏥 Elder Safety Monitoring System (ESMS)

> A real-time fall detection system using webcam feed, computer vision, and SMS alerts — built in Java.

![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=java)
![JavaFX](https://img.shields.io/badge/JavaFX-17-blue?style=flat-square)
![OpenCV](https://img.shields.io/badge/OpenCV-4.7-green?style=flat-square)
![Maven](https://img.shields.io/badge/Maven-3.9-red?style=flat-square)
![Twilio](https://img.shields.io/badge/SMS-Twilio-F22F46?style=flat-square&logo=twilio)

---

## 📌 About

ESMS is a Java-based desktop application that monitors a live webcam feed and automatically detects when a person falls and remains on the floor for too long. When a fall is detected beyond the configured threshold, the system fires an **SMS alert** to a caregiver's phone number via Twilio.

Built as a 4th Semester Java project at **CGC (Chandigarh Group of Colleges)**.

---

## 🎬 How It Works

```
Webcam → Gaussian Blur (privacy) → MOG2 Background Subtraction
→ Contour Detection → Bounding Box (EMA smoothed)
→ Fall Logic (width/height ratio) → Alert after threshold
→ SMS via Twilio + alerts.log
```

1. **Webcam Capture** — JavaCV grabs live frames from the built-in camera
2. **Privacy Blur** — Gaussian blur applied to every frame before display
3. **Background Learning** — MOG2 algorithm learns the empty scene over ~50 frames
4. **Motion Detection** — Foreground blobs extracted and merged into one bounding box
5. **Smoothing** — Exponential Moving Average (EMA) keeps the box stable and glitch-free
6. **Fall Detection** — If bounding box width/height ratio ≥ threshold → person is horizontal
7. **Alert** — After configured seconds on floor → SMS sent + log entry written

---

## ✨ Features

- 🎥 **Live blurred webcam feed** — privacy-preserving CCTV-style display
- 🟩 **Stable detection box** — EMA-smoothed bounding box tracks the person
- ⚠️ **Configurable fall threshold** — alert fires after N seconds on the floor
- 📱 **Real SMS alerts** — powered by Twilio API
- 📋 **Alert log** — every alert timestamped and saved to `alerts.log`
- 🖥️ **CCTV-style JavaFX UI** — scanlines, vignette, REC indicator, HUD overlay
- 🔴 **Pulsing red alert overlay** — visual alarm when fall detected
- ⚙️ **Fully configurable** — all settings in a simple `.env` file

---

## 🛠️ Tech Stack

| Component | Technology |
|---|---|
| Language | Java 11+ |
| GUI | JavaFX 17 |
| Computer Vision | OpenCV 4.7 via JavaCV 1.5.9 |
| Background Subtraction | MOG2 (OpenCV) |
| Webcam Capture | JavaCV / FFmpeg |
| SMS Alerts | Twilio Java SDK 9.14.1 |
| Build Tool | Apache Maven 3.9 |
| Logging | SLF4J Simple |

---

## 📁 Project Structure

```
elderly-fall-detector/
├── pom.xml
├── .env                          ← your config (never commit this!)
├── .env.example                  ← template
├── alerts.log                    ← generated at runtime
└── src/main/java/com/elderlyfall/
    ├── Main.java                 ← entry point
    ├── config/
    │   └── ConfigLoader.java     ← reads .env, loads Twilio keys
    ├── model/
    │   ├── Person.java           ← bounding box + fall logic
    │   └── Frame.java            ← frame data + pixel bytes
    ├── capture/
    │   └── WebcamFrameGrabber.java ← MOG2 + EMA smoothing
    ├── detection/
    │   └── FallDetector.java     ← frame counter + alert threshold
    ├── alert/
    │   └── AlertService.java     ← Twilio SMS + log file
    └── ui/
        └── MainWindow.java       ← JavaFX CCTV-style interface
```

---

## ⚙️ Configuration (`.env`)

Copy `.env.example` to `.env` and fill in your values:

```env
# Fall detection
fall.threshold.seconds=4
fall.ratio=1.3

# Camera
camera.index=0
camera.frame.rate=10
privacy.blur.radius=12

# Alert recipient (your mobile in E.164 format)
alert.phone.number=+91XXXXXXXXXX

# Twilio credentials — get from https://console.twilio.com
twilio.account.sid=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
twilio.auth.token=your_auth_token_here
twilio.from.number=+1XXXXXXXXXX
```

> ⚠️ **Never commit your `.env` file to GitHub.** It contains private credentials.

### Configuration Reference

| Key | Default | Description |
|---|---|---|
| `fall.threshold.seconds` | `4` | Seconds on floor before alert fires |
| `fall.ratio` | `1.3` | Bounding box width÷height to classify as fallen |
| `camera.index` | `0` | Webcam index (0 = built-in) |
| `camera.frame.rate` | `10` | Frames per second |
| `privacy.blur.radius` | `12` | Gaussian blur strength |
| `alert.phone.number` | — | SMS recipient number |
| `twilio.account.sid` | — | From Twilio dashboard |
| `twilio.auth.token` | — | From Twilio dashboard |
| `twilio.from.number` | — | Your Twilio phone number |

---

## 🚀 Getting Started

### Prerequisites

- Java 11 or higher
- Apache Maven 3.6+
- A working webcam
- A [Twilio account](https://twilio.com/try-twilio) (free trial works)

### Build & Run

```bash
# 1. Clone the repo
git clone https://github.com/Kama0f1/CGC-4thSem-Java-Project.git
cd CGC-4thSem-Java-Project/elderly-fall-detector

# 2. Set up config
cp .env.example .env
# Edit .env with your Twilio credentials and phone number

# 3. Compile (first run downloads ~200MB of OpenCV/FFmpeg natives)
mvn clean compile

# 4. Run
mvn javafx:run
```

---

## 🧪 Testing

1. Press **▶ START**
2. **Step out of frame** for 5 seconds while the background model warms up
3. **Step back in** — a green bounding box appears around you
4. **Lie down flat** on the floor — box turns red, "FALLEN" label appears
5. Stay down for the threshold duration — SMS fires, red alert overlay flashes

**Tuning tips:**
- Getting false positives? Increase `fall.ratio` to `1.5` or `1.8`
- Falls not detected? Decrease `fall.ratio` to `1.1`
- Alert too slow/fast? Adjust `fall.threshold.seconds`

---

## 📱 SMS Alert

When a fall is detected, the configured phone number receives:

```
ESMS ALERT: A person has been on the floor for too long
and may need help. Please check immediately.
Time: 2026-03-15 14:47:59
```

All alerts are also logged to `alerts.log`:
```
[2026-03-15 14:47:59] PersonID=1 | To=+916283836627 | Person (ID 1) has been on the floor for 4+ seconds!
```

---

## 👨‍💻 Author

**Aditya** — CGC, 4th Semester, Java Project (2026)

---

## 📄 License

This project is for educational purposes as part of a college submission.
