# Controls guide

All games are played by two people at once: **Player 1 on the left, Player 2 on the right** (as seen on the TV).
Each player controls only their own side with their own body. Movements are measured relative to each player's
calibrated body size and comfortable reach, so a child and an adult need the same *relative* effort.

Nothing in any game asks you to run, jump, cross over to the other player, crouch deeply or touch anyone.

> These controls are implemented and covered by automated tests that feed in synthetic body movements (and, for
> held objects, generated pictures; for finger guns, the real hand model on still photos of hands). The owner has
> played up to 1.6.0 on their phone; the 1.7.0 games and movements (finger guns, punches, the bow, darts throws)
> and the 1.8.0 menus, golf screens and tracking changes have **not yet been tried with real people in front of a
> real camera** (see [TEST_REPORT.md](TEST_REPORT.md)). The controls themselves did not change in 1.8.0.

The main menu has two halves - **Sports & Arcade** and **Christmas** - switched with the two buttons under the
title. Quick Match, Play Solo, tournaments and How to Play show the games of the chosen half.

## Everywhere

| Action | How |
|---|---|
| Start a match | On the instructions card, **both players raise a hand and hold it up** until their ring fills. |
| Left/right check | The first time someone raises a hand in front of a camera, slide that hand towards the arrow on the right, then the one on the left. If movements went the wrong way, the picture is flipped for that camera (and remembered). Also: **Flip left/right** on the "Stand in your areas" screen, **Check left/right** in Settings -> Camera & TV. |
| Hands-free menus | Stand in front of the camera (no set-up needed) and **lift a hand to chest height**: your coloured mitten pointer appears. Each hand points from its own side like a touch pad in the air - small, relaxed movements reach the whole screen, and the bottom row of buttons is at chest height. Hold over a button until the gold ring fills (1.2 s, adjustable 0.8-3 s). Buttons are magnetic (a hand just off a button still points at it), and slipping off for a moment pauses the ring instead of restarting it. A lowered hand shows no pointer; a hand on its way up shows a faded one. After a selection, pointers lock briefly and must leave the button before it can be chosen again. |
| Use a real object | On the start screen of a game that can use one (tennis, table tennis, the shooting games), under **Play with**: each player picks their hands, **My racket / My toy gun**, or **Teach my ...** to learn one there and then. Also in **Settings -> Motion -> Teach a racket / club** and **Teach a toy gun**. Hold the object straight up (a toy gun pointing up) beside your shoulder and keep still; the game learns its colours (bright, single-coloured objects work best) and draws a green line along it on the camera picture so you can check it is followed. A racket or club is used in tennis, table tennis and golf (it points the way the object does, and swings are also seen through it); a toy gun is used in the shooting games (flick it up to fire). Kept per player and per kind; **Forget** or switch off "Use learned objects in games" any time. |
| Finger gun | Shooting games: hold your hand out towards the TV with the **index finger pointing** and the other fingers curled in, **thumb up**. The crosshair follows your hand (small movements cover the screen). **Drop your thumb** onto your hand to fire; raise it again for the next shot. The camera follows the fingers of the hand you use (the other hand too, more slowly, when raised). If the fingers can't be seen (too far away, too dark, gloves), a quick **upward flick** of the hand - like a recoil - also fires. |
| Motion test | **Settings -> Motion -> Motion test** shows what the camera sees: your skeleton and held object, a swing meter for each hand (it flashes and counts every swing, and says whether the wrist, the elbow or the object saw it), finger guns (thumb up or down) and shots, punches, the bow, the golf grip, the pointer, and the camera speed, tracking speed (pictures a second and time per picture), body model (full or lite), delay, exposure, finger tracking time, low-light boost and phone heat, with a plain warning when the phone is holding tracking back. Good for trying movements out - and for a screenshot when something isn't picked up. |
| Sharper fast movements | **Settings -> Motion** (on by default): on phones that allow it, the camera uses a much shorter exposure so swinging arms are not blurred. The picture can look a little grainier in a dim room; switch it off if it flickers or goes dark. |
| Low-light boost | **Settings -> Motion** (on by default): when the picture is clearly dark, it is brightened for the body and hand models (the picture you see is not changed). In normal light it does nothing. |
| Extra tracking aids | **Settings -> Motion** (off by default; off = tracking as in version 1.7): steadier crops, a wider second look before a player is dropped and a guard against two players' tracking swapping. They measured better on test pictures but are not proven in a real room - try a game with them on and off and keep whichever works better for you. |
| Touch / remote | Every menu also works by touch on the phone, a TV remote / game controller D-pad (if connected) or a keyboard. When the game is on the TV as a second screen, the phone shows large arrow / OK / Back buttons. |
| Pause | Back (phone gesture/button, remote), the **II** button, or the phone's **Back / Pause** button. Hands-free: one player steps out of view - the game pauses automatically. |
| Someone steps out of view | The game pauses and names the missing player. When they are back in their own area and standing still, a 3-second countdown resumes play. Scores and timers are frozen while paused. |
| Pause menu | Resume, Restart match, Recalibrate players, Switch to front/back camera, Quit. |
| Front or back camera | Tap the camera button at the top left of the main menu, the **Use back/front camera** button on the "Stand in your areas" screen, or **Switch to ... camera** in the pause menu. After switching mid-game everyone checks their position again, then the match carries on. The choice is remembered. |
| Timing test | **Settings -> Timing -> Run the timing test**: a snowball flies into a ring ten times; swing like a tennis shot as each one reaches the ring *on the TV*. The game measures your phone + TV delay and allows for it in the timing games. If swings still feel judged late, raise the allowance a little on the same page; early, lower it. |
| Tracking meter | On the "Stand in your areas" screen, each player's bars (Excellent, Good, Fair, Weak) show how well the camera sees them, with a tip when something would help (come closer, step back, more light, keep both hands in the picture). |
| After a match | Rematch, Choose another game, Main menu (or Continue tournament). |
| Sound | **Settings -> Sound**: music, effects and **Background sounds** (the crowd, birdsong, wind or fire behind each game, new in 1.8.0) each have their own volume. |

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

