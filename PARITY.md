# KaivoFlow — Capability & Node Parity Matrix

> **Tracking node availability, mobile implementation status, and mobile-native improvements.**
> Current `NodeCatalog`: **87 runnable executors** — 12 Sparks, 17 Logic, 25 Transform, 3 AI, 12 SaaS, 18 Device.
> Every expanded Step is an original KaivoFlow implementation; no external node code, descriptions, icons or schemas were copied.

---

## Original catalog

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
| **Logic** | `logic.dedupe` | Deduplicate | Full | Stable fingerprint-based dedupe |
| **Logic** | `logic.loop` | Loop / Batch | Full | Item-by-item chunking for rate limits |
| **Logic** | `logic.wait` | Wait / Delay | Full | Non-blocking coroutine sleep with optional jitter |
| **Logic** | `logic.stop` | Stop | Full | Early termination with status reporting |
| **Logic** | `logic.onError` | Error Handler | Full | Dedicated error branch routing |
| **Transform** | `transform.set` | Set Fields | Full | Visual key-value field editor with expressions |
| **Transform** | `transform.fields` | Rename/Remove | Full | Bulk rename and drop fields |
| **Transform** | `transform.json` | JSON Parse/Build | Full | Sandboxed non-throwing JSON parser |
| **Transform** | `transform.template` | Write Text | Full | Multi-line text interpolator |
| **Transform** | `transform.date` | Date & Time | Full | Parse, format, shift, difference and IANA time-zone conversion |
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
| **SaaS** | `saas.http` | HTTP Request | Full | Universal REST client (GET, POST, PUT, PATCH, DELETE) |
| **SaaS** | `saas.sheets` | Google Sheets | Full | Static keys and refreshed connected-account access tokens |
| **SaaS** | `saas.telegram` | Telegram Bot | Full | Native bot message dispatch |
| **SaaS** | `saas.slack` | Slack | Full | Webhook message dispatch |
| **SaaS** | `saas.discord` | Discord | Full | Webhook message dispatch |
| **SaaS** | `saas.weather` | Weather | Full | Free live weather query |

---

## Mobile-native Sparks added

| Node ID | Title | Status | Mobile advantage |
|---|---|---|---|
| `trigger.geofence` | Place boundary | Full | `LocationManager` proximity alerts with radius and enter/exit filtering |
| `trigger.bluetooth` | Bluetooth device | Full | Connection/disconnection broadcasts with address and name filters |
| `trigger.wifi` | Wi-Fi network | Full | Network-name filters, rejects unknown SSIDs, event-aware matching |
| `trigger.headset` | Headset plug | Full | Wired headset and microphone events without a cloud listener |
| `trigger.boot` | Phone restarted | Full | Re-arms Flows after reboot and app/time changes |

All five Sparks use fail-closed matching: an event without its identifying fields never starts a Run.

---

## Second catalog expansion

