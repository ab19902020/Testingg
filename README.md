# Living Room Games

A motion-game console for Android, for families: the phone runs the games, its camera follows the players' bodies
(no controllers or wearables) and the picture goes to the TV through a USB-C to HDMI adapter. Two halves, switched
on the main menu:

* **Sports & Arcade** - Clay Shooting, Boxing, Archery, Shooting Gallery, Darts, Penalty Save, Tennis, Table
  Tennis, Bowling and Golf, with sporty characters and daytime looks. Shooting games are played with a **finger
  gun**: point at the TV and drop your thumb to fire (the camera follows your fingers).
* **Christmas** - the original ten Christmas minigames and Christmas Golf, unchanged.

Every game is two players at once (or one person against a computer opponent with **Play Solo**); golf is 1 to 4
players taking turns. A **party tournament** rotates pairs and keeps a family scoreboard. Everything can be
controlled with your body: Player 1 (the red player) waves at the screen to take control of the menus, then lifts a
hand and holds it still over a button. Real objects can be taught to the game - a toy
racket or stick as your racket or club, a **toy gun** for the shooting games - right from a game's start screen.

> **Status, plainly:** all automated tests pass and a signed debug APK has been built and verified. The owner
> reports that versions up to 1.6.0 run on their phone. On 1.8.0 they found tracking worse than before (fixed in
> 1.8.1), and on 1.8.1 that the finger gun does not see the thumb going down. 1.9.0 rebuilt the thumb trigger;
> 1.10.0 changed how the menus are steered and made golf accept a clasped grip; 1.11.0 is a graphics overhaul built
> so that frames do less drawing work, not more. All of this is tested with simulated bodies and screens rendered on
> the build machine - **not yet on the owner's phone**: how smoothly it runs and how it feels in their room is only
> known once played. See [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

## What's in the box

| | |
|---|---|
| APK | `dist/LivingRoomGames-debug.apk` (debug-signed, ~23.7 MB, arm64-v8a + x86_64), SHA-256 in `dist/SHA256SUMS` |
| Build guide + versions | [BUILDING.md](BUILDING.md) |
| Install + TV setup | [docs/INSTALL_AND_TV_SETUP.md](docs/INSTALL_AND_TV_SETUP.md) |
| Controls for every game | [docs/CONTROLS.md](docs/CONTROLS.md) |
| Test results + device checklist | [docs/TEST_REPORT.md](docs/TEST_REPORT.md) |
| Limitations + device compatibility | [docs/LIMITATIONS.md](docs/LIMITATIONS.md) |
| How it works | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Licences | [LICENSES.md](LICENSES.md) |

## Changes in 1.11.0 - next-level graphics, lighter frames

The owner: take the graphics to the next level - but don't make it laggy or break the motion controls; improve the
motion capture where possible.

* **How it stays smooth.** Detailed scenery (skies, mountains, stands, rings, lanes, boards, rooms) is now drawn once
  into a cached picture and reused every frame as a single image, so a backdrop with thousands of details costs about
  as much as one rectangle. Moving things (crowds cheering, chasing bulbs, bobbing snowmen) use a few cached frames
  shown in turn; trees and clouds in the 3D games are cached pictures too; far-away snowmen are drawn simply. Counted
  on the build machine (drawing operations per frame - a stand-in for frame cost, **not a frame rate**), the games
  now do **54% less drawing work** in total than 1.10.0: Tennis 1,673 -> 388, Sleigh Slalom 2,892 -> 1,063, Darts
  588 -> 149. Two do a little more (Penalty Save +23%, Elf Dance-Off +9%) because the players are more detailed.
  A new test keeps every game under a budget and checks no cached picture is remade every frame.
* **Lighting.** Glows, sparks, lamps and beams now *add* light instead of washing colours out; depth-of-field blur on
  distant scenery; a fine film grain; light shafts and floodlight glare.
* **New characters.** The sports players have shaded, rounded limbs, a shaped team shirt with a collar and number,
  trainers and socks, five hairstyles, expressive eyes and a rim light; goalkeepers wear proper gloves. The snowmen
  have packed-snow texture, glints and a cool rim light. All joints are where they were, so they copy your moves
  exactly as before.
* **Every scene redone:** a dark boxing arena with a lighting rig and camera flashes; the clay ground with soft
  clouds, hazy mountains, fence, gravel path and a polished wooden stand; an archery range with roped lanes,
  distance boards, a straw target boss and wind flags; a real-looking dartboard with sisal fibres and a walnut
  cabinet; tennis and table tennis stadiums with roofs, cheering crowds and glowing boards; Penalty Save under
  stadium lights; bowling lanes with board grain and the pin lights reflected down the lane; and for Christmas a
  snowy cabin with icicles, a moonlit village, a stage with velvet curtains, a lush tree for Decorate the Tree,
  sprite forests in Sleigh Slalom and Reindeer Race, and new menu backdrops for both modes.
* **Golf:** leafy trees and pines, soft clouds, haze in the distance, and a proper golfer (legs, polo shirt, cap,
  shaded arms) instead of a stack of balls.
* **Effects:** breaking clays and bottles throw tumbling shards and dust; confetti is foil that glints.
* **Motion capture:** during play, if a player's tracking stays weak for two seconds (hands out of the picture, too
  close or far, too dark), a tip appears on their side - "Keep both hands in the picture", "Step back a little" -
  until it is fixed, so missed moves come with the reason. The gesture code itself is unchanged in this version.

