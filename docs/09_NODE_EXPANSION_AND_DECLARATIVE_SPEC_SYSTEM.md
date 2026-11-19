# 09. Node Expansion & Declarative Spec System — Scaling to 300+ Nodes

> **Target Platform:** KaivoFlow (Android Local-First Automation Engine)  
> **Language:** Hinglish (Senior Architect Deep-Dive)  
> **Topic:** Hazaaron lines ka boilerplate Kotlin code likhe bina 300+ nodes kaise expand karein, Declarative HTTP Node Engine, aur Top 30 High-Impact Nodes list.

---

## 1. Problem: Har Ek Node Ke Liye Kotlin File Likhna Unsustainable Hai

Abhi KaivoFlow me agar hume Telegram, Discord, Slack, GitHub, Notion, Stripe, Airtable, Google Sheets ke liye alag-alag `.kt` files likhni padein:
- Ek HTTP API integration me 10 se 20 actions hote hain (Create issue, Get issue, Update issue, Add comment, Delete...).
- 300 nodes $\times$ 15 actions = **4,500 functions**!
- Itna code likhne me saalon lag jayenge aur APK size 200MB+ ho jayega.

### Reference System Ka Solution: Declarative Node System
Reference system (`packages/nodes-base`) me zyadatar REST integrations **Declarative Specifications** hoti hain.
Ek standard JSON/TS file describe karti hai:
1. `name`: "GitHub"
2. `credentials`: `["githubApi", "githubOAuth2"]`
3. `baseUrl`: `"https://api.github.com"`
4. `properties`: Dropdown list of resources (`issue`, `repo`, `pullRequest`) aur operations (`create`, `get`, `list`).
5. `routing`: Request mapper:
   ```json
   {
     "operation": "create",
     "request": {
       "method": "POST",
       "url": "=/repos/{{$parameter.owner}}/{{$parameter.repo}}/issues",
       "body": {
         "title": "={{$parameter.title}}",
         "body": "={{$parameter.body}}"
       }
     }
   }
   ```
Engine is JSON spec ko read karke automatically:
- Canvas UI me dynamic form render karta hai.
- User input par expressions evaluate karta hai.
- Universal `HttpGateway` se HTTP call execute karta hai aur response parse karke emit karta hai!
**Zero custom code required!**

---

## 2. KaivoFlow Ke Liye `DeclarativeNodeEngine` Blueprint

Hum `:core:nodes` mein ek universal **Declarative Node Executor** implement karenge:

```kotlin
data class DeclarativeNodeSpec(
    val id: String,                    // e.g. "integration.telegram"
    val displayName: String,           // "Telegram"
    val icon: String,                  // "telegram"
    val category: NodeCategory,        // Communication
    val credentialType: String?,       // "telegram_bot_token"
    val baseUrl: String,               // "https://api.telegram.org/bot{apiKey}"
    val resources: List<NodeResourceSpec>,
)

data class NodeResourceSpec(
    val name: String,                  // "message", "chat"
    val operations: List<NodeOperationSpec>,
)

data class NodeOperationSpec(
    val id: String,                    // "sendMessage"
    val displayName: String,           // "Send Message"
    val httpMethod: String,            // "POST"
    val endpointPath: String,          // "/sendMessage"
    val parameters: List<ParameterFieldSpec>,
    val bodyTemplate: Map<String, String>, // {"chat_id": "{{$param.chatId}}", "text": "{{$param.text}}"}
)
```

### Iska Fayda:
1. Naya integration add karna = Sirf ek JSON/Kotlin config file add karna (15 minutes ka kaam!).
2. Community ya users naye nodes bina app recompile kiye import/share kar sakte hain!
3. APK size lightweight rahega kyunki logic ek common execution engine par chalega.

---

## 3. Top 30 High-Impact Nodes Jo KaivoFlow Ko Million-Dollar Banayenge

Users ke real-world use cases ke hisaab se sabse zaroori 30 nodes ko 5 categories mein divide kiya gaya hai:

### Category 1: Communication & Messaging (Daily Automation)
1. **Telegram** (Bot API: Send text, photo, audio, buttons, receive messages)
2. **Discord** (Webhooks & Bot: Send rich embeds, channel notifications)
3. **Slack** (Post message, blocks, channel alerts)
4. **WhatsApp Cloud / Gupshup** (Send template messages, alerts)
5. **Gmail / Email (SMTP/IMAP)** (Send emails, trigger on incoming unread mail)
6. **Twilio** (Send SMS, WhatsApp messages, voice alerts)

### Category 2: Databases & Storage (Data Persistence)
7. **PostgreSQL** (Execute SQL query, insert, update)
8. **MySQL** (Execute SQL query)
9. **MongoDB** (Insert document, find, aggregate)
10. **Redis** (Get, set, publish/subscribe, cache)
11. **Supabase** (Database CRUD, auth events)
12. **AWS S3 / Cloudflare R2** (Upload files, download buckets)

### Category 3: Productivity & Project Management
13. **Google Sheets** (Append row, read range, update cells)
14. **Google Drive** (Upload file, create folder, search)
15. **Notion** (Add page, update database item, query)
16. **Airtable** (List records, create record)
17. **GitHub** (Create issue, trigger on commit/PR, star alert)
18. **Trello / ClickUp** (Create task, move card)

### Category 4: E-Commerce & Payments
19. **Stripe** (Trigger on payment_intent.succeeded, create customer, invoice)
20. **Shopify** (Trigger on order created, update inventory)
21. **PayPal** (Capture order, invoice created)
22. **Razorpay** (Payment authorized trigger, refund)

### Category 5: Utilities & Data Transformation (The Glue)
23. **Crypto** (MD5, SHA-256, HMAC signature generation for webhook security)
24. **HTML Extract** (Cheerio-style web scraper: extract text, links, tables)
25. **Markdown to HTML / Text** (Format conversion for messages)
26. **Compression (Zip/Unzip)** (Compress logs/files before upload)
27. **XML to JSON / JSON to XML** (Legacy API adapter)
28. **RSS Feed Read** (Trigger on new blog/news item)
29. **Form Trigger** (Generate web form to trigger workflow with human input)
30. **Webhook Wait** (Pause flow until external callback arrives)

---

## 4. Implementation Priority

- **Step 1:** Build the `DeclarativeHttpStep` runtime engine (`:core:nodes`).
- **Step 2:** Seed the top 5 most demanded nodes (`Telegram`, `Discord`, `Google Sheets`, `GitHub`, `Crypto/Webhook Wait`).
- **Step 3:** Enable Ask AI Assistant to automatically suggest and configure these nodes using their declarative schemas.