| Category | Node ID | Title | Status | Mobile advantage |
|---|---|---|---|---|
| Logic | `logic.sort` | Sort items | Full | Stable numeric, text and date ordering entirely on-device |
| Logic | `logic.limit` | Limit items | Full | First/last/skip/slice/sample without extra items |
| Logic | `logic.compare` | Compare lists | Full | Added/removed/changed/same diff with fuzzy text matching |
| Logic | `logic.table` | Local table | Full | Private app-internal file-backed key/value rows between Runs |
| Logic | `logic.run_info` | Run details | Full | Run id, time, mode, attempt and item count for logs |
| Logic | `logic.noop` | Pass through | Full | Annotation/debug marker with zero mutation |
| Transform | `transform.math` | Math | Full | 20 numeric operations with zero-division and non-finite guards |
| Transform | `transform.text` | Text tools | Full | Case, slug, regex, split/join, URL/HTML/base64 helpers |
| Transform | `transform.stats` | Number summary | Full | Count, sum, min, max, mean, median, variance and deviation |
| Transform | `transform.split` | Split items | Full | Array field, text separator or whole-list batching |
| Transform | `transform.url` | URL tools | Full | Parse or build links with encoded query parameters |
| Transform | `transform.csv` | CSV | Full | Quoted-field reader and writer with embedded newlines |
| Transform | `transform.xml` | XML | Full | Bounded attribute/text/child parser and serializer |
| Transform | `transform.html` | HTML tools | Full | Readable text, links, images, titles and attribute extraction |
| Transform | `transform.markdown` | Markdown | Full | Headings, lists, links, emphasis, code and plain-text output |
| Transform | `transform.ical` | Calendar invite | Full | Standards-shaped `.ics` event text with escaped fields |
| Transform | `transform.binary` | Binary tools | Full | Base64/text ↔ attachment, gzip, ZIP, SHA-256 and metadata |
| Transform | `transform.binary_upload` | Upload a file | Full | Multipart/form-data upload with extra form fields |
| Transform | `transform.chart` | Quick chart | Full | Bar/line/area SVG attachment with no chart SDK |
| Transform | `transform.random` | Random value | Full | Secure UUID, number, string, pick and shuffle |
| Transform | `transform.token` | Token tools | Full | Compact-token decode, HMAC verify and sign with exp/nbf checks |
| Transform | `transform.totp` | One-time code | Full | Base32 secret, SHA1/256/512, 6–8 digits, clock window |
| Transform | `transform.pdf` | PDF text | Full | Best-effort text-layer extraction and page/size metadata |
| SaaS | `saas.rss` | Feed reader | Full | RSS/Atom feed entries as ordinary items |
| SaaS | `saas.graphql` | GraphQL | Full | Query, variables, operation name and readable API errors |
| SaaS | `saas.webdav` | File store request | Full | GET/PUT/PROPFIND/MKCOL/DELETE/MOVE/COPY with binary downloads |
| SaaS | `saas.tcp` | Socket message | Full | Bounded raw TCP/TLS conversation for on-premise devices |
| Device | `device.clipboard_read` | Read clipboard | Full | Uses the text already copied on the phone |
| Device | `device.toast` | Quick message | Full | Short on-screen confirmation without a notification |
| Device | `device.share` | Share sheet | Full | Hands text to another local app |
| Device | `device.torch` | Torch | Full | Camera flash on/off/toggle with capability check |
| Device | `device.volume` | Volume | Full | Percentage mapped to the device's real media stream range |
| Device | `device.location` | Current location | Full | Last known coordinates with permission-aware failure text |
| Device | `device.wifi_info` | Wi-Fi details | Full | Current SSID, signal, link speed and connection state |
| Device | `device.contact` | Find a contact | Full | Local address-book lookup by name or number |
| Device | `device.calendar_event` | Add calendar event | Full | Writes a reminder into the phone calendar |
| Logic | `logic.subflow` | Run another Flow | Full | Reuse a saved Flow, pass items in, get its terminal items back, depth-capped |
| Logic | `logic.poll` | Only new items | Full | Poll an API, hash/ID-dedupe against a private local state file between Runs |
| SaaS | `saas.connector` | Service connector | Full | Arbitrary REST call with credential auth, dot-path extraction, pagination, retries |

---

## Cross-cutting platform capabilities

| Capability | Status | Notes |
|---|---|---|
| First-class binary attachments | Full | `BinaryDataRef` with MIME, file name, size, checksum and internal URI; 25 MB cap |
| Multipart/base64 HTTP transport | Full | Hand-built multipart body, bounded downloads, transport-error mapping |
| Native `.kaivoflow` full backup | Full | Secret redaction, ID remap, graph validation, dual-format import UI |
| Connected-account token refresh | Full | Encrypted vault, per-credential mutex, expiry skew, rotated refresh tokens |
| Per-output port Stream gesture | Full | 48 dp targets, dashed bezier preview, success haptic, branch-aware chaining |
| Density-correct canvas coordinates | Full | Single `pixelScale` at every dp↔px boundary for cards, ports, paths and drag deltas |
| Sub-Flow composition | Full | `FlowInvoker` + `RunTrigger(kind="subflow")`; nested Runs are not persisted as top-level history |
| Generic service connector | Full | Original response-path/pagination/retry engine covers many API integrations without copied presets |
| Polling change detection | Full | Bounded local state file remembers item IDs/hashes across Runs |
| Starter Flow templates | Full | Five editable original templates with fresh Step/Stream IDs and a picker in Flow List |