## Changes in 1.10.0 - Player 1 steers the menus, steadier choosing, golf grip

The owner: only Player 1 (whoever is in the red position) should control the menus; it should say "wave at the
screen" to take control; choosing happens too easily while the hand is just hovering, and the pointer is too slow to
move away; golf wasn't picking up the swing; better graphics.

* **Wave to take control.** Menus are steered by Player 1 only - the person in the red (left) player area. Until
  they wave, a prompt says *"Player 1: wave at the screen to take control"* with a waving hand and a progress bar.
  A wave is a hand at chest height or above moving side to side: three changes of direction, each a clear swing,
  in quick succession (tracking jitter and an arm being lowered don't count). A chime confirms it. Control is kept
  while Player 1 is in view; it is given up after about 12 s with both hands down or 2 s out of view, and taken back
  with another wave. Player 2 no longer gets a menu pointer. Following the arrows of the left/right check also gives
  control. The golfer choosing a club during their turn doesn't need to wave.
* **Hold still to choose.** The gold ring now fills only while the pointer is held steady over a button, and empties
  2.5 times faster than it fills as soon as the hand moves - so a hand passing over a button, or moving away from one
  it didn't mean to choose, never selects it. A held hand's small tremble still counts as still. Buttons pull a
  steady pointer in from a shorter distance (32 px instead of 60), and never pull a moving one.
* **Quicker pointer.** The pointer's smoothing lets go sooner when the hand moves (it follows a deliberate movement
  faster), while still damping jitter when the hand is held still.
* **Golf grip.** Clasped hands often hide one wrist behind the other; the camera then barely sees it and the grip was
  not recognised, so the swing never started. Now a hidden wrist placed right next to the visible one counts as the
  grip. Tests also confirm a blurred downswing and a quick swing on a slow (15 pictures a second) camera still hit.
  The Motion test graph has a new **golf** view: hands height, the grip and address, the backswing and the strike.
* **Graphics:** the Shooting Gallery is now a fairground booth at night (striped awning with chasing bulbs, ferris
  wheel, ducks riding between wooden waves, a wooden bottle shelf, prize teddies on the counter), and Table Tennis in
  Sports mode is played in a floodlit arena with a crowd, advertising boards and court surrounds.

## Changes in 1.9.0 - the finger gun, rebuilt

The owner: the gun games don't recognise the thumb going down; everything should react quickly without
over-reacting. The gun is: one or two fingers pointing out, thumb up; thumb down shoots.

* **Why the thumb was missed.** The old trigger used fixed limits measured on photos of hands seen from the side, in
  3D, and also required a strict "index finger straight" shape. A finger gun points at the camera (seen end-on), where
  the hand model's depth is at its least reliable: with depth squashed as such models do, a fully lowered thumb read
  0.37-0.49 against a fire line of 0.33 - it could never fire - and the straight-index check failed too.
