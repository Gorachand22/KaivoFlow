# 06. Credentials, Security & Sovereign Vault — Gap Analysis & Missing Features

> **Document Type:** Sovereign Architecture & Feature Gap Audit  
> **Language:** Hinglish (Developer Action Guide)  
> **Objective:** Reference repo (`packages/core/src/encryption`, `credentials`) ke mukable hamare KaivoFlow (`:core:data`, `:feature:flows`) ke credential & security layer mein kya features missing hain aur kal hume kya implement karna hai.

---

## 1. Summary: Abhi Hamare Paas Kya Hai vs Reference System

| Security / Vault Feature | Reference System (`packages/core/src/encryption`) | KaivoFlow Aaj Ka Status | Gap Severity |
|---|---|---|---|
| **Hardware-Backed Secret Encryption** | AES-256-GCM instance key encryption for all saved credentials | SQLite / SharedPreferences text storage; Android Keystore encryption partial | 🔴 High |
| **"Test Connection" on Credentials** | Har credential type me built-in test URL hota hai jo credentials save karne se pehle ping karta hai | Sirf AI provider config par discovery ping hoti hai; baki credentials untested save hote hain | 🟡 Medium |
| **Domain Restrictions / Allowlist** | SSRF prevention: HTTP node se local network (`192.168.*`, `10.*`, `127.0.0.1`) par requests block/allow karne ka toggle | Missing | 🟡 Medium |
| **OAuth2 Flow with Local Callback** | Automated OAuth2 flow: opens browser, catches callback code on localhost, exchanges refresh token | Manual API Key / Bearer token entry only; in-app OAuth2 missing | 🟡 Medium |
| **Credential Auto-Fill in Nodes** | Jab user HTTP ya AI node add karta hai, compatible saved credentials dropdown me auto-filter hote hain | Present in AI assistant; Canvas step parameter me manual selection hai | 🟢 Low |

---

## 2. Deep Dive: Kya Features Missing Hain (Detail Wise)

### A. Android Hardware-Backed KeyStore Encryption (AES-256-GCM)
- **Reference System:**
  - Database me API keys ya passwords kabhi bhi plain text me save nahi hote.
  - Har string `aes-256-gcm:iv:authTag:ciphertext` format me encrypted rehti hai.
- **KaivoFlow Ka Implementation Plan:**
  - Android me hamare paas **Android KeyStore** ka native hardware chip (TEE / StrongBox) hota hai.
  - Hume Room database ke credential fields ko hardware key se transparently encrypt/decrypt karna hai. Agar phone root bhi ho ya backup dump liya jaye, secrets extract na ho sakein.

### B. "Test Connection" Button for All Credentials
- **Reference System:**
  - Jab user naya credential create karta hai (e.g. OpenAI, Telegram, Webhook, Postgres, etc.):
  - Modal par ek button hota hai: **"Test Connection"**.
  - System test API hit karta hai:
    - Success par: `Connection tested successfully` (Green badge)
    - Error par: `Authentication failed (HTTP 401): Invalid API Key` (Red badge with troubleshooting link).
- **KaivoFlow Mein Missing:**
  - Abhi user galat API key save kar leta hai aur baad me workflow crash hone par hairan hota hai.

---

## 3. Action Items (Kal Kya Implement Karna Hai)

1. **`HardwareVault` Keystore Integration (`:core:data`):**
   - Android Keystore AES-256-GCM cipher wrapper for all credential secrets.
2. **Credential "Test Connection" Button (`:feature:flows`):**
   - Har credential card par instant verification test action.
3. **SSRF Guard in `HttpGateway` (`:core:network`):**
   - Private IP block toggle (local intranet protection).
