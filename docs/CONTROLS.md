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
| Left/right check | The first time someone raises a hand in front of a camera, slide that hand towards the arrow on the right, then the one on the left. If movements went the wrong way, the picture is flipped for that camera (and remembered). Also: **Flip left/right** on the "Stand in your areas" screen, **Check left/right** in Settings -> Camera & TV. |
| Hands-free menus | Stand in front of the camera (no set-up needed) and raise a hand above your waist: your coloured cursor appears. Buttons are slightly magnetic: a hand just off a button still points at it. Move it onto a button and **hold still** until the ring fills (1.5 s, adjustable 0.8-3 s). After a selection, cursors lock briefly and must leave the button before it can be chosen again, to stop accidental double selections. |
| Touch / remote | Every menu also works by touch on the phone, a TV remote / game controller D-pad (if connected) or a keyboard. When the game is on the TV as a second screen, the phone shows large arrow / OK / Back buttons. |
| Pause | Back (phone gesture/button, remote), the **II** button, or the phone's **Back / Pause** button. Hands-free: one player steps out of view - the game pauses automatically. |
| Someone steps out of view | The game pauses and names the missing player. When they are back in their own area and standing still, a 3-second countdown resumes play. Scores and timers are frozen while paused. |
| Pause menu | Resume, Restart match, Recalibrate players, Switch to front/back camera, Quit. |
| Front or back camera | Tap the camera button at the top left of the main menu, the **Use back/front camera** button on the "Stand in your areas" screen, or **Switch to ... camera** in the pause menu. After switching mid-game everyone checks their position again, then the match carries on. The choice is remembered. |
| Timing test | **Settings -> Timing -> Run the timing test**: a snowball flies into a ring ten times; swing like a tennis shot as each one reaches the ring *on the TV*. The game measures your phone + TV delay and allows for it in the timing games. If swings still feel judged late, raise the allowance a little on the same page; early, lower it. |
| Tracking meter | On the "Stand in your areas" screen, each player's bars (Excellent, Good, Fair, Weak) show how well the camera sees them, with a tip when something would help (come closer, step back, more light, keep both hands in the picture). |
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

## Play Solo (one person against the computer)

Main menu -> **Play Solo** -> pick who is playing and the opponent -> **Choose a game**. Or, after choosing a game in
Quick Match, press **Just me** (uses the level you picked last time, Medium at first).

| Opponent | Level | What to expect |
|---|---|---|
| Sprout | Easy | Slow to react and makes plenty of mistakes. |
| Jingles | Medium | A fair match for most players. |
| Blitz | Hard | Quick and accurate - a real challenge. |

* You are Player 1 (left side of the TV); the computer is Player 2 (right side, marked **CPU**).
* Stand in the **middle of the picture**. Setup, calibration and "raise a hand to start" are for you alone; the
  computer is always ready.
* Your controls are exactly the same as in a two-player game (below).
* If you step out of view the game pauses for you; the computer never causes a pause.
* The computer sees only what you see on screen, reacts after a human-like delay, moves at a human speed and
  makes mistakes, so it can be beaten. The difficulty levels were checked by playing them against each other in
  automated tests; how they feel against real players still needs trying out (see [TEST_REPORT.md](TEST_REPORT.md)).
* After a solo match: Rematch, Choose another game (still against the computer) or Main menu. Quick Match and the
  Party Tournament are for two people and switch solo mode off.

---

## Christmas Golf - 1 to 4 players, taking turns

Only the player whose turn it is stands in front of the camera; everyone else stands back. When your name comes up,
step in, stand still a moment and **raise a hand** to start your turn. You play the whole hole, then the next golfer
steps up. (Playing alone, or with only computer golfers, you just carry on.)

| Movement | Action |
|---|---|
| Hands together, low in front of you, still for a moment | Address the ball ("Swing!" appears) |
| Swing back - hands up to one side - then down and through quickly | Full shot: the higher the backswing, the further it goes (a live meter shows the distance). About head height is full power; higher is an overswing - a little further, less accurate |
| Bring your hands back through where they started | Straight shot; through to one side sends the ball that way |
| Coming back down slowly | Practice swing (no shot) |
| On the green: hands together low, draw them back sideways, stroke through | Putt: further back = harder (the meter shows how far it will roll on the flat) |
| Raise a hand and hold it over a club | Choose Driver, 3 Wood, 5 Iron, 9 Iron, Wedge or Putter (a club is suggested for you) |
| Raise a hand and hold it over "< Aim" / "Aim >" | Turn the aim (keeps turning while you hold; finer on the green) |

The usual round: **drive** with the Driver, hit the **approach** to the green with an iron or the wedge, then **putt**.
Rough costs some distance, bunkers more (the wedge copes best), trees stop the ball, water and out of bounds cost a
penalty stroke and you play again from the same spot. Watch the wind arrow, and the slope arrows on the green (putts
curl downhill). Putts that stop very close are tapped in for one stroke. A hole ends after par + 4 strokes at most.
Reduced movement / seated: a chest-high backswing is a full swing.

## 1. Christmas Tennis - first to 7 points

Your snow buddy runs to the ball for you; you only swing.

| Movement | Action |
|---|---|
| Swing either arm across your body | Hit the ball |
| Watch the shrinking ring around your character | Swing as it turns green |

On time = fast, deep shot. Far too early = out. Far too late = net. Serve by swinging when it's your turn. Rallies
get gradually harder (the timing window shrinks with every hit) so points always end.

Your swing is judged by the moment your arm moved fastest, compared with where the ball was on your TV at that
moment (camera and TV delays allowed for - see the timing test above). So swing when the ball reaches you on the
screen; if the ball has flown on a little by the time your swing registers, it is returned from where you hit it.

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
| Move a hand under / onto a falling present (anywhere on the way down) | Catch it: present 10, candy cane 15, gold present 25 |
| Catch the star | Magnet mittens: a bigger catch zone for 6 seconds |
| Keep your hands away from coal (glows red, X) | Avoid -15 |

Santa's sleigh drops everything; the shadows on the snow show where each drop is heading. Catches in a row raise a
multiplier (x2 from 5, x3 from 10, x4 from 15); a miss or coal resets it. The last 12 seconds are a Present Rush with
double points. Both players get exactly the same drops in their own lane, inside their own calibrated reach; seated
and reduced-movement players get a narrower lane.

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