* **The new trigger** reads the thumb flat on the picture, in hand widths, against the side of the fingers (one- or
  two-finger gun), and learns each player's own thumb-up and thumb-down positions. Built like the pinch and
  thumb-tap detection of headset hand tracking: a 0-100% scale with a wide gap between "ready" (above 70%) and "fire"
  (below 30%), one quick movement (up to about 0.4 s), a 3-frame filter so one misread frame can't fire, and the shot
  timed to when the thumb really dropped. Open hands, fists seen from the side and very fast-moving hands don't fire.
* **Steadier hand crop** for a hand pointing at the camera (the crop the finger model sees no longer spins).
* Measured on the real hand model's landmarks of a real photo, turned to point at the camera at 12 angles, with
  squashed depth and frame-to-frame wobble - **not a camera**:

  | Finger gun pointing at the camera | Up to 1.8.1 | 1.9.0 |
  |---|---|---|
  | One finger: thumb drops that fired | 33 of 48 | **48 of 48** |
  | Two fingers: thumb drops that fired | 23 of 48 | **48 of 48** |
  | Small thumb movements (half up, not fully down) | - | 47 of 48 |
  | Shots with no thumb drop (held up, held down, twitches, misread frames, hand turning) | - | 0 |
  | Wobble of the hand crop's direction | 10.3 deg | 3.6 deg |

* **Archery:** pulling the string back quickly could fire an arrow by itself. Now a flick-back lets go only after
  the draw has been held for a moment (as in real archery); opening the hand still lets go at once.
* **Sleigh Slalom tuck** no longer flickers on and off when your hands are near the edge of "together", and survives
  one wrist hiding behind the other for a moment.
* **Motion test graph:** the new Graph button shows, live, what the game reads for a move (thumb, bowling hand,
  lean and tuck, bow, swing) with the lines it has to cross and where it fired. A screenshot of it shows exactly why
  a move did or didn't count - the quickest way to tune bowling and skiing to a real room.

## Changes in 1.8.1 - tracking fix

The owner found tracking worse in 1.8.0 than before (moves missed, wrong triggers). Two faults in the new 1.8.0
low-light brightening fit that, and are fixed:

* **It switched on abruptly.** A picture just on the dark side of its threshold was made ~1.7 times brighter than
  one just on the bright side, so a room near the threshold flickered between the two from frame to frame and the
  body points jumped - which can fire moves by itself and spoil real ones. Now a dark picture is brought up to a
  fixed target, starting from nothing at the edge of "dark", smoothed over frames for each player, and a picture
  with normal brightness is not touched at all. Largest change for a 0.5% change in light: 0.011 (1.8.0: 0.21).
* **It was slow.** It ran a slow maths function on every pixel of every body crop: about 3.7 ms per crop on a fast
  desktop, so likely several times that per player on a phone - possibly enough to make the game switch to the
  less accurate lite body model and drop the 60 fps camera (not measured on a phone). Now a lookup table: about 0.5 ms per crop on the same
  desktop.
* **Tracking is back to 1.7.0 by default.** The other 1.8.0 tracking aids (steadier crops, wider retry, keep-tracking
  bar, jump guard, hand crops following the wrist) are now **Settings -> Motion -> Extra tracking aids**, off by
  default. The low-light boost has its own switch (on).
* **Motion test** now shows the body model in use (full or lite), time per picture, the low-light boost, phone heat,
  and a plain warning when the phone is holding tracking back. A screenshot of it shows what is going on.

## Changes in 1.8.0 - new sound, new menus, better graphics, golf and tracking

* **Sound.** Every effect rebuilt in stereo from layered recipes (77 effects, 43 of them new: crowds, applause,
  fanfares, golf, shooting, archery, darts, boxing, ball sports, menus), loudness-matched with a weighting for small
  speakers. Each venue has its own room echo and a live background (arena crowd, park birdsong, winter wind,
  fireside, bowling lanes) with its own **Background sounds** volume in Settings. Music dips under big moments and a
  limiter stops loud moments from clipping. Captions cover the new sounds.
