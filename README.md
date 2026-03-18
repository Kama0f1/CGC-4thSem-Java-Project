# Elder Safety Monitoring System (ESMS) — v2 (Real Webcam)

Real-time fall detection using your webcam, JavaCV (OpenCV), and JavaFX.

## How it works

1. **Webcam capture** — JavaCV grabs live frames from your built-in camera
2. **Privacy blur** — Gaussian blur is applied to every frame before display
3. **HOG person detection** — OpenCV's pre-trained HOG descriptor detects people
4. **Fall logic** — if a detected person's bounding box width/height ratio >= `fall.ratio`, they are considered horizontal (fallen)
5. **Alert** — if they stay fallen for >= `fall.threshold.seconds`, an alert fires and is logged to `alerts.log`

## Requirements

- Java 11+
- Maven 3.6+
- Windows 10/11 x64
- A working webcam

## Build & Run

```bash
# 1. Navigate to project folder
cd elderly-fall-detector

# 2. Compile (first run downloads ~200MB of JavaCV/OpenCV natives)
mvn clean compile

# 3. Run
mvn javafx:run
```

## Configuration (.env)

| Key | Default | Description |
|-----|---------|-------------|
| `fall.threshold.seconds` | `5` | Seconds on floor before alert |
| `alert.phone.number` | `+1234567890` | Logged to alerts.log |
| `camera.index` | `0` | Webcam device (0 = built-in) |
| `camera.frame.rate` | `15` | FPS |
| `privacy.blur.radius` | `15` | Blur strength (higher = more blur) |
| `fall.ratio` | `1.3` | Width/height ratio for fall detection |

## Tuning fall detection

- If you get **false positives** (standing person detected as fallen): increase `fall.ratio` to 1.5 or 1.8
- If falls are **not detected**: decrease `fall.ratio` to 1.1
- Alert fires **too slowly**: decrease `fall.threshold.seconds`

## Output

- Live blurred webcam feed in the GUI
- Green box = standing person detected
- Red box + pulse ring = fallen person detected  
- Red flashing border + alert text = fall sustained too long
- `alerts.log` — timestamped log of all alerts
