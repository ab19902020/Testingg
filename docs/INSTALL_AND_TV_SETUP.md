# Install and TV setup

> Not yet tried on real hardware. These steps follow how Android handles APK installs and external displays,
> but no phone/TV combination has been tested with this app yet (see [TEST_REPORT.md](TEST_REPORT.md)).

## 1. What you need

* **Phone:** Android 10 or newer, 64-bit. Recommended: Snapdragon 8 Gen 3 or a chip of similar or better speed
  (for example Snapdragon 8 Elite, Dimensity 9300 / 9400, Exynos 2400 / 2500). The app also starts on slower
  phones but tracking may lag; the main menu warns when the chip is known to be below the target, and
  Settings -> Developer shows the detected chip.
* **USB-C video output on the phone.** Many phones, including some very fast ones, cannot send video over USB-C.
  Look for "DisplayPort Alt Mode", "USB-C video out", "DeX" or "Ready For" in the phone's specifications.
  If your phone can't, you can still play on the phone screen, or cast to the TV (adds noticeable delay).
* **USB-C to HDMI adapter** (ideally one with USB-C power pass-through so the phone charges while playing) and an
  HDMI cable.
* **A stand** that holds the phone steady in landscape at about chest height.
* **Space:** about 3 m from the phone to the players and enough width for two people standing side by side an
  arm's length apart.

## 2. Install the APK

1. Copy `ChristmasLivingRoomGames-debug.apk` to the phone (or download it from the repository's `dist/` folder).
2. Open it from the Files app. Android asks you to allow installs from that app once ("Install unknown apps").
3. Tap **Install**. Google Play Protect may warn because the app is debug-signed and not from the Play Store;
   choose to install anyway if you trust this build. Check the SHA-256 against `dist/SHA256SUMS` if you want to
   be sure the file is the one that was built.

With a computer: `adb install -r ChristmasLivingRoomGames-debug.apk` (USB debugging on).

The app needs **only the camera permission**. It has no internet permission at all.

## 3. Connect the TV

1. Plug the adapter into the phone and the HDMI cable into the TV. Pick that HDMI input on the TV.
2. Open the app. The first setup screen shows whether a TV was found, its resolution and refresh rate.
3. Two ways the picture can reach the TV, chosen automatically:
   * **Second screen (preferred):** the game is drawn on the TV and the phone shows the camera view with large
     touch buttons (arrows, OK, Back/Pause). This happens when Android offers the TV as a separate display.
   * **Mirroring:** the TV shows a copy of the phone screen. The game is drawn for any screen shape, so this also
     works. Some phones (for example Samsung with DeX) offer a choice when the cable is plugged in - pick
     **screen mirroring**, not the DeX desktop.
   You can switch the second-screen behaviour off in **Settings -> Camera & TV**.
4. If the cable is pulled out during a game, the game pauses and says "TV disconnected". Plug it back in, or
   press **Continue** to carry on on the phone screen.

Tip: turn on the TV's **Game mode** to cut the TV's own picture delay.

## 4. Place the phone

* Landscape, steady, near the TV, camera facing the players, about chest height.
* Rear camera gives the best picture but you then cannot see the phone screen - that is fine when the TV shows the
  game. The front camera works too. Choose in the setup screen or **Settings -> Camera & TV**; "Wide angle"
  zooms out on cameras that support it.
* Light the room well. Avoid a bright window or lamp behind the players.

## 5. First-time setup in the app

1. **Camera permission** - the app explains why it needs the camera (processed on the phone, never recorded or
   uploaded) before Android asks.
2. **Framing** - the TV shows the camera picture with two outlines. Player 1 stands in the left outline,
   Player 2 in the right. Each player's status line turns green when they are found. Once both players are
   locked in, other people in the picture are ignored.
3. **Calibration** (both players at the same time, a few seconds per step): stand still; arms out wide; reach
   up; raise the hand you want to play with. Only move as far as is comfortable - the game scales every movement to your own
   size and reach. "Skip" uses default values.
4. **Start** - both players raise a hand and hold it up until the ring fills.

Menus can be used hands-free: after calibration, raise a hand to show your cursor, move it onto a button and hold
it there until the ring fills (1.5 s by default, adjustable). Touch, a remote's D-pad or a keyboard also work.

## 6. Problems

| Problem | What to try |
|---|---|
| TV stays black | Check the TV input. Try another adapter/cable. Check the phone supports USB-C video output. |
| "Not every phone can send video over USB-C" stays on screen | The phone does not report an external display: play on the phone or cast. |
| A player is not found | Step back so your whole upper body and hips are in the picture; improve lighting; stay inside your outline. |
| Players get swapped | Recalibrate (pause menu). Wear different-coloured tops; keep an arm's length apart; don't cross over. |
| Game pauses "we lost track of someone" | The named player steps back into their area and stands still; play resumes after a 3-second countdown. |
| Movements not recognised | Recalibrate; try **Settings -> Accessibility -> Gesture sensitivity**; choose Reduced movement or Seated for that player. |
| Lag | Settings -> Camera & TV -> Tracking quality -> Performance. Let the phone cool down; avoid a phone case while charging. |