* **Menus and game screens.** A drawn icon set, buttons that lift and glow under a hand, new game cards with live
  previews, play-style tags and a hold-to-choose bar. New main menu (logo, Sports/Christmas switch, three big cards
  for Quick Match, Play Solo and Golf), new instructions card, pause panel and podium results, a broadcast-style
  scoreboard with score pops in every game, and a new countdown.
* **Graphics.** Sleigh Slalom is now 3D from behind the sleigh, down a forest piste. Reindeer Racing has galloping
  reindeer seen from behind and cheering snow buddies. Richer skies, clouds, mountains and tree lines outdoors; a
  proper clay range; a lit dartboard cabinet; neighbouring bowling lanes; a new impact flash on punches, clays,
  targets, saves, snowball hits and strikes.
* **Golf.** The course fills the screen with a cleaner HUD (hole card, player card with distance, lie and wind,
  swing coach only while you aim). Each hole opens with a flyover from the green to the tee. BIRDIE!/EAGLE!/HOLE IN
  ONE! pop-ups, a coloured shot tracer and a ring where the ball lands, clouds, leafy trees, glinting water, raked
  bunkers. New 9-hole course (Sleigh Ride Tour / Championship Tour) playing every hole in a row.
* **Camera tracking.** Dark rooms: if the normal search finds nobody new, a brightened copy of the picture is
  searched too, and every body and hand crop is brightened when it is dark or backlit. A person whose position
  suddenly jumps (usually onto the other player) is re-checked where their own movement says they should be.
  Steadier crops when still, one wider retry before a person is dropped, a lower bar for keeping someone already
  tracked, and hand crops that move with the body's wrist. Movement prediction of crops was tried and left off: it
  measured worse.

  Measured with the real pose models on a real photo of two people turned into hard sequences (noise, dim, dark,
  backlit, swaying, lunging, jumping) - **a stand-in for a camera, not proof of real-camera tracking**:

  | Sequence | 1.7.0 settings | 1.8.0 settings |
  |---|---|---|
  | Dark room (~2.7 stops under): both players found | 0% of frames | 100% |
  | Dark and swaying: both found | 57% | 97% |
  | Dim: joint error / jitter (fraction of picture size) | 0.051 / 0.050 | 0.033 / 0.030 |
  | Noisy still picture: jitter | 0.041 | 0.022 |
  | Backlit: jitter | 0.054 | 0.036 |
  | Fast side-to-side sway: track jumped onto the other player | 1 time | 0 |
  | Fast sway: both found | 97% | **87%** (worse: frames where the test pushes a player half out of the picture) |

  It is not "flawless" and cannot be shown to be from here: the real test is a session in your living room.
  *(1.8.1: on the owner's phone 1.8.0 tracked worse; see Changes in 1.8.1. These aids are now a setting, off by
  default.)*

## Changes in 1.7.0 - a console, not just Christmas

* **Two modes.** The main menu switches between **Sports & Arcade** and **Christmas**; Quick Match, Play Solo,
  tournaments and How to Play follow the chosen mode (remembered). The app is now called Living Room Games (same
  package: it installs over the old version and keeps players, settings and objects).
* **Finger tracking.** In the shooting games the camera also follows the fingers of each player's gun hand, with
  the MediaPipe hand landmark model (bundled, checksum-verified, runs only in games that need it). Make a finger gun,
  raise your thumb, drop it to fire. Holding a toy gun instead? A quick upward flick (a recoil) fires.
* **Clay Shooting (3D):** both players aim at the same sky; singles, doubles, then a flurry; each clay goes to
  whoever *pulled the trigger* first (judged by when each shot was made, so camera delay favours nobody).
* **Boxing:** split screen, your opponent face on (copying their real arms), your gloves in front of you. Jabs and
  crosses at the TV, hooks round the side, uppercuts from below; fists up to block, lean or duck to slip; energy,
  knockdowns, KO.
* **Archery (3D):** bow hand at the TV to aim, hands together to load an arrow, pull back to your face, open your
  hand (or let it fly back) to release. 20, 35 and 50 m, with wind.
