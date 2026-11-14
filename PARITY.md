# KaivoFlow — Capability & Node Parity Matrix

> **Tracking node availability, mobile implementation status, and mobile-native improvements.**

---

| Category | Node ID | Title | KaivoFlow Status | Mobile Improvement over Traditional Systems |
|---|---|---|---|---|
| **Sparks** | `trigger.manual` | Manual Spark | Full | Haptic feedback on tap, instant HUD execution |
| **Sparks** | `trigger.schedule` | On a Schedule | Full | Auto-rescheduled WorkManager + Boot re-registration |
| **Sparks** | `trigger.webhook` | Local Webhook | Full | On-device HTTP/1.1 server on local Wi-Fi port |
| **Sparks** | `trigger.notification`| Notification Listener | Full | Intercepts bank SMS, WhatsApp, and app alerts directly |
| **Sparks** | `trigger.sms` | Incoming SMS | Full | Direct cellular telephony interception |
| **Sparks** | `trigger.battery` | Battery Spark | Full | Triggers when battery drops below threshold or charges |
| **Sparks** | `trigger.share` | Share Sheet | Full | App appears directly in Android system share sheet |
| **Logic** | `logic.if` | If / Else | Full | Dual-port visual branching on canvas |
| **Logic** | `logic.switch` | Switch Router | Full | 5-way value routing |
| **Logic** | `logic.merge` | Merge | Full | Re-combines branches (append, zip, multiply, combine) |
| **Logic** | `logic.filter` | Filter | Full | In-memory stream filtering |
| **Logic** | `logic.loop` | Loop / Batch | Full | Item-by-item chunking for rate limits |
| **Logic** | `logic.wait` | Wait / Delay | Full | Non-blocking coroutine sleep with optional jitter |
| **Logic** | `logic.stop` | Stop | Full | Early termination with status reporting |
| **Logic** | `logic.onError` | Error Handler | Full | Dedicated error branch routing |
| **Transform** | `transform.set` | Set Fields | Full | Visual key-value field editor with expressions |
| **Transform** | `transform.fields` | Rename/Remove | Full | Bulk rename and drop fields |
| **Transform** | `transform.json` | JSON Parse/Build | Full | Sandboxed non-throwing JSON parser |
| **Transform** | `transform.template` | Write Text | Full | Multi-line text interpolator |
| **Transform** | `transform.date` | Date & Time | Full | Formatting, shifting, and difference calculator |
| **Transform** | `transform.aggregate`| Group & Total | Full | Group by field, sum, average, and count |
| **Transform** | `transform.code` | Run JavaScript | Full | Sandboxed QuickJS / Kotlin fallback evaluator |
| **Transform** | `transform.crypto` | Crypto & Hash | Full | Native SHA-256, MD5, SHA-1, HMAC, Base64, UUID |
| **AI** | `ai.chat` | Ask AI | Full | Gemini, Ollama, & OpenAI-compatible endpoints |
| **AI** | `ai.extract` | Extract Data | Full | Structured JSON schema extraction from messy text |
| **AI** | `ai.classify` | Classify | Full | Multi-category text routing |
| **Device** | `device.notify` | Show Notification | Full | Rich status bar alerts with custom importance |
| **Device** | `device.sms` | Send SMS | Full | Direct cellular SMS transmission |
| **Device** | `device.clipboard` | Clipboard | Full | Read and write system clipboard |
| **Device** | `device.open` | Open Link / App | Full | Deep link and package intent launcher |
| **Device** | `device.speak` | Text to Speech | Full | Native Android TTS voice readout |
| **Device** | `device.vibrate` | Haptic Vibrate | Full | Custom waveform vibration patterns |
| **Device** | `device.file` | Save to File | Full | Sandboxed app-internal file storage |
| **Device** | `device.battery_status`| Battery Status | Full | Real-time level, charging, and temperature query |
| **Device** | `device.network_status`| Network Status | Full | Wi-Fi, cellular, and metered connectivity query |
| **SaaS** | `saas.http` | HTTP Request | Full | Universal REST client (GET, POST, PUT, DELETE) |
| **SaaS** | `saas.sheets` | Google Sheets | Partial | Static Bearer token supported; OAuth2 vault pending |
| **SaaS** | `saas.telegram` | Telegram Bot | Full | Native bot message dispatch |
| **SaaS** | `saas.slack` | Slack | Full | Webhook message dispatch |
| **SaaS** | `saas.discord` | Discord | Full | Webhook message dispatch |
| **SaaS** | `saas.weather` | Weather | Full | Free live weather query |
