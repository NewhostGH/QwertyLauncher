# Sir Alex of Quertia: TODO and ideas

Hedge knight and herald living on the home screen (`:feature:herald`).
Tick items as they land; ideas at the bottom are not planned yet.
All of the below is written but not yet tried on the phone: untick anything that misbehaves.

## Plumbing

- [x] `Happenings` in core: modules share news (weather, timer, games) without knowing each other
- [x] Weather posts `weather.sky` and `weather.celsius`
- [x] Timer command posts `timer.ends`
- [x] Games post `games.played` and `games.best`
- [x] Phone senses in the herald: battery, music playing, notification count, next alarm
- [x] `alex debug`: his state, senses, memories and journal; every scene and moment to play on demand,
  outfits to try on (unsaved), and the realm's looks (level, season, holiday, night, chores) to preview

## A day of his own

- [x] Daily rhythm by the clock
  - [x] Morning: stretches, polishes his sword, trumpets the weather
  - [x] Midday: eats a turkey leg on a tile
  - [x] Evening: lights a lantern, patrols end to end
  - [x] Night: visits rarer; knocking on his door gets "...five more minutes"
- [x] Needs that rise slowly and show only in what he does
  - [x] Hunger: eats a turkey leg sooner (not yet: rummaging by the apps)
  - [x] Boredom: juggles, kicks a pebble along a tile
  - [x] Tiredness: naps sooner
- [x] Moods from how he's treated
  - [x] Wary after many pokes: peeks out first, no greetings, storms off after 3 pokes instead of 5 (not yet: keeping his distance)
  - [x] Friendly when left alone: waves when you come back to home
  - [ ] (Not possible yet) follow the cursor: the shell doesn't tell actors where focus is

## Reacting to the phone

- [x] Weather: shield over his head in rain, sunbathes when clear, shivers and wears winter clothes when cold
- [x] Battery: cranks at low battery, "feeds the lightning" while charging, cheers at full
- [x] Notifications: a raven lands with a letter; he reads, gasps, hurries off
- [x] Music: dances wherever he is while music plays; freezes, embarrassed, when it stops
- [x] Timer ends: frantic trumpeting
- [x] Alarm within the hour: tiptoes past the clock

## Life in the realm

- [x] Visitors (rare, one scene each): a squire, a cat, a goose that chases him, a dragon hatchling that grows with the realm
- [x] Chores: sweeps, chops wood, hangs a banner, mends the door; the realm shows it for the day
- [x] Seasons: snow in winter, orange leaves in autumn, blossom in spring
- [x] Dates: pumpkin at Halloween, lights at Christmas, a feast on his name day (the day you met)
- [x] Realm levels drawn: 2 adds a treehouse, 3 a little stone tower

## Outfits

- [x] Outfit picked by the moment, or worn by choice (`alex wear <outfit>`, `alex wear auto`)
- [x] Hedge knight (today's)
- [x] Herald: red and gold tabard, feathered hat; on proclamation days (Sundays)
- [x] Night watch: dark cloak and hood, lantern; after dark
- [x] Winter: fur mantle, scarf; cold or winter months
- [x] Tourney: full plate, big plume; after a game
- [x] Pyjamas and nightcap: napping at night
- [x] Unlockables for gold: gold trim, crown, dragon-scale cape

## Remembering you

- [x] Journal: a line a day in old-speak, `alex journal` to read it (the AI writes it when set up)
- [x] Callbacks: nervous on the tile he was dropped onto hard; "Thou wert away long..." after days away
- [x] Quests: one a day, gold for finishing, `alex quest` to see it and your gold
  - [x] No social app before nine
  - [x] Knock on his door
  - [x] Play a game / beat a best score
  - [x] Leave him be: no pokes all day

## Known gaps

- [ ] Only the timer command's countdowns are known: timers set in the clock app itself aren't
- [ ] Notifications (the raven) need notification access granted, as for the notifications tile
- [ ] Text look (without the ascii art module): new moves reuse old text frames, and outfits don't show

## Ideas, not planned yet

- Realm growth from donations (bigger tree, treehouse, keep), beyond quest gold
- Following the cursor: needs the shell to tell actors which tile is selected
- A rival knight who visits now and then
- Sir Alex reading out the day's agenda in the morning
- Friends' realms: share yours as a code