* **Shooting Gallery, Darts, Penalty Save** - more quick games; darts land where you held steady before throwing.
* **Sports versions** of Tennis, Table Tennis, Bowling and Golf: daytime stadium, sports hall, bowling alley,
  summer course, and athletes instead of snowmen.
* **Objects before a game:** the start screen of a game that can use one (tennis, table tennis, shooting) lets
  each player pick their hands, their learned object, or teach one there and then. Objects are kept per player and
  per kind (racket/club and toy gun).
* **Motion test** now also shows fingers (finger gun, thumb up/down), punches, the bow and shots.

## Changes in 1.6.0 - tracking rework, 3D golf, real objects

* **Swings that count.** Swing detection now reads the camera's unsmoothed body points and copes with what real
  cameras do to a fast arm: a wrist that blurs out for a few frames (the elbow and the gap are bridged), a forehand
  swung towards the TV (depth counts), one-frame glitches (ignored). A backswing no longer blocks the real swing
  that follows it, and while a ball is arriving the detector becomes more sensitive.
* **Tennis and table tennis fixed.** The right moment is known as soon as the opponent hits; every swing around the
  ball's arrival is a candidate and the one nearest the right moment is played - early swings and backswings are
  never "used up", and a swing at the top of the bounce still returns the ball. Rallies tighten faster so points end.
* **Hand pointer rebuilt.** Each hand points from its own side like a touch pad in the air; small relaxed movements
  reach the whole screen and the bottom buttons are at chest height (they used to sit where the pointer switched
  off). Steady when held still, quick when moved, no overshoot; slipping off a button pauses the ring instead of
  restarting it; a bigger mitten pointer with a ring and a "pop"; 1.2 s to choose.
* **Christmas Golf in 3D.** Played from behind the ball with the planned flight drawn through the air; the camera
  follows the shot down the fairway; low putting view with the green's slope; trees, water, bunkers, mountains; a
  snowman golfer copies your swing. You now **grip** first - hands together like holding a club (the mittens on
  screen join up) - and hands hidden behind your head at the top of the backswing no longer cancel the swing.
* **Use a real object** (Settings -> Motion): hold a toy racket, stick or pool noodle up and the game learns its
  colours, then follows it; your racket, bat or club points the way it does, and swings are seen through it too.
* **Motion test** (Settings -> Motion): your skeleton, held object, live swing meters (and which part of the arm saw
  each swing), grip, pointer and camera speed - to try movements out and to screenshot when something isn't seen.
* **Camera:** sharper fast movements (a short, flicker-matched exposure where the phone allows it), the 60 fps camera
  used whenever tracking keeps up, and the more accurate pose model kept unless the phone is too slow or hot.

## Changes in 1.5.0 - movements go the right way, a new Present Catch

* **Left and right fixed for every phone.** Some phones deliver a camera's picture already mirrored, which made
  every movement go backwards on screen (hand right, cursor left). Now, the first time someone raises a hand in
  front of a camera, a quick check appears: slide your hand towards an arrow on the right, then on the left. If the
  hand went the wrong way, the game flips the picture for that camera and remembers it. A picture that arrives
  upside down is noticed and turned the right way up automatically. There is also a **Flip left/right** button on
  the "Stand in your areas" screen, and **Check left/right** in Settings -> Camera & TV.
* **Present Catch rebuilt.** Santa's sleigh flies over each lane dropping presents; catch them with your hands
  anywhere on the way down. Candy canes and gold presents score more, a star gives **magnet mittens** (a bigger
  catch zone for 6 seconds), coal glows red and costs points. Catches in a row raise a multiplier (x2, x3, x4) and
  the last 12 seconds are a double-points **Present Rush**. Shadows on the snow show where each present will land,
  and catches allow for camera and TV delay.
* **Easier hand control:** buttons are slightly magnetic - a hand just off a button still points at it, and a small
  wobble over the edge no longer restarts the hold-to-choose ring.

## Changes in 1.4.0 - golf, and control everything with your body

