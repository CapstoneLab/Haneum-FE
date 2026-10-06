# HAN2 cross-app send guard

## Files

- `NativeKeyboardService.kt`: custom IME, current draft tracking, local analysis, warning UI.
- `PrivacyDetector.kt`: detector interface and regex implementation. It returns types and ranges, not a copy of the input.
- `RiskAnalyzer.kt`: maps privacy findings and fraud context to SAFE/WARNING/DANGER.
- `RiskStateStore.kt`: shares only draft existence, risk level, target package and timestamp. Raw draft text is not persisted.
- `SendButtonDetector.kt`: generic detector plus per-app `AppAdapter` implementations.
- `RiskSendGuardService.kt`: node-tree scanning, bounds-based accessibility overlay, confirmation UI, click and gesture fallback.
- `risk_send_guard_service.xml`: event scope, window retrieval and gesture capability.
- `AndroidManifest.xml`: IME and accessibility service declarations.

## Runtime flow

1. The IME reads text through its own `InputConnection` and runs the local detector.
2. It shares metadata only and broadcasts an explicit, package-scoped state update.
3. While a non-empty draft exists, the accessibility service scans the active window.
4. The service first uses a package-specific adapter, then generic text/content-description/view-id matching.
5. It gets `boundsInScreen` from the node and places a `TYPE_ACCESSIBILITY_OVERLAY` touch target over that rectangle.
6. On touch, a safe draft is sent through `ACTION_CLICK`. A suspected draft shows confirmation first.
7. If `ACTION_CLICK` returns false, a gesture is dispatched at the last known bounds center.
8. A 1.5-second one-shot bypass window prevents the synthetic click from being intercepted again.
9. Window/content/focus/text events recalculate the target position. Interrupt and destroy remove all overlays.

## Detector extension

`PrivacyDetector` is a functional interface. A future on-device model can implement it and replace
`RegexPrivacyDetector` without changing the IME or accessibility service. The current rules detect
email, Korean phone-number formats, resident-registration-number shapes, card-number shapes,
account numbers with account context, and authentication codes with authentication context.

Regex matches are warnings, not proof that data is personal information. Production rules should
add checksums (for example Luhn for card numbers), tenant-specific allowlists, and false-positive tests.

## Android limitations

- The user must enable the accessibility service manually. Apps cannot grant this capability themselves.
- `canRetrieveWindowContent=true` is required for node inspection.
- `canPerformGestures=true` is required for `dispatchGesture` fallback.
- Accessibility overlays do not require `SYSTEM_ALERT_WINDOW`, but only an enabled accessibility service can create them.
- A node can become stale between discovery and click. The implementation retains its last bounds for gesture fallback.
- Custom Canvas/OpenGL surfaces, protected windows and some WebViews may expose no useful node, ID or clickable action.
- Some apps replace or move the send button without a usable accessibility event. Polling is intentionally avoided;
  adapters and relevant window/content events are preferred to reduce battery and privacy impact.
- Gesture fallback is coordinate-based only after obtaining dynamic node bounds. It cannot help when no candidate node
  is exposed at all. A supported-app allowlist or share-sheet flow is the safer fallback for those apps.
- No accessibility solution can guarantee interception in every app. Apps may intentionally restrict accessibility,
  use nonstandard send gestures, hardware keys, notifications, voice input or server-side auto-send behavior.

## Google Play policy

- Accessibility use must be narrowly scoped and documented in the Play listing and Play Console declaration.
- Unless the product genuinely has disability assistance as its primary purpose, do not set
  `isAccessibilityTool=true`.
- Show a separate prominent in-app disclosure before opening system settings and require affirmative consent.
- The disclosure must state what screen data is accessed, why it is used and whether it is stored or shared.
- Provide the declaration review video showing consent, enablement, denial and the core feature.
- Do not silently change settings, bypass platform privacy controls or perform autonomous actions unrelated to the
  user's explicit send attempt.

References:

- Android accessibility service guide: https://developer.android.com/guide/topics/ui/accessibility/service
- Android `AccessibilityService` API: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Google Play Accessibility API policy: https://support.google.com/googleplay/android-developer/answer/10964491
- Google Play prominent disclosure guidance: https://support.google.com/googleplay/android-developer/answer/11150561
