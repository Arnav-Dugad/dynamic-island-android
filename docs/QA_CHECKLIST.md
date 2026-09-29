# Manual QA checklist: Galaxy S23+ (SM-S916*)

Run on the release build (`app-release.apk`) with One UI's default display settings
(FHD+, 120 Hz *Adaptive*), then repeat the ★ items at *Standard* (60 Hz) and with a non-default
font size. Tick each item; note the One UI version.

## 1. Install and onboarding
- [ ] Fresh install shows onboarding; the welcome preview animates smoothly.
- [ ] Overlay page: "Allow" opens *Appear on top*; status flips to Ready on return.
- [ ] Notification access page: grant works; if the toggle is greyed out, *App info → ⋮ → Allow restricted settings* unblocks it.
- [ ] Battery page: App info opens; status reflects *Unrestricted* after setting it.
- [ ] Calibration page shows a detected camera (source "Display cutout path").
- [ ] Try-it page: turning Island on shows the idle island around the camera; test buttons work.
- [ ] Finish lands on Home; relaunch does not show onboarding again.

## 2. Alignment ★
- [ ] Idle *Camera only*: over a white app (Contacts), the black disc is concentric with the lens, with no visible ring offset.
- [ ] Compact pill: lens appears vertically centred (or slightly high), with no lens edge poking out.
- [ ] Calibration: ±1 px steps move the island exactly one pixel; hold-to-repeat works; Reset returns to detection.
- [ ] Expanded card corners look concentric with the screen corners.
- [ ] Rotate to landscape: island hides; back to portrait: returns aligned.
- [ ] Change *Screen resolution* (HD+/FHD+/WQHD+ where offered): island re-aligns.

## 3. Motion ★
- [ ] Music → plug in charger → charging toast pops → returns to music without a jump.
- [ ] Rapid interrupt: start expanding, immediately swipe up; the shape reverses smoothly (no snap).
- [ ] Split: start a timer while music plays; the bubble buds off with a liquid neck; stopping the timer re-absorbs it.
- [ ] Every preset (Natural, Snappy, Soft, Elastic) feels distinct; Custom sliders apply live.
- [ ] Debug HUD shows ~120 fps while animating at 120 Hz and 0 frames while idle.
- [ ] *Remove animations* (Accessibility) makes transitions short and non-bouncy.

## 4. Gestures
- [ ] Long-press in the strip just below the status bar expands; haptic tick fires.
- [ ] Swipe down expands; swipe up collapses; swipe sideways dismisses a toast.
- [ ] Tap outside an expanded card collapses it.
- [ ] Press compresses the island toward the touched edge; drag stretches with rubber-band resistance.
- [ ] Haptics toggle disables all island haptics.
- [ ] Nothing below/around the island is blocked when idle (tap app toolbars under the status bar).

## 5. Media
- [ ] Spotify, YouTube Music, Samsung Music each appear with artwork and tinted equalizer.
- [ ] Expanded: previous/play-pause/next work; play↔pause morphs; seek bar scrubs.
- [ ] Tap opens the player. Pausing melts the equalizer; paused media hides after the timeout.
- [ ] Track change updates in place (no re-entrance). Two players: the playing one wins.

## 6. Notifications
- [ ] A message shows a banner with avatar, app badge, title and text; duration matches settings.
- [ ] Several messages from one chat merge ("3 new").
- [ ] Tap opens the conversation; swipe clears the notification.
- [ ] Silent/low-importance notifications and DND-suppressed ones do not appear.
- [ ] Privacy *App name only* / *Icon only* and per-app rules apply.
- [ ] While locked nothing is drawn; after unlock private notifications stay reduced.

## 7. Calls
- [ ] Incoming call auto-expands with name/avatar; Decline and Answer work (Samsung Phone).
- [ ] Swipe up collapses it to a ringing compact pill; notifications wait behind it.
- [ ] Ongoing call shows a green duration; tap returns to the call; Hang up works if offered.

## 8. Battery and charging
- [ ] Wired (25 W / 45 W) and wireless charging each show the right label; *Fast* only when measured.
- [ ] Each charging theme animates; Battery saver mode reduces particles.
- [ ] Low battery toast at the threshold; *Fully charged* once per plug-in; Power saving on/off toasts.
- [ ] *Protect battery* (85 %) shows "Charging paused", not "Charging".

## 9. Bluetooth, timers, system
- [ ] Galaxy Buds connect/disconnect toasts with battery (if reported).
- [ ] Timer: runs with Island closed; alarm sound + ringing card at zero; +1 min and Stop work from island and notification.
- [ ] Timer survives force-stop of Island and a reboot.
- [ ] Stopwatch start/lap/stop/reset from the island.
- [ ] Silent/Vibrate/Ring and DND toggles show toasts; rotation lock (if enabled).
- [ ] Screen recorder (One UI 7+ / Android 15): red recording indicator with timer.

## 10. Fullscreen, apps, Game Mode
- [ ] YouTube fullscreen video hides the island (default mode) and it returns after.
- [ ] A game hides the island with Game Mode on (Usage access granted); events still accumulate.
- [ ] Per-app *Hide completely* and *Important only* work.
- [ ] Banking app / system Settings: island hidden by Android (expected).

## 11. Lifecycle and battery
- [ ] Screen off: island disappears immediately (HUD confirms no frames). Unlock: blooms back from the camera.
- [ ] Swipe Island away from Recents: island keeps running.
- [ ] Reboot with *Start on boot*: island returns after first unlock.
- [ ] App update (reinstall): island returns without opening the app.
- [ ] Revoke overlay permission: service stops cleanly; re-grant and enable restores it.
- [ ] Overnight idle: Island's battery use in *Battery → usage* is negligible.

## 12. Accessibility
- [ ] TalkBack reads the island ("Playing: …") and offers Expand/Collapse/Dismiss/actions.
- [ ] Settings screens are fully navigable with TalkBack; font size 1.3× keeps layouts intact.