* **Christmas Golf** (main menu): 1 to 4 players - family members and/or computer golfers (Sprout, Jingles,
  Blitz) - take turns, so only the player whose turn it is stands in front of the camera. Each player plays a
  whole hole, then the next steps up and raises a hand to start. Three courses of three holes (par 12 each):
  *Candy Cane Links* (wide and gentle), *Frozen Lake Club* (water everywhere), *North Pole Peaks* (narrow,
  tree-lined, a steep green).
  * **Drive, approach, putt - three movements:** a full golf swing for the tee shot and the approach (the higher
    your backswing, the further the ball goes; bring your hands back through where they started to hit it
    straight), and a small pendulum stroke for putting (draw your hands back sideways - further back, harder putt).
    A live meter shows the distance while you swing back; coming down slowly is a practice swing.
  * **Clubs and aim by hand:** raise a hand and hold it over Driver, 3 Wood, 5 Iron, 9 Iron, Wedge or Putter, or
    over an aim arrow (hold to keep turning). The game suggests a club and aims for you, so you can just swing.
  * Wind, bunkers, snowy rough, trees, frozen lakes (one penalty stroke), sloping greens with arrows showing the
    break, and short putts tapped in automatically. A scorecard after each hole; fewest strokes wins.
* **Hands-free from the start:** a hand cursor now appears for anyone standing in front of the camera - no set-up
  needed first - so you can open games, pick players and start matches without touching the phone. (The very
  first camera permission still needs a tap on the phone; Android requires that.)
* **Less tracking work for one player:** in Play Solo and golf the camera follows one player (plus one spectator),
  so the person-search runs far less often - 7 instead of 25 times in 24 frames in the test - leaving more time
  for 60 fps tracking.

## Changes in 1.3.0 - less lag, fairer timing

* **Swings count when you make them, not when the game hears about them.** Every movement reaches the game a
  little late (the camera and body tracking take ~0.1 s, and the TV shows the picture late too), which made
  on-time tennis swings land "late". Swings are now stamped with the moment your arm moved fastest - where the racket
  meets the ball - and judged against where the ball was *on your TV* at that moment. The same allowance is used for
  goalkeeper saves, snowball shields and dodges, and dance poses. Computer players are unaffected.
* **Timing test** (Settings -> Timing): a snowball flies into a ring ten times; swing as it gets there. The game
  measures your phone + TV delay and allows for exactly that. Without the test it assumes a typical 60 ms. You can
  also nudge the allowance up or down by hand.
* **Less delay to begin with:**
  * Swings are recognised 17-42 ms after the fastest moment of the swing (was 33-75 ms): the smoothing now opens up
    as soon as you start moving.
  * Drawing is synchronised with the screen refresh, so finished pictures no longer wait in a queue (up to ~33 ms).
  * The game asks the TV for its low-delay game mode (HDMI ALLM) where the TV and phone support it.
  * The camera uses its low-delay preview mode with fast noise reduction (no multi-frame smoothing that smears
    fast arms).
  * The small camera picture is made on a separate thread, only while it is shown, so it never holds up tracking.
  * In Auto quality the game picks whichever body-tracking model lets the camera run at 60 fps on your phone.
  * Your character's arms are shown slightly further ahead (up to 0.12 s) to hide the remaining delay; steering
    (lean) reacts faster.
* **Bigger tennis court and more time:** the court is about 18% wider, shots take about 0.15-0.2 s longer to cross,
  and the timing window is wider (0.36 s). Long rallies still get faster, so matches still end.

## Changes in 1.2.0

* **Front or back camera, one tap away:** a camera button on the main menu, on the "Stand in your areas" screen
  and in the pause menu switches between the front camera and the back camera. The back camera is usually sharper
  and wider; with it the phone's screen faces away from the players, which is fine because the game is on the TV. Switching re-checks everyone's
  position, and the choice is remembered.
