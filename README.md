# Clog Casino

Were you spooned or dry? Clog Casino records the kill count you got each
boss and raid collection log item at, and rates every drop against its
drop rate: **spooned**, **average**, **dry** or **desert**.

You can see your luck in the RuneLite side panel and in chat, and, if you
choose, on a public profile on the Clog Casino website
(https://osrs-luck-tracker.vercel.app).

## Features

- **Luck for every new drop.** When a boss or raid drop enters your
  collection log, a line in chat shows the kill count you got it at and
  how lucky that was, for example "first obtained at 49 KC - dry".
- **Check any slot.** Use the game's *Check* option on an item in your
  own collection log to see the same luck line for drops the plugin
  has recorded.
- **Side panel.**
  - **My luck** tab: your overall luck, your luckiest drop ("jackpot"),
    your driest drop, and the items you're still hunting at each boss,
    with a link to your website profile.
  - **Backlog** tab: tools for bringing in the items you had before you
    installed the plugin (see below).
  - Your boss kill counts this session, above both tabs.
- **Import your existing log.** Items you already had are brought in
  from your collection log. The game doesn't record the kill count they
  dropped at, so they're marked as obtained and never shown as
  spooned or dry. Where the log page shows a kill count, the website
  can show an optional estimate from "how many by this KC".
- **Still hunting.** The empty slots on your boss pages become a "still
  hunting" list showing how overdue each one is.
- **Website profile and leaderboard (optional, off by default).** Share
  your log on the Clog Casino website, compare yourself with friends,
  and join the luckiest/driest players leaderboard.

## Getting started

1. Install the plugin and log in. Your account registers itself.
2. Open your collection log and click through the **Bosses** and
   **Raids** pages listed on the panel's **Backlog** tab. Each page is
   read as you open it.
3. Press **Import items** once. This brings in what you already had.
4. That's it. From then on, new drops, kill counts and any log page you
   open sync by themselves, with no more buttons to press.
5. If you want others to see your log, turn on **Show my log on the
   website** in the plugin settings (see below).

You only need to reopen a log page if you got an item on it while the
plugin wasn't running. If the panel lists a page under **Pages to open
again**, it was read while still loading, so open it once more.

## Settings

| Setting | Default | What it does |
|---|---|---|
| **Show my log on the website** | Off | Lets anyone look up your name on the Clog Casino website and see your drops and luck. While off, the website says no player is logged under your name. Your drops are still recorded, so turning it on later shows everything. |
| **Show me on the leaderboard** | Off | Lists you on the website's luckiest/driest players leaderboard. Needs *Show my log on the website* on as well. |

The panel and chat lines work the same whichever way these are set.
Settings are stored per RuneLite profile, so every account you play on
the same profile shares them.

## How luck is rated

Each drop is compared with its wiki drop rate: the chance of getting the
item as early or as late as you did.

- **Spooned:** under a 10% chance of getting it this early.
- **Average:** anywhere in between.
- **Dry:** more than 80% of players would have had it by this KC.
- **Desert:** more than 99% would have.

Raid uniques and items with pity mechanics are estimates based on a
typical raid (assumed points or team size), since the plugin doesn't
read your raid points. Your **overall luck** is the average of all your
rated drops, compared with what a player with fair luck would average.

## What's sent, and where

Everything goes to the Clog Casino backend (a Supabase project run by
the plugin's author), over HTTPS:

- **On first login:** your account hash and in-game name, to register
  the account. The server returns an install token, which RuneLite
  stores in your profile and which authorises every later request. Your
  account hash is never shown on the website.
- **When you get a new collection log item from a boss or raid:** the
  item, the boss, and your kill count.
- **When you open collection log pages:** for boss and raid pages, the
  items you have and their quantities, the items you're missing, and
  the page's kill count.
- **While you play:** kill counts from boss kill messages, batched at
  most once a minute and at logout.
- **When you change the plugin settings:** the two visibility settings
  above.

As with any website, the server also sees your IP address; it is used
only for rate limiting. Nothing is sent about your bank, inventory,
location, chat (other than the kill-count and collection log messages
above) or other players. Nobody can look up your log on the site unless
you turn on "Show my log on the website". Disable the plugin to stop
sending anything.

## Good to know

- **Only boss and raid drops are tracked.** Clue scrolls, minigames and
  skilling pets don't print a kill count, so the plugin can't tell what
  they came from and skips them.
- **A drop counts if it follows a kill-count message** within 60
  seconds, or 10 minutes after a raid, since raid loot comes from the
  chest. Floor loot enters your log when you pick it up, so pick it up
  promptly.
- **Items shared between bosses** (godsword shards, Dragon pickaxe,
  Virtus and similar) can't be imported automatically, because the log
  shows them on every page that lists them. Add them one at a time with
  **Add a single item** on the Backlog tab if you know which boss they
  came from.
- **Lost install token.** The token lives in your RuneLite profile. If
  you lose it (for example by resetting your profile), open an issue
  and it can be reset.

Bug reports and questions:
https://github.com/nreaver1/osrs-luck-tracker-plugin/issues

---

## For developers

The rest of this README covers how the plugin works internally.

### How it works

1. On login, calls `/register` once per account (result cached locally),
   getting back an `install_token`.
2. Watches chat for kill-count messages ("Your Zulrah kill count is:
   127.", "Your completed Tombs of Amascut: Expert Mode count is: 12.")
   to track current KC per boss or raid. `KillCountMessage` strips the
   colour tags the game puts around the number first.
3. Watches chat for the collection log popup message ("New item added
   to your collection log: X") and, if it happened within 60 seconds of
   a kill-count message (10 minutes after a raid completion, since raid
   loot is claimed from the chest later), resolves the item name to an
   item ID and calls `/ingest-drop` with the item, source, and KC at the
   time. Once it's recorded, the plugin adds the same luck line to chat
   that checking the slot in the collection log would ("first obtained
   at 49 KC - dry"). Names are matched against the collection log's own items first,
   because `itemManager.search()` only knows tradeable items.
4. Imports items the player already had before installing the plugin.
   The player opens their collection log in-game and clicks through the
   pages the side panel lists; each page is read as it opens, and the
   panel shows what it found and an "Import N items" button. A dropdown
   for adding single items remains as a fallback; it lists only
   collection log slots on each source's page, minus anything the
   account already has recorded (read from `/get-player-luck` on login). Both call
   `/backfill-drop`, which records items as obtained with **no KC and no
   computed luck**. See "Collection log import" below for how this stays
   reliable.

### Collection log import

Earlier approaches to reading the whole log automatically broke when
game updates changed things underneath them. This one avoids relying
on anything fragile, and fails closed when something does change:

- **Reading a page.** After the game's own `COLLECTION_DRAW_LIST` script
  (2731) draws a page, the plugin reads the page title and the item
  widgets it just drew. Obtained slots are drawn at opacity 0. RuneLite's
  built-in Chat Commands plugin reads the "All Pets" page exactly the
  same way, so RuneLite itself keeps this hook working. Only pages the
  player opens are read; the plugin never opens the log or clicks
  through it. What each page showed is saved per account in RuneLite's
  config (`readLogPages`), so it's a one-time job: later logins start
  from the saved pages, and items that gain a drop rate later become
  importable without reopening anything. New drops are tracked live
  from chat, so a page only needs reopening for items obtained while
  the plugin wasn't running.
- **Shared items.** The log fills a slot on *every* page that lists the
  item, whoever dropped it: a Godsword shard from Kree'arra shows on all
  four GWD pages. So an obtained slot only proves its source when no
  other drop source's page lists it. `CollectionLogIndex` reads the full
  page/item layout from the game cache (tab structs 471–475, params
  683/689/690, the same IDs the Collection Log plugin-hub plugin uses).
  `BackfillPlanner` only offers items that exactly one catalog source
  with a rate for them lists. Pages that aren't a source, like "All
  Pets", don't count, so boss pets import normally. Items several
  sources drop (godsword shards, Dragon pickaxe, Virtus) are listed as
  skipped and can be added one at a time from the dropdown.
- **KC snapshot.** The same read also takes the page header's kill
  count and each obtained slot's quantity (`LogPageSnapshot`, saved as
  `logSnapshots`), and an import sends them with each item so the site
  can estimate luck from "k copies by N KC". A personal best time is
  never read as a count. Tempoross uses "Reward permits claimed", since
  its rates are per permit (`ROLL_COUNTERS` in `LogPageSnapshot`). It's
  left out when the header has no counter or several (Dagannoth Kings,
  The Gauntlet's two modes, Wintertodt, whose carts give a
  points-dependent number of rolls), for stackable slots (their quantity counts items, not drops),
  and for shared items (their quantity spans every source). Items
  imported before snapshots existed are offered as "Add luck estimates".
- **Still hunting.** The empty slots on those same pages become the
  site's "still hunting" list (`BackfillPlanner.planHunting`, sent to
  `/sync-hunting` from the same button). Only flat-rate catalog items on
  the page's own source count, and only from a page whose slots read as
  obtained add up to the header's "Obtained: x/y", so a page drawn with
  slots still faded can't list owned items as missing. A page skipped
  that way (or saved by an older version that didn't keep the header's
  count) is listed in the panel under "Pages to open again". Reopening a
  page later sends the higher kill count and clears items obtained since.
  The same button sends each consistent page whole (kill count,
  obtained items, quantities) when it differs from the backend's copy,
  which rates pages like Barrows as a whole.
- **Automatic after the first import.** The player reads their log once
  and presses the import button once. After that the panel syncs by
  itself a few seconds after log reads settle (new imports, estimates,
  still-hunting rows and page reads), kill counts from chat are sent in
  batches to `/update-kc`, and tracked drops update their page on the
  backend. The button then just shows "Up to date" or "Syncing...".
- **Failing closed.** If the cache layout isn't recognised, import is
  disabled with a message. If a drawn page doesn't match its cache
  entry, that page is skipped. Only (item, source) pairs from the
  `/drop-rates-catalog` are offered, and the server skips anything the
  account already has a row for.
- **Other players' logs.** A collection log opened from someone else's
  adventure log in a POH is ignored. The owner is detected the same way
  as in RuneLite's Chat Commands plugin.

`BackfillPlanner`, `KillCountMessage` and `LogPageSnapshot` are pure
logic with unit tests (`./gradlew test`).

### Development

`./gradlew build` compiles against the real `runelite-client` and runs
the tests. `./gradlew run` starts a RuneLite dev client with the plugin
loaded (`LuckTrackerPluginTest`).

### Settings sync

Both visibility settings are sent to `/update-settings` when they
change, and once for each account that hasn't synced them yet
(per-account config key `syncedSettings`). With *Show my log on the
website* off, the plugin's own panel and collection log check keep
working because they read your drops with your install token
(`POST /get-player-luck`), not by name.

The backend URL and publishable key are hidden config items
(`apiBaseUrl`, `publishableKey`) that default to the live backend.

### Known limitations (by design, not bugs)

- **Only tracks drops that follow a kill-count message within 60
  seconds** (10 minutes after a raid). Boss drops work well since RuneLite prints kill count on
  every kill. Non-boss collection log sources — clue scrolls, skilling
  pets, minigame-specific unlocks — don't print a kill-count message,
  so this plugin currently has no way to know which "source" to
  attribute those drops to, and silently skips them (logged at debug
  level, not submitted). Extending this to other source types would
  need a per-activity detection strategy, not just this one regex.
- **Imported items never get a drop KC.** There is no way to
  retroactively know what KC you were at for collection log items you
  already have when you install this plugin — that data simply doesn't
  exist anywhere to recover. Imported items are recorded as obtained
  with no KC, flagged `is_backfilled`, so the website never shows them
  as a real percentage. Flat-rate items with a KC snapshot get a
  separate, clearly marked estimate from their count instead.
- **KC snapshot header reads are verified in-game** for Barrows,
  Brutus, Royal Titans, Moons of Peril, Scurrius and Tempoross. A page
  whose header doesn't parse logs its header text at debug level and
  imports without snapshots.
- **The import needs you to click through the log pages.** The game only
  sends a page's contents when that page is opened, so there's nothing
  to read until you open it. The panel lists which pages are still
  needed.
- **Shared items can't be imported automatically** (see above). Add them
  with the single-item dropdown if you know which boss they came from.
- **Item name → ID resolution** matches the chat message's item name
  against the collection log's own items, then falls back to
  `itemManager.search()` (tradeables only). A name that matches nothing,
  or several log items on different pages, is skipped with a warning
  rather than submitted with a guessed ID.
- **Raid estimates assume a typical raid.** Raid uniques are stored as
  `points_based` rates with an assumed points total or team size (see
  the `assumption` note on each row in
  `osrs-luck-database/data/manual_drop_rates.json`), because the plugin
  doesn't read your actual raid points. Entry modes aren't tracked.
