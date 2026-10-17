# Nuvio To Zidoo

A small Android TV app that makes [Nuvio](https://github.com/NuvioMedia/NuvioTV)
play in the **Zidoo player**, with the things Nuvio's own player does:

- **Resume where you stopped.** Starts at Nuvio's resume point, and saves your
  place every 10 seconds, so stopping, pausing for a long time or switching the
  box off resumes in the right spot. If the Zidoo player starts a big file at
  0:00 anyway, it is moved to the resume point.
- **Up Next.** When an episode reaches its credits you get a 5 second notice,
  then the episode ends and Nuvio fetches the next one. Before it plays, a
  countdown screen (10 seconds by default) shows **Play now** and **Cancel**.
- **Skip intros and recaps** (and, if you want, film credits) using the
  timestamps Nuvio already knows.
- **Watched status.** Nuvio is told when a title finished, so it marks it
  watched and syncs Trakt as usual.
- **Streams that need special headers** (some addons) still play: the app relays
  them to the Zidoo player on the box itself.

It works like PlexToZidoo does for Plex: Nuvio thinks it is talking to an
external player such as MX Player, and this app hands the stream to the Zidoo
player and watches it through the Zidoo's built-in control API.

Nuvio itself is not modified, so Nuvio keeps updating itself as normal and keeps
your account, addons, Trakt and everything else.

## Install

On the Zidoo, open the **Downloader** app and enter:

    https://github.com/Basche1490/NuvioToZidoo/releases/latest/download/nuvio-to-zidoo.apk

That address always gives the newest build, and each build installs over the
previous one.

## Set up

1. **Nuvio → Settings → Playback → Player →** External.
2. **Nuvio → Settings → Playback → External Player →** turn on *Send intro and
   outro timestamps*. Without it, skipping and Up Next at the credits can't work
   (Up Next still works when an episode plays to the very end).
3. **Nuvio → Settings → Playback →** turn on *Auto-play next episode*.
4. Play something in Nuvio. When Android asks which app to use, pick
   **Nuvio To Zidoo** and choose **Always**.
5. **Zidoo Quick settings → Playback → Play mode →** Single file.

If Android never asks and the Zidoo player opens directly, the Zidoo player is
set as the default for videos: go to *Settings → Apps → (the Zidoo player app)
→ Open by default → Clear defaults*, then play again.

The app's own screen (open **Nuvio To Zidoo** from the home screen) shows
whether a Zidoo player was found, the same setup steps, and the settings below.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Skip intros | On | Jumps over openings Nuvio has timestamps for. |
| Skip recaps | On | Jumps over "Previously on…". |
| When an episode reaches its credits | Go to Up Next | Ends the episode after a 5 s notice so the next one starts. Rewind during the notice to keep watching. *Keep playing* waits for the real end. |
| Up Next countdown | 10 seconds | How long the next episode waits, with Play now / Cancel. Off, 5, 10, 15, 20 or 30 s. |
| Skip film credits | Off | Jumps to the end of a film at its credits (also skips post-credit scenes). |
| Remember where I stopped | On | This app's own resume points, used when they are further along than Nuvio's. |
| Zidoo player | Automatic | Current Zidoo player if the firmware has it, otherwise the classic one. |
| Wait for Nuvio before playing | Off | See "Episodes don't move on" below. |

## Troubleshooting

**Episodes don't move on to the next one (sometimes, or after a pause).**
On boxes Nuvio recognises as a Zidoo, Nuvio also follows the Zidoo player itself.
That tracking can give up after a pause or race this app at the end of an
episode; Nuvio then ignores the "finished" this app sends, so it doesn't play
the next episode. Your resume point is still right, because this app keeps its
own. Turning on **Wait for Nuvio before playing** fixes it by holding the first
start for about 15 seconds, until Nuvio's tracking steps aside (the Up Next
countdown covers this for later episodes). The app's screen says whether Nuvio
treats your box as a Zidoo.

**Nothing is skipped.** Turn on *Send intro and outro timestamps* in Nuvio. Not
every title has timestamps.

**Subtitles from Nuvio don't show.** The Zidoo player can't be handed subtitle
files from another app. Subtitles inside the video file work, and the Zidoo
player's own subtitle search works.

**"No Zidoo player was found."** The app only works on a Zidoo media player. The
current Zidoo player needs firmware 6.4.42+ (Z9X line) or 1.1.42+ (Z9X 8K line);
older firmware uses the classic player.

## Staying up to date

- **Nuvio** updates as it always does. This app doesn't need rebuilding for it.
- Every Monday the **Nuvio compatibility check** workflow reads the newest Nuvio
  code and checks it still talks to external players the same way. If Nuvio
  changed something this app relies on, it opens an issue in this repo.
- Any change pushed to `main` here builds a new APK automatically (**Actions →
  Build APK**) and publishes it at the Downloader address above. You can also run
  it by hand from the Actions tab.
- If a build fails, the end of its log is saved to the `ci-logs` branch.
- GitHub pauses scheduled workflows after 60 days without activity; re-enable
  *Nuvio compatibility check* from the Actions tab if that happens.

### Updating with Claude

Start a chat with Claude (with GitHub access to this repo) and paste:

```
Please check my Nuvio To Zidoo app, github.com/Basche1490/NuvioToZidoo, against the newest
Nuvio code at github.com/NuvioMedia/NuvioTV. Read CLAUDE.md and README.md first.
Run scripts/check-nuvio.sh against Nuvio, fix anything that changed, run the unit tests,
push to main, and tell me in plain language what changed and what to test on the Zidoo.
```

## How it works

Nuvio opens this app with the stream, the title ("Show - S01E05"), its resume
point and (if enabled) the intro/credits timestamps. The app picks the start
position, opens the Zidoo player with it, and reads
`http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus` once a second: to save the
position, to seek past intros (`ZidooVideoPlay/seekTo`), and to notice the
credits. When the player closes it answers Nuvio the way MX Player does
(`position`, `duration`, `end_by`), which is how Nuvio decides to save progress,
mark the title watched and play the next episode. When Nuvio then opens the next
episode within a few minutes, the countdown is shown first.

The APKs are signed with a fixed key that lives in this repo
(`signing/nuviotozidoo.keystore`, password `android`), like Android Studio's debug
key, so that every new build installs over the old one. It protects nothing.

## Credits

The Zidoo player launch follows [PlexToZidoo](https://github.com/bowlingbeeg/PlexToZidoo)
and the Jellyfin Zidoo edition; the playback monitor and resume guard are ported
from the Silo Zidoo fork, so this app is licensed AGPL-3.0 like Silo.