* **Smoother, steadier body tracking:**
  * Smoothing now adapts to each player's size in the picture, so a child far from the camera gets the same quick,
    steady response as an adult close to it. Low-confidence joints are smoothed harder; confident fast moves stay
    sharp.
  * One-frame glitches (a wrist that jumps across the body for a single frame) are ignored.
  * The on-screen avatar and hands are predicted a few hundredths of a second ahead to hide camera and processing
    delay (capped, so it never overshoots far).
  * Crouching, ducking and head tilt now show on your character.
  * The camera asks for 60 frames per second when the phone keeps up and drops back to 30 when it doesn't
    (Settings: "Smoother tracking").
  * A tracking meter on the "Stand in your areas" screen rates each player (Excellent to Weak) and says what would
    help: come closer, step back, more light, keep both hands in the picture.
* **New graphics everywhere:** lit, shaded snowmen with knitted hats and mittens; skies with aurora and moon;
  floodlit courts and pitches; a log cabin and brick snow forts; ski tracks and snow spray on the slalom; a
  mountain road with lanterns; a polished bowling lane; a lamp-lit table tennis hall; a dance stage with moving
  spotlights and a light-up floor; a living room with frosty windows for the tree. Buttons, panels and the
  scoreboard are glossier, and hits, catches and saves now flash with impact rings and glowing sparks.
  "Reduced effects" in Settings still tones all of this down.

## Changes in 1.1.0

* **Play Solo:** one person against a computer player in all ten games. Pick who is playing and the opponent:
  **Sprout (Easy)**, **Jingles (Medium)** or **Blitz (Hard)**. You can stand anywhere in the middle of the picture;
  setup, calibration and the start gesture are for you alone, and the game only ever pauses for you.
* The computer players play fair: each sees only what a person sees on screen, reacts after a human-like delay,
  moves its hands at a human speed and makes mistakes (more on Easy, fewer on Hard). Their characters animate their
  swings, throws, reaches and dances like a camera player's would.
* Quick Match's "Who is playing?" screen has a **Just me** button that starts the chosen game against the
  computer. The Party Tournament stays a two-person mode.

## Changes in 1.0.1

* **Crash right after allowing the camera: most likely cause fixed** (found by code review; not yet confirmed on
  the phone). Granting the permission started the camera twice (from the permission result and again when the app
  resumed), so the same camera was opened twice and the first one was shut down mid-setup; an error on the camera
  thread was not caught and closed the app. Camera start is now idempotent, stale camera callbacks are ignored and
  every camera callback is guarded.
* If the game ever closes unexpectedly, the next launch shows what happened (phone model and where the error was)
  with a **Share details** button. Nothing is sent automatically.
* If the phone's GPU driver crashes during body tracking, the game switches to the CPU for good on that phone.
* If body tracking cannot start (for example the TensorFlow Lite library cannot load), a message says so instead
  of tracking silently not working.
* Safer camera image handling and pose-thread restarts after pausing.

## The games

**Sports & Arcade**

1. **Clay Shooting** (3D) - finger gun, first to hit each clay, most points wins.
2. **Boxing** - real punches, guard and slips, three rounds or a KO.
3. **Archery** (3D) - draw, aim and release; nine arrows at three distances.
4. **Shooting Gallery** - ducks, bottles, balloons and gold stars in 60 seconds.
5. **Darts** - aim, hold steady, throw; trebles, doubles and the bull.
6. **Penalty Save** - reach to stop the shots.
7. **Tennis**, 8. **Table Tennis**, 9. **Bowling** - the swing games, in sports dress.
10. **Golf** (3D) - 1 to 4 players taking turns, three summer courses.

**Christmas**

1. **Christmas Tennis** - swing like a racket, first to 7.
2. **Snowball Showdown** - throw, dodge by leaning, shield with both hands up.
3. **Present Catch** - catch the presents Santa drops, dodge the coal.
4. **Reindeer Racing** - pump your arms in rhythm (no running), lean to steer.
5. **Festive Bowling** - underarm swing, 5 frames, independent lanes.
6. **Table Tennis** - small quick forehands/backhands, first to 11.
7. **Elf Dance-Off** - copy the elf's poses on the beat.
8. **Sleigh Slalom** - lean to steer, tuck for speed (steering-only mode available).
9. **Christmas Goalkeepers** - reach to save shots.
10. **Decorate the Tree** - hold a hand still to pick up and hang ornaments.
11. **Christmas Golf** (3D) - 1 to 4 players taking turns.