## Sports & Arcade games

### Clay Shooting - most points (3D)

Both players aim at the same sky, each with their own coloured crosshair. **First shot of the game: hit the target
in the middle** - wherever your hand is then becomes "straight ahead" for you. Clays fly from the trap houses: round
1 one at a time, round 2 in pairs, round 3 a flurry. Two shells, then a quick reload. Each clay goes to whoever
*fired* first (judged by when the shot was made, not when the camera noticed it). Gold clays are worth 3.

| Movement | Action |
|---|---|
| Finger gun pointed at the TV, move your hand | Aim (the crosshair turns green over a clay; it is drawn gently onto a clay that is close) |
| Thumb up, then down | Fire |
| Toy gun: quick upward flick | Fire |

Reduced movement / seated: smaller hand movements cover the screen, the shot spreads wider, and holding the
crosshair on a clay for a moment fires for you.

### Boxing - knockout or most points

Split screen: you see your opponent face on - their boxer copies their real arms - and your own gloves in front of
you. Three rounds of 30 seconds.

| Movement | Action |
|---|---|
| Punch straight at the TV | Jab / cross (fastest) |
| Swing round from the side | Hook (harder) |
| Drive up from below your chest | Uppercut (hardest; also catches a ducking opponent) |
| Both fists up in front of your face | Guard: blocks punches to the head (body punches still land) |
| Lean left or right | Slip a straight (or a hook coming from that side) |
| Duck (bend your knees) | Slip straights and hooks - not uppercuts |

Punching uses energy (the gold bar); punches thrown with little energy left are weaker - take a breath. Three
knockdowns is a knockout. Reduced movement / seated: shorter, slower punches count and a smaller lean slips.

### Archery - most points from 9 arrows (3D)

