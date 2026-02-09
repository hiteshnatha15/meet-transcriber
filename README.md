# Google Meet Transcription Microservice

A Java Spring Boot microservice that joins Google Meet calls, captures live captions/transcriptions, and sends structured transcripts to a callback URL.

## Features

- 🎯 **REST API** to schedule meeting transcriptions
- 👤 **Anonymous Join** – no Google account; joins as guest with fixed name (Alexa)
- 📝 **Live Caption Capture** with speaker identification
- ⏰ **Scheduled Join/Leave** based on start/end times with timezone support
- 🔄 **Concurrent Meetings** support (configurable thread pool)
- 📤 **Callback Webhook** with structured transcript data
- 📁 **CSV/TXT Export** of transcripts
- 🐳 **Docker Support** for easy deployment

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Service                       │
├─────────────────────────────────────────────────────────────┤
│  POST /api/join-meeting                                      │
│       ↓                                                      │
│  MeetingSchedulerService (ThreadPool for concurrent meets)   │
│       ↓                                                      │
│  PlaywrightService (Browser automation, headless)            │
│       ↓                                                      │
│  Anonymous Join (name: Alexa) → Join Meet → Enable CC → Capture  │
│       ↓                                                      │
│  On end_time: Leave → Save CSV → POST to callbackUrl         │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Linux VM with display server OR headless mode (default)
- Meetings must allow guest access (anonymous join)

## Quick Start

### 1. Clone and Configure

```bash
# Copy environment file (optional – defaults to anonymous join as "Alexa")
cp .env.example .env
```

No Google account is required. The bot joins meetings anonymously with the display name **Alexa** (configurable via `MEETING_BOT_NAME`).

### 2. Run with Docker (Recommended)

```bash
# Build and run
docker-compose up -d

# View logs
docker-compose logs -f
```

### 3. Run Locally (Development)

**Important:** Run all Maven commands from the **project root** (the folder that contains `pom.xml`), e.g. `cd meet-transcriber` then run the commands below.

```bash
# Install Playwright browsers
# Windows (PowerShell): quote -D args so PowerShell doesn't strip them
mvn exec:java -e "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"

# Linux/macOS:
# mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"

# Run the application (from project root)
mvn spring-boot:run
```

Or use the helper script: `./run.sh` (run from project root).

## API Reference

### Schedule a Meeting

```bash
POST /api/join-meeting
Content-Type: application/json

{
  "meetUrl": "https://meet.google.com/abc-defg-hij",
  "uuid": "unique-meeting-id-123",
  "startTime": "2025-02-04T10:00:00",
  "endTime": "2025-02-04T11:00:00",
  "timeZone": "Asia/Kolkata",
  "callbackUrl": "https://your-server.com/webhook/transcript"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Meeting scheduled successfully",
  "data": {
    "uuid": "unique-meeting-id-123",
    "status": "SCHEDULED",
    "scheduledStart": "2025-02-04T10:00+05:30[Asia/Kolkata]",
    "scheduledEnd": "2025-02-04T11:00+05:30[Asia/Kolkata]"
  }
}
```

### Get Meeting Status

```bash
GET /api/meeting/{uuid}
```

### Cancel Meeting

```bash
DELETE /api/meeting/{uuid}
```

### Health Check

```bash
GET /api/health
```

## Callback Payload

When the meeting ends, the service sends this payload to your callback URL:

```json
{
  "uuid": "unique-meeting-id-123",
  "meetUrl": "https://meet.google.com/abc-defg-hij",
  "status": "COMPLETED",
  "meetingStartTime": "2025-02-04T10:00:00",
  "meetingEndTime": "2025-02-04T11:00:00",
  "actualStartTime": "2025-02-04T10:00:15",
  "actualEndTime": "2025-02-04T11:00:02",
  "transcripts": [
    {
      "speaker": "John Doe",
      "text": "Hello everyone, let's get started with the meeting.",
      "timestamp": "2025-02-04T10:01:23"
    },
    {
      "speaker": "Jane Smith",
      "text": "Thanks John. I have the quarterly report ready.",
      "timestamp": "2025-02-04T10:01:45"
    }
  ],
  "totalEntries": 156,
  "csvFilePath": "/data/transcripts/transcript_unique-meeting-id-123_20250204_100000.csv",
  "errorMessage": null
}
```

## Configuration

### application.yml

| Property | Default | Description |
|----------|---------|-------------|
| `meeting.bot-name` | Alexa | Display name when joining as guest (fixed name) |
| `meeting.max-concurrent-meetings` | 10 | Max simultaneous meetings |
| `meeting.transcript-path` | /tmp/transcripts | Where to save CSV/TXT files |
| `playwright.headless` | true | Run browser headless |
| `playwright.slow-mo` | 100 | Slow down actions (ms) |

### Environment Variables

```bash
MEETING_BOT_NAME=Alexa
TRANSCRIPT_PATH=/data/transcripts
PLAYWRIGHT_USER_DATA=/data/playwright
```

## Transcript Output

### CSV Format

```csv
Timestamp,Speaker,Text
2025-02-04T10:01:23,John Doe,Hello everyone let's get started
2025-02-04T10:01:45,Jane Smith,Thanks John I have the quarterly report
```

### TXT Format

```
============================================================
MEETING TRANSCRIPT
============================================================
Meeting UUID: unique-meeting-id-123
Meeting URL: https://meet.google.com/abc-defg-hij
Start Time: 2025-02-04T10:00+05:30[Asia/Kolkata]
End Time: 2025-02-04T11:00+05:30[Asia/Kolkata]
Total Entries: 156
============================================================

[10:01:23] John Doe:
  Hello everyone let's get started with the meeting.

[10:01:45] Jane Smith:
  Thanks John. I have the quarterly report ready.
```

## Deployment on Linux VM

### Using systemd

1. Build the JAR:
```bash
mvn clean package -DskipTests
```

2. Create systemd service:
```bash
sudo nano /etc/systemd/system/meet-transcriber.service
```

```ini
[Unit]
Description=Meet Transcriber Service
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/meet-transcriber
ExecStart=/usr/bin/java -jar -Xmx1g meet-transcriber-1.0.0.jar
Restart=always
RestartSec=10
Environment=MEETING_BOT_NAME=Alexa

[Install]
WantedBy=multi-user.target
```

3. Start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable meet-transcriber
sudo systemctl start meet-transcriber
```

## Troubleshooting

### "No plugin found for prefix 'spring-boot'"

Run Maven from the **project root** (the folder that contains `pom.xml`). If your prompt shows a different directory (e.g. `transcriber %`), run:

```bash
cd /path/to/meet-transcriber
mvn spring-boot:run
```

Or run `./run.sh` from inside the `meet-transcriber` folder.

### "cannot find symbol: variable log" or "method getUuid()/builder()"

The project uses Lombok; the compiler must run Lombok’s annotation processor. Ensure you’re building from the project root with Maven (`mvn clean compile` or `mvn spring-boot:run`). Don’t compile single files with `javac`. If it still fails, run `mvn clean compile` once to refresh the build.

### Cannot Join Meeting

1. Ensure the meeting allows **guest access** (joining without a Google account).
2. Some meetings require the host to allow "Ask to join" or "Join now" for guests.

### Caption Not Capturing

1. Ensure captions are available in the meeting language
2. Check if the meeting host has disabled captions
3. Try keyboard shortcut 'c' to toggle captions

### Browser Crashes

1. Increase `shm_size` in docker-compose.yml
2. Increase Java heap: `-Xmx2g`
3. Reduce `max-concurrent-meetings`

## License

MIT License
# meet-transcriber
