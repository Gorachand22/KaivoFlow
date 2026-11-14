# KaivoFlow — Engineering Backlog & Task Priorities

> **Tracking issues, technical debt, and feature enhancements by severity.**

---

## Priority 0 (Blockers & Reliability)
- [x] **P0-01:** Build compilation failure (toolchain version mismatch, unresolved imports). -> *FIXED*
- [x] **P0-02:** Trigger emission: manual and scheduled runs pass empty lists, bypassing downstream nodes. -> *FIXED*
- [x] **P0-03:** Scheduled flows lack recurring execution loop after worker completion. -> *FIXED*
- [x] **P0-04:** Event trigger dispatch ignores package/sender/keyword filters. -> *FIXED*

---

## Priority 1 (Core Functional Parity)
- [ ] **P1-01: First-Class Binary Data Model**  
  *Add `binary: Map<String, BinaryItem>` to `FlowData` with Android SAF (`content://` URI) support for photos, documents, and audio.*
- [ ] **P1-02: Canvas Touch-Linking Drag & Drop**  
  *Allow dragging directly from a node output circle to another node input circle to establish stream links with haptic feedback.*
- [ ] **P1-03: Parameter-Preserving Native Flow Export/Import**  
  *Enable full flow backup (`.kaivoflow`) that keeps URLs, templates, and condition rules intact while redacting sensitive API keys.*
- [ ] **P1-04: Lightweight OAuth2 Token Manager**  
  *Custom tabs OAuth2 receiver for SaaS integrations with auto-refresh token rotation.*

---

## Priority 2 (UI & Polish)
- [ ] **P2-01: Font Scale & Insets Audit**  
  *Verify all screens at 1.3x and 2.0x font scaling to ensure zero text clipping.*
- [ ] **P2-02: Card Drag Coordinate Snapping**  
  *Ensure silky smooth world-to-screen coordinate mapping during simultaneous pan and pinch gestures.*