Split screen, your own lane. If you're right-handed your **left hand holds the bow** (and aims); left-handed, the
other way round.

| Movement | Action |
|---|---|
| Bow hand out towards the TV | Aim: the sight follows it |
| Touch your hands together | Load an arrow |
| Pull your other hand back towards your face | Draw (the power bar fills; the sight is set for a full draw) |
| Open your drawing hand - or let it fly back | Release |
| Bring your hands back together slowly | Put the arrow down without shooting |

Three ends of three arrows at 20, 35 and 50 m; the longer ends have wind (the arrow drifts the way the arrow on the
left shows). Gold = 10. Reduced movement / seated: a shorter pull is a full draw, and holding a good draw still for a
second releases it.

### Shooting Gallery - most points in 60 seconds

A fairground stall: ducks (1 point), bottles (2), balloons (1) and the odd gold star (5). Same controls as Clay
Shooting; six shots, then a one-second reload; first shot made wins each target.

### Darts - highest total from 12 darts

Split screen, a board each. Raise your throwing hand towards the TV - the sight follows it. **Hold steady** over your
target, then **throw: a quick push of the hand towards the TV**. The dart lands where you were holding steady before
the throw (so the throw itself doesn't spoil the aim); a weak, floppy throw drops low. Treble ring x3, double ring
x2, bull 50, outer bull 25.

### Penalty Save, Tennis, Table Tennis, Bowling, Golf

The same controls as their Christmas versions below (Goalkeepers, Christmas Tennis, Table Tennis, Festive Bowling,
Christmas Golf), in sports dress. Golf has three summer courses plus the 9-hole Championship Tour.

---

## Christmas Golf - 1 to 4 players, taking turns

Only the player whose turn it is stands in front of the camera; everyone else stands back. When your name comes up,
step in, stand still a moment and **raise a hand** to start your turn. You play the whole hole, then the next golfer
steps up. (Playing alone, or with only computer golfers, you just carry on.)

Choose a course on the set-up screen: three 3-hole courses, or the **Sleigh Ride Tour** (Championship Tour in
Sports mode) that plays all nine holes in a row. Each hole opens with a short **flyover** from the green back to the
tee, with a card naming the par, length and hazards; raising a hand starts your turn straight away.

The hole is shown in 3D from behind your ball: the planned flight is drawn through the air, the camera follows the
ball down the fairway after each shot, and putts are seen low along the green. A snowman golfer beside the ball copies
your swing, the small map shows the whole hole, and the camera inset marks your hands. The cards at the top show
the hole, your distance to the pin, your lie and the wind; a swing coach card appears only while a camera player is
aiming. A coloured tracer follows each shot and a ring marks where it lands; BIRDIE!, EAGLE! or HOLE IN ONE! pop
up when the ball drops.

| Movement | Action |
|---|---|
| Put your hands together as if holding a club (the two mittens on screen join up and the inset says "Grip OK") | Grip |
| Keep the grip, low in front of you, still for a moment | Address the ball ("Swing!" appears) |
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
| Swing either arm across your body (or swing your held object) | Hit the ball |
| Watch the shrinking ring around your character | Swing as it turns green |

Swing like a racket - back, then through. Every swing around the ball's arrival counts as a try and the one nearest
the right moment is played, so a backswing or a slightly early swing never "uses up" your shot, and a swing before
the bounce or at the top of the bounce still returns the ball. On time = fast, deep shot; a little early or late is
still a safe return; way too early = out, way too late = net. Serve by swinging when it's your turn. Rallies get
gradually harder (faster balls, tighter timing with every hit) so points end in under a minute.

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
Smaller swing thresholds than tennis; as in tennis, the stroke nearest the right moment is the one played. Reduced movement / seated: tiny wrist-and-forearm strokes are enough.

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

Seen in 3D from behind your sleigh, racing down a forest piste. Pass between every pair of flags; a missed gate adds 2 seconds. Presents take 0.5 seconds off; trees and rocks stop
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
