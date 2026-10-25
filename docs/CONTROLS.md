# Controls guide

All games are played by two people at once: **Player 1 on the left, Player 2 on the right** (as seen on the TV).
Each player controls only their own side with their own body. Movements are measured relative to each player's
calibrated body size and comfortable reach, so a child and an adult need the same *relative* effort.

Nothing in any game asks you to run, jump, cross over to the other player, crouch deeply or touch anyone.

> These controls are implemented and covered by automated tests that feed in synthetic body movements. They have
> **not yet been tried with real people in front of a real camera** - thresholds may need tuning after the first
> on-device tests (see [TEST_REPORT.md](TEST_REPORT.md)).

## Everywhere

| Action | How |
|---|---|
| Start a match | On the instructions card, **both players raise a hand and hold it up** until their ring fills. |
| Hands-free menus | After calibration, raise a hand above your waist: your coloured cursor appears. Move it onto a button and **hold still** until the ring fills (1.5 s, adjustable 0.8-3 s). After a selection, cursors lock briefly and must leave the button before it can be chosen again, to stop accidental double selections. |
| Touch / remote | Every menu also works by touch on the phone, a TV remote / game controller D-pad (if connected) or a keyboard. When the game is on the TV as a second screen, the phone shows large arrow / OK / Back buttons. |
| Pause | Back (phone gesture/button, remote), the **II** button, or the phone's **Back / Pause** button. Hands-free: one player steps out of view - the game pauses automatically. |
| Someone steps out of view | The game pauses and names the missing player. When they are back in their own area and standing still, a 3-second countdown resumes play. Scores and timers are frozen while paused. |
| Pause menu | Resume, Restart match, Recalibrate players, Quit. |
| After a match | Rematch, Choose another game, Main menu (or Continue tournament). |

**Left-handed players:** during calibration, raise the hand you want to play with. Games that use one "playing
hand" (table tennis forehand/backhand) follow that choice; games that accept either hand accept both.

**Movement modes (per player, on the Players screen or during calibration):**

* **Standard** - normal movements.
* **Reduced movement** - smaller movements count; swings, throws and pumps need less distance and speed.
* **Seated** - for playing from a chair: hips need not be visible, no low targets, "hands up" is measured from
  the shoulders, bowling swings go from lap to chest. Steering games use a gentle upper-body lean.

**Settings -> Accessibility:** gesture sensitivity (0.5-2x), hands-free select time, high contrast, larger
text, reduced effects (less shake/flashing/confetti), captions for sound effects (on by default), and
"Sleigh Slalom: steering only".

---

## 1. Christmas Tennis - first to 7 points

Your snow buddy runs to the ball for you; you only swing.

| Movement | Action |
|---|---|
| Swing either arm across your body | Hit the ball |
| Watch the shrinking ring around your character | Swing as it turns green |

On time = fast, deep shot. Far too early = out. Far too late = net. Serve by swinging when it's your turn. Rallies
get gradually harder (the timing window shrinks with every hit) so points always end.

Reduced movement / seated: a smaller, slower arm swing counts.

## 2. Snowball Showdown - most hits in 60 seconds

| Movement | Action |
|---|---|
| Hand up by your shoulder, then throw forward (overhand) | Throw a snowball at the opponent's current position |
| Lean left / right | Dodge inside your own fort area |
| Both hands up high | Shield (3 per player) |

Reduced movement / seated: a short forward push from chest height throws; small leans move you.

## 3. Present Catch - highest score in 60 seconds

| Movement | Action |
|---|---|
| Reach left, right, up or overhead with either hand | Touch a present while its ring shows |
| Keep hands away from coal (red X) | Avoid a penalty |

Gold presents are worth extra. Both players get the same presents in their own lane. Spots are placed inside each
player's own calibrated reach; seated players get no low spots.

## 4. Reindeer Racing - first over the finish line

| Movement | Action |
|---|---|
| Pump your arms up and down, one after the other (like running, but standing still) | Gallop |
| Lean left / right | Steer around snowmen, logs and ice |

A steady rhythm is fastest; bigger movements are not faster. Carrots give a speed boost.
Reduced movement / seated: small arm pumps at a gentle rhythm reach full speed.

## 5. Festive Bowling - highest total after 5 frames

| Movement | Action |
|---|---|
| Lean left / right | Choose where you stand |
| Underarm swing: arm down and back, then forward and up | Roll the ball |
| Swing slightly sideways | Angle the ball a little |

Scoring (simplified): each pin 1 point, strike 15, spare 13. Each player bowls in their own lane at their own pace.
Reduced movement: a small low-to-chest swing rolls the ball. Seated: swing from your lap up to your chest.

## 6. Table Tennis - first to 11 points

| Movement | Action |
|---|---|
| Small quick forehand: playing hand sweeps across from its own side | Return FOREHAND balls |
| Small quick backhand: playing hand sweeps the other way | Return BACKHAND balls |

Each incoming ball shows FOREHAND or BACKHAND. The wrong stroke gives a weak pop-up that the opponent can smash.
Smaller swing thresholds than tennis. Reduced movement / seated: tiny wrist-and-forearm strokes are enough.

## 7. Elf Dance-Off - highest dance score

| Movement | Action |
|---|---|
| Copy the arm pose on the card as it reaches NOW | Score the pose |
| Hold it on the beat | Timing bonus |

Your snow buddy copies your pose so you can check it. Reduced movement / seated: a gentler pose set with nothing
overhead and more tolerance.

## 8. Sleigh Slalom - fastest time

| Movement | Action |
|---|---|
| Lean left / right | Steer |
| Hands together in front of your chest | Tuck for speed |

Pass between every pair of flags; a missed gate adds 2 seconds. Presents take 0.5 seconds off; trees and rocks stop
you for a moment. **Steering-only mode** (Settings -> Accessibility): no tuck, speed is automatic for both.

## 9. Christmas Goalkeepers - most saves

| Movement | Action |
|---|---|
| Reach a hand to the glowing target spot before the ball arrives | Save |

Both goals get exactly the same shots, three short rounds, each a bit faster. Chain saves for a streak. Shots are
placed inside each player's calibrated reach; seated players get no low shots.

## 10. Decorate the Tree - first to finish

| Movement | Action |
|---|---|
| Hold a hand still over an ornament in your tray until the ring fills | Pick it up |
| Carry it to the glowing spot on your tree and hold still until the ring fills | Hang it |

Ten decorations; the last one is the star on top. Entirely dwell-based (no fast movements). All spots sit inside
each player's calibrated reach; seated players get no low spots.
