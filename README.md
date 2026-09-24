# OSRS Collection Log Luck Tracker — RuneLite Plugin

Sends new collection log drops to your deployed backend as they happen,
so the website shows real data instead of the seed script from earlier
in this build.

## What this actually does

1. On login, calls `/register` once per account (result cached locally),
   getting back an `install_token`.
2. Watches chat for kill-count messages ("Your Zulrah kill count is:
   127.", "Your completed Tombs of Amascut: Expert Mode count is: 12.")
   to track current KC per boss or raid. `KillCountMessage` strips the
   colour tags the game puts around the number first.
3. Watches chat for the collection log popup message ("New item added
   to your collection log: X") and, if it happened within 5 seconds of
   a kill-count message (10 minutes after a raid completion, since raid
   loot is claimed from the chest later), resolves the item name to an
   item ID and calls `/ingest-drop` with the item, source, and KC at the
   time. Names are matched against the collection log's own items first,
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

## Collection log import

Earlier approaches to reading the whole log automatically broke when
game updates changed things underneath them. This one avoids relying
on anything fragile, and fails closed when something does change:

- **Reading a page.** After the game's own `COLLECTION_DRAW_LIST` script
  (2731) draws a page, the plugin reads the page title and the item
  widgets it just drew. Obtained slots are drawn at opacity 0. RuneLite's
  built-in Chat Commands plugin reads the "All Pets" page exactly the
  same way, so RuneLite itself keeps this hook working. Only pages the
  player opens are read; the plugin never opens the log or clicks
  through it.
- **Shared items.** The log fills a slot on *every* page that lists the
  item, whoever dropped it: a Godsword shard from Kree'arra shows on all
  four GWD pages. So an obtained slot only proves its source when the
  item appears on exactly one page. `CollectionLogIndex` reads the full
  page/item layout from the game cache (tab structs 471–475, params
  683/689/690, the same IDs the Collection Log plugin-hub plugin uses),
  and `BackfillPlanner` only offers single-page items. Shared items are
  listed as skipped and can be added one at a time from the dropdown.
- **Failing closed.** If the cache layout isn't recognised, import is
  disabled with a message. If a drawn page doesn't match its cache
  entry, that page is skipped. Only (item, source) pairs from the
  `/drop-rates-catalog` are offered, and the server skips anything the
  account already has a row for.
- **Other players' logs.** A collection log opened from someone else's
  adventure log in a POH is ignored. The owner is detected the same way
  as in RuneLite's Chat Commands plugin.

`BackfillPlanner` and `KillCountMessage` are pure logic with unit tests:
`./gradlew test --tests com.osrslucktracker.BackfillPlannerTest`.

## What I could and couldn't verify

**I could verify:** the `build.gradle` structure, group/version
conventions, and dependency versions were cross-checked against a real,
currently-merged Plugin Hub repository (fetched live from GitHub while
building this), not written from memory alone. The Java files also
passed a syntax check with `javac` — no brace/semicolon/typo-level
errors. This check caught a real bug during the backfill feature
addition (an edit accidentally truncated `ApiClient.java` mid-method),
which is exactly the kind of thing `javac` is good for catching even
without the real dependencies available.

**I could NOT verify:** this sandbox has no network access to
`repo.runelite.net` or Maven Central, so I was never able to run
`gradlew build` against the actual `runelite-client` jar. That means
the specific method signatures I'm relying on — `client.getAccountHash()`,
`itemManager.search(name)`, `itemManager.getItemComposition(id)`,
`ImageUtil.loadImageResource()`,
`configManager.getConfiguration()/setConfiguration()`, the injected
`OkHttpClient`/`Gson` — are based on stable, long-standing RuneLite API
patterns I'm confident about, but **not compiled against a real
version**. Treat this as a strong first draft, not a finished, tested
plugin. (Your Gradle sync + successful run of the core plugin already
confirmed the first three of those method calls are correct —
`itemManager.getItemComposition()`, used only in the new backfill
dropdown, hasn't been exercised yet.)

## Before you trust this, do this

1. Open the project in IntelliJ (RuneLite's plugin docs recommend
   IntelliJ Community + Java 11).
2. Let Gradle sync — this pulls the real `runelite-client` jar and will
   immediately surface any method that's actually named or shaped
   differently than I've assumed.
3. Fix whatever Gradle/the IDE flags red. Given the syntax is clean and
   the APIs used are all long-stable ones, I'd expect this to be small
   fixes, not a rewrite — but I can't promise zero errors without having
   compiled it myself.
4. Run the plugin against RuneLite's test client (the standard `Run
   test` Gradle task from the plugin template) before trusting it with
   your real account.

## Configuration

In RuneLite's plugin settings, fill in:
- **API base URL**: `https://YOUR_REF.supabase.co/functions/v1`
- **Publishable API key**: `sb_publishable_...` (the same one from the
  frontend's `.env.local` — never the secret key)

## Known limitations (by design, not bugs)

- **Only tracks drops that follow a kill-count message within 5
  seconds.** Boss drops work well since RuneLite prints kill count on
  every kill. Non-boss collection log sources — clue scrolls, skilling
  pets, minigame-specific unlocks — don't print a kill-count message,
  so this plugin currently has no way to know which "source" to
  attribute those drops to, and silently skips them (logged at debug
  level, not submitted). Extending this to other source types would
  need a per-activity detection strategy, not just this one regex.
- **Imported items never get a KC or a luck number.** There is no way to
  retroactively know what KC you were at for collection log items you
  already have when you install this plugin — that data simply doesn't
  exist anywhere to recover. Imported items are recorded as obtained
  with no KC, flagged `is_backfilled`, so the website never shows them
  as a real percentage.
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
