# FM Team Hundo Speaker Script

Target duration: about 10 minutes. Hard cap: 15 minutes.

Use the optional lines only if you are comfortably ahead of time.

## Timing At A Glance

| Slide | Topic | Target |
| --- | --- | ---: |
| 1 | Title and premise | 0:45 |
| 2 | Event experience | 1:05 |
| 3 | Player setup | 1:05 |
| 4 | Emulator capture | 1:10 |
| 5 | FM_Sentinel | 1:00 |
| 6 | Server | 1:15 |
| 7 | Architecture diagram | 1:25 |
| 8 | Live views and broadcast | 1:30 |
| 9 | Why it works | 0:55 |

Planned total: about 10:10.

## Slide 1: FM Team Hundo

FM Team Hundo is a live team race built around Yu-Gi-Oh! Forbidden Memories. The goal is simple to understand: each team is trying to complete a shared card library.

The project makes that possible by connecting four worlds that usually do not talk to each other: a player emulator, an official scoring website, real-time team dashboards, and a broadcast setup for viewers.

This talk is about how those pieces work together to create one full event experience.

## Slide 2: The Event Experience

There are three main groups to think about.

First, players. They mostly play the game normally. The software watches for the moments that matter, like card drops, fusions, ritual summons, and starchip totals.

Second, teams. A team is not just a list of players. It has one shared library. When any member earns a valid card, the whole team benefits.

Third, viewers and commentators. The project turns small game events into visible updates: team pages change, widgets update, and broadcast tools can highlight a player when something important happens.

The key idea is that FM Team Hundo is not only a tracker. It is the connective tissue between play, scoring, and the show.

## Slide 3: Player Setup: Trust Before Tracking

Before anyone can submit progress, the system needs to know who they are.

Players sign in with Twitch on the FM Team Hundo website. That creates or updates their profile, connects them to a Twitch identity, and places them on a team.

Then they download a credentials file. That file is used by FM_Sentinel, the local bridge, so the server can tell which player is submitting updates.

The Twitch side matters for another reason too: participants are expected to stream gameplay from their registered account or configured alt account. That gives the event reviewability and gives the restream a live source to use.

Optional: This is the trust layer. It keeps normal participation simple, while still giving organizers a way to connect progress to real players and streams.

## Slide 4: Game Events Leave the Emulator

The first technical handoff happens inside the emulator.

FM Team Hundo supports BizHawk through a plugin, and DuckStation through a source patch. Both do the same kind of job: they watch the running game for important memory changes.

When the player receives a card drop, creates a fusion, performs a ritual summon, or changes starchips, the integration sends a small local message.

The important non-technical point is this: ordinary progress is detected as the game is played. A player should not have to type in every card manually, and the team library should update from the real gameplay stream of events.

## Slide 5: FM_Sentinel: The Local Bridge

FM_Sentinel sits between the emulator and the website.

On startup, it reads the credentials file and asks the server, "Are these credentials valid?" It also checks protocol compatibility for stamped release builds, which helps prevent old components from quietly talking to new ones incorrectly.

Once the emulator connects, FM_Sentinel receives event messages locally. It batches them, sends them to the collection API, and retries if the server connection fails.

From the player's point of view, this is the local command window that confirms everything is connected and prints readable feedback as cards and starchips are detected.

## Slide 6: The Server: Official Source of Truth

The server is where local events become official event state.

It is a Spring and Vaadin application. Vaadin provides the website pages, while Spring handles the API, security, persistence, and WebSocket updates.

When FM_Sentinel posts updates, the server checks the API key, rejects impossible card IDs and unobtainable cards, stores accepted updates, and stamps them with server time.

Then the game state service rebuilds each team's library. It calculates what is still missing, how many unbuyable cards remain, the cost of buyable cards, special rules like Blue-Eyes Ultimate Dragon and Gate Guardian, and whether a team has completed the hundo.

Finally, it broadcasts live updates to anything listening: website views, LiveStats, and broadcast automation.

## Slide 7: How the Parts Work Together

This diagram is the whole event loop.

Start on the left with a player. Their emulator detects game events through the plugin or patch. Those events go to FM_Sentinel on the same machine, and FM_Sentinel sends validated updates to the official backend.

The backend stores the record in the database, talks to Twitch for identity and optional VOD lookup, and pushes live state out to public and production-facing views.

On the right side, the broadcast stack uses two kinds of input. MediaMTX carries the live video paths, while the OBS controller listens for team events and tells OBS when to change scenes, show alerts, or focus audio.

The result is one loop: gameplay becomes official data, official data becomes live surfaces, and live surfaces become a better viewer experience.

## Slide 8: Live Views and Broadcast Automation

There are several live surfaces, each serving a different audience.

The website has a home page for team comparison, team pages for library grids and latest acquisitions, player pages for individual activity, and OBS-friendly stats widgets.

LiveStats is a separate commentary tool. It connects to the same server APIs and firehose updates, but it is shaped for the commentary desk: compact panels, player rows, library stats, and highlighted recent activity.

The OBS controller is the production automation piece. It can build managed scenes for all streamers, teams, and individual players. It watches MediaMTX for active streams, listens to the team firehose, and reacts when an acquisition should become a broadcast moment.

That means a new drop can move from a player's game, to the server, to a commentator dashboard, to a scene cut or overlay without someone manually reassembling the show every time.

## Slide 9: Why It Works as a Full Event System

The project works because the responsibilities are separated, but connected.

The emulator integration is close to the game. FM_Sentinel is close to the player machine. The server is the official scoring authority. The website and LiveStats present the race. The OBS stack turns selected moments into a show.

There is also release discipline around this. CI builds the BizHawk plugin, FM_Sentinel, the server jar, LiveStats, and the DuckStation patch package. The shared protocol version protects the wire format between the most important pieces.

So the final picture is not just "a tracker." It is an event platform: capture, verify, score, display, and broadcast in one loop.