Every game is played by two people at the same time, each controlling their own side with their own body - or,
with **Play Solo**, by one person against the computer (Easy, Medium or Hard).

## Quick start (players)

1. Install the APK (see [install guide](docs/INSTALL_AND_TV_SETUP.md)).
2. Plug a USB-C to HDMI adapter into the phone and the TV (phone must support USB-C video output).
3. Put the phone in landscape on a stand near the TV at about chest height, camera facing the room.
4. Open the app and follow the setup: TV check -> place the phone -> step into your outlines -> calibrate ->
   both players raise a hand and hold to start.
5. On your own? Choose **Play Solo**, pick yourself and an opponent level, then a game. Stand in the middle of
   the picture, calibrate, and raise a hand to start.
6. Pick **Sports & Arcade** or **Christmas** at the top of the main menu.

## Quick start (developers)

```bash
# needs JDK 17+ and an Android SDK with platforms;android-35 and build-tools;35.0.0
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :core:test            # JVM tests (the real-model tests are skipped unless configured, see BUILDING.md)
./gradlew :app:assembleDebugApk # -> app/build/outputs/apk/LivingRoomGames-debug.apk
adb install -r app/build/outputs/apk/LivingRoomGames-debug.apk
```

## Technology choice

* **Kotlin, plain Android framework, no game engine.** Rendering is a small immediate-mode 2D layer over
  `Canvas` (hardware accelerated via `lockHardwareCanvas`). This keeps the APK small, the build simple and the
  whole game (rules, UI, tracking logic) testable on a normal JVM.
* **Pose model: MediaPipe BlazePose (lite + full), Apache-2.0**, run with **TensorFlow Lite 2.16.1** (GPU
  delegate, CPU/XNNPACK fallback). **Finger tracking: MediaPipe hand landmark model (lite), Apache-2.0**, on the
  CPU, only in games that use fingers. The models are bundled in the APK; nothing is downloaded at runtime.
* **Genuine multi-person tracking.** The stock MediaPipe Pose Landmarker reliably tracks one person per image, so
  the app contains its own Kotlin implementation of the BlazePose two-stage pipeline: the person detector runs on
  overlapping left/right/full search regions, each found person gets their own landmark pass and their own region
  of interest is tracked frame to frame. This port was checked against the official MediaPipe output on real
  two-person images (mean landmark error about 0.002 of the image size; details in the test report).
* **Identity by position and continuity, not detection order.** Each player is locked to a slot after
  calibration; spectators cannot take over; lost players are re-acquired only if they match the calibrated body.
* **Custom Gradle pipeline instead of the Android Gradle Plugin.** The build environment could not reach Google's
  Maven repository, so `app/build.gradle.kts` drives aapt2, D8, zipalign and apksigner directly. It works with a
  normal Android SDK on any machine (see [BUILDING.md](BUILDING.md)).

## Performance budgets (targets, not measurements)

These are the design targets the code is built around. **They have not been measured on a device yet**; the
in-app performance overlay (Settings -> Developer) shows the live values to check against them.

| Item | Target |
|---|---|
| Camera | 1280x720, 30 fps, newest frame only (no queue) |
| Pose inference, both players | <= 25 ms per camera frame (full model, GPU) |
| Finger tracking (shooting games) | <= 8 ms per camera frame for both gun hands (lite hand model, CPU) |
| Camera capture -> game input | <= 100 ms |
| Render | 60 fps cap (16.7 ms), independent of inference rate |
| Simulation | fixed 120 Hz step, independent of render rate |
| Adaptation | AUTO quality drops to the lite model after 3 s above 30 ms or when the phone is hot; returns after 15 s below 12 ms |
| Thermal | SEVERE: render capped at 30 fps and lite model; CRITICAL: pose runs on every other camera frame |

## Privacy

Camera frames are processed in memory on the phone and discarded. No video or images are recorded, stored or
uploaded. The app does not request the INTERNET permission (the build fails if it ever does). Player names and
scores are stored only on the phone.
