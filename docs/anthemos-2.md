# Anthemos 2.0

Anthemos 2.0 is a voice-first tool for recording a life and getting things done, with very few screens. You talk, and it writes down what you said, works out what you meant, and either records it or acts on it. Everything stays yours: it is kept encrypted on the phone, signed, synced to your pod, and shared with Kronk only when you choose.

This branch starts from `smart-anthemos`, which added the Direct AI chat screen. It reuses the 14 kinds of entry and the encrypted storage from 1.x.

## What it does

Two kinds of request, two rules:

- **Records** ("I just had lunch, chicken and a salad") save immediately. Anthemos reads back "Lunch logged", and "undo" takes it back.
- **Actions** ("Call Sam") ask first: "Calling Sam?", and you say "yes". Anything that leaves the phone always confirms first: calls, messages, posts to Kronk.

Every entry keeps your exact words next to what Anthemos understood. A better model later can re-read the history and find more in it.

## Words first

Fixed kinds (Food, Water, Caffeine...) lock us in: a sentence holds several things, and life doesn't fit a list decided in advance. So:

- **The words are the record.** Each thing said is one entry: the time and exactly what was said (`voice/Entry.kt`).
- **Readings sit on top.** A reader finds the items in the words: "chicken sandwich (lunch)", "salad", "coffee". Each item has a loose `kind` (food, drink, activity, call), a `label` in the person's own words, and a wider `group` ("flat white" is a coffee). Labels are open; nothing is a closed list. Each entry records which reader made its items, so a better reader can redo them.
- **Recall reads both.** Questions are answered from the items, falling back to the words.
- **Changing an entry** edits its words, then reads them again (`voice/Change.kt`): "change the coffee to tea", "no, it was tea not coffee", "make that two", "delete the coffee", "the walk was at seven", "that was yesterday". A thing named is found in the last week's entries, newest first; "that" means the last one. When more than one of today's entries fits ("the coffee" with two coffees), it asks "Which coffee, 8:00 am or 11:00 am?" and takes a time, "first", "last", or a tap. Forms that could just be something to keep ("the meeting was at three") are kept as said when nothing matches.
- The 1.x kinds become views over entries rather than storage.

## Where the thinking happens

Not on a big model on the phone, and not on a server we run for everyone: on **each person's own server**, the same one that holds their pod. Hosting and setting that up is the service people pay for. The phone does what it can alone (speech, rules, simple questions) and sends the rest to its owner's server: Whisper for harder speech, a language model as the better reader and for harder questions. No one else's server sees the words.

## Hey Poly

The **Hey Poly** switch in Settings (`ui/settings/sections/HeyPolySetting.kt`) turns on listening for one phrase, all the time, until it's switched off (`voice/WakeService.kt`). On hearing it, a short tone, and Speak opens listening, over the lock screen if need be. "Anthemos" isn't a word the speech model knows, so the phrase is Hey Poly (also heard as Polly, Pauly, Paulie).

- **Nothing is kept or sent.** Audio is only checked for the phrase, on the phone, by the same Vosk model Speak uses (loaded once for both, `voice/SpeechModel.kt`).
- **Light on battery:** each fifth of a second is measured first, and only sound louder than the room's own noise goes to the recogniser (`SoundGate`), which knows only the phrase. It lets go of the microphone during calls, on battery saver, below 15% (unless charging), and while Speak is listening.
- **Android's limits:** a foreground service with a notification, and GrapheneOS's microphone dot, while it runs. It can only take the microphone while Anthemos is on screen, so after a restart a notification asks for one tap. Opening Speak from the background needs "Display over other apps", asked for when it's switched on; without it, hearing the phrase shows a "Tap to talk" notification.

## Commands

Everything Anthemos understands is organised by focus, so what it does can be read in one place:

- A **focus** is one area of what it does, each in its own file in `focus/`: `LogFocus`, `NotesFocus`, `ChangeFocus`, `QuestionsFocus`, `CalendarFocus`, `CallsFocus`, `PhoneFocus`, `PlacesFocus`, `KronkFocus`, `MediaFocus`, `HomeFocus`, `ClockFocus`, `DeviceFocus`, `WeatherFocus`.
- Each focus lists its **commands**. A command has an id (`calendar.add`), one line on what it does, example sentences, how it is recognised, and what it does then. Everything a focus does sits in its file.
- **`voice/Commands.kt`** gathers the focuses and holds **one order** in which commands are tried. It crosses focuses on purpose: "remind me to call Dad" is the calendar before it is a call, "this is home" is a place before it is something to log, and anything left is logged.
- **`focus/Turn.kt`** is what a focus can do while handling one thing said: reply, ask something and wait for the answer (`Waiting`, with buttons as `Choice`s; `confirm` for yes or no), ask a prompt, need location. Speak (`ui/voice/VoiceViewModel.kt`) provides it and does nothing else: listening, replies, questions waiting, prompts.
- **`CommandsTest`** checks that every example lands on its own command and that every command is in the order.

To add a command: add it, with examples, to its focus and to the order. To add a focus: a new file in `focus/`, listed in `Commands.focuses`. The test shows if a new command takes over another one's sentences.

## Prompts

Anthemos asks about **gaps, not a script** (`prompts/PromptRules.kt`). A prompt fires only when something usual is missing from today:

| Prompt | When | Asked if |
|---|---|---|
| How did you sleep? | 7:00–11:00 | no sleep recorded today |
| Had breakfast yet? | 8:30–10:30 | no breakfast |
| What did you have for lunch? | 13:00–14:30 | no lunch |
| Had any water today? | 14:00–16:00 | none today, and water is usual (3 of the last 14 days) |
| Get outside or move today? | 16:30–18:00 | no activity, and activity is usual |
| What did you have for dinner? | 19:00–20:30 | no dinner |
| Anything from today you want to keep? | 20:30–21:00 | always |
| Anything to remember from the call with X? | after a call made through Anthemos ends | always |

- At most three a day; quiet from 21:00 to 7:00; each at most once a day.
- Three unanswered in a row rests a prompt for a week; "Stop asking this" ends it.
- Prompts arrive as notifications (checked every 15 minutes on the phone, no Google services). Tapping opens Speak listening; the answer is read in the light of the question ("seven hours" to "How did you sleep?" is sleep) and keeps the question with the entry.
- **One follow-up per entry** when an obvious detail is missing: how long a walk was, how many hours of sleep, how much something bought cost. The answer fills in that entry.
- Later, the reader on the person's own server words prompts and follow-ups from context ("the bus was late again, third time this week").

## Calendar

Proton Calendar is end-to-end encrypted and doesn't share its events with Android's calendar store, so no other app can read or add to it. Anthemos keeps **its own calendar in its encrypted store** (`calendar/`), and hands each event to Proton with one tap ("Also add to Proton", Android's standard new-event request; it works if Proton accepts it).

- **Add by voice or typing:** "add dentist Thursday at three", "put lunch with Sam tomorrow at half past twelve at the bakery", "I've got the market on the twenty-first". Confirmed before saving.
- **Reminders:** "remind me to call Dad at five", "in twenty minutes". Anthemos sets off its own alarm and notification (exact alarms, set again after a restart). A "call" reminder opens Anthemos ready to make the call.
- **Questions:** "What's on tomorrow?", "When's the dentist?", "Am I free Friday afternoon?", "What's in my calendar today?"
- **Reading Proton:** Proton can share a calendar as a read-only link ("Share with anyone", full view). Paste it into Speak, or copy it and say "connect my calendar"; the link is kept encrypted (`calendar/LinkedCalendar.kt`) and the calendar fetched at most every ten minutes, with the last copy kept for offline. Questions answer from both calendars; an event in both is told once. `calendar/Ics.kt` reads the file, with weekly, monthly and yearly repeats, skipped and moved occurrences. "Disconnect my calendar" forgets the link.
- **Adding with Proton linked:** the event is kept in Anthemos and Proton's new-event screen opens filled in; a tap on save keeps it there too. Proton lets no app save for you.
- **After events:** "How was the dentist?" within three hours of the end, as a prompt (same limits; "Stop asking this" stops them all).
- Later: sync to the person's own server (CalDAV), which Proton can subscribe to.

## Music

Through whichever app plays music on the phone, with the phone's own media controls; nothing to sign in to (`focus/MediaFocus.kt`, `media/Music.kt`). "Play", "pause", "skip", "previous song", "turn it up". "Play Bonobo" hands the words to the music app's search, which plays the best match (the first time, Android may ask which app). Replies are shown, not said, so Polly doesn't talk over the music. "I played tennis" is still something that happened.

## Phone

The phone's own things, by voice.

- **Timers and alarms** (`clock/Clock.kt`, `focus/ClockFocus.kt`) are set in the phone's Clock app (Android's standard requests), so they ring as usual with Anthemos closed. "Set a timer for ten minutes", "pasta timer for 12 minutes", "wake me up at 7 every weekday", "stop the timer", "show my alarms" (cancelling one is picked there). An alarm with no am or pm is for the morning.
- **Torch, apps, battery** (`device/Device.kt`, `focus/DeviceFocus.kt`): "turn on the torch", "open Signal" (by the app's name; if no app has it and Home Assistant is connected, it goes there: "open the garage"), "how much battery".

## Weather

Open-Meteo's forecast (`weather/Forecasts.kt`): free, no account, nothing kept about who asks. The phone's position goes rounded to about a kilometre; a place said by name is looked up first. "What's the weather like", "will it rain tomorrow", "how hot will it be on Saturday", "what's the weather in Melbourne this weekend" (`weather/Weather.kt` reads the question and words the answer). Inside is the house's: "how cold is it inside" goes to Home Assistant.

## Home

The house, through Home Assistant (`home/HomeAssistant.kt`, `focus/HomeFocus.kt`). Added in Settings: the address used at home or over the VPN (a bare IP gets `http://` and `:8123`), and a long-lived token from Home Assistant's profile page, under Security; checked before it's kept, encrypted. Anthemos only decides the words are for the house ("turn off the lounge lights", "set the heating to 20", "lock the front door", "is the back door locked", "what's the temperature inside") and hands them to Home Assistant's own Assist (`/api/conversation/process`), which knows the devices and rooms, does it, and its answer is said back. Nothing is asked first (Tal's choice). A follow-up within five minutes carries on Assist's conversation. A question nothing else answers is tried on Assist before "I can't answer that yet". Plain http is allowed (`res/xml/network_security_config.xml`) since a home Home Assistant usually has no certificate; the VPN encrypts it away from home.

## Places

Location stays on the phone. No map service is asked where anything is or what it is called (Tal, 2026-11-18: not OSM's public server).

- **The trail:** "start tracking" and "stop tracking". A foreground service (`place/LocationService.kt`, with a notification while it runs) takes a reading every two minutes or on moving 50 m, from plain GPS and the phone's own network location (GrapheneOS has no Google location service). Readings are kept in the encrypted store (pluginId `trail`). Time spent in one spot, ten minutes or more within 150 m, becomes an entry the phone noticed: "At home, 6:00 pm to 8:10 am" (`place/Geo.kt`).
- **Places are named by voice:** "this is home", "this is mum's place", "call this place the gym", "save this place as work". "This is..." only names a place when it sounds like one, so "this is my third coffee" is still a coffee.
- **Questions:** "Where am I?" (a named place, or how far you are from the nearest one), "Where was I yesterday?"
- **Later:** walks and rides as trips between stays; OsmAnd track recording ("start recording"), imported for the full route.

## Kronk

Anthemos links to Kronk once, and from then on what it shares is signed by the phone (Kronk's `docs/spaces/map.md`, Live location, and `docs/decisions.md`, 2026-11-18).

- **Connect:** "connect to Kronk" opens Kronk's sign-in in the browser (OAuth; Anthemos registers itself as an app). Coming back (`anthemos://kronk`), it makes a P-256 key in the Android Keystore, which never leaves the phone, and gives Kronk the public half, signed to prove it holds the key. The token is kept in encrypted preferences (`kronk/Kronk.kt`). The server is shadow.kronk.info until live location is released to kronk.info.
- **Live location:** "share my location with Ana for an hour", "let Dad see where I am until five", "share my live location with Ana and Sam", "share live tracking with Jye on Kronk". It finds them among your Kronk Mates, asks "Share your exact location with Ana Lee until 4:00 pm?", and only on yes starts. An hour unless said, a day at most. The location service then sends a signed reading about every 30 seconds; the notification says who sees it and has Stop sharing. "Stop sharing" or "stop sharing with Ana" ends it, as does Stop in Kronk's Account & Security.
- **On Kronk** the Mates named get a nudge and see you live on the Map, exact, with "From Anthemos, signed by their phone". Kronk keeps only the latest point, until the share ends. For now Kronk's server can read it as it passes; next, it is encrypted to viewers who use Anthemos.
- **Later:** the same link carries other shares (treks, sleep, mood) as signed, revocable permissions.

## Pieces

1. **Ears.** Anthemos registers as the phone's default assistant (`VoiceInteractionService`), so a headset or steering-wheel button opens it. Push to talk first; a wake word is optional later. Speech becomes text on the phone (Whisper via whisper.cpp, or Android's on-device recogniser).
2. **Mind.** A small language model on the phone turns the text into one structured result: a record (kind + fields) or an action (verb + target). Its output is held to a JSON shape built from the kinds' field definitions. Common phrases ("call X", "note ...") take a fast path without the model.
3. **Hands.** These carry out actions: call a contact (`CALL_PHONE` plus a contacts lookup), take a note, set a timer. Later: text messages and posting to Kronk.
4. **Voice back.** Short spoken confirmations through Android text-to-speech.
5. **Memory.** The existing encrypted `data_points` store. Each entry gets its transcript, a timestamp and a signature from a key in the Android Keystore.
6. **Pod and Kronk (later).** Sync to the person's pod. Share chosen entries through the membrane as signed, revocable grants.

## Build order

1. **Voice loop.** Assistant role, push to talk, on-device speech to text, spoken reply. Shows the transcript only.
2. **Three requests.** Log food or an activity, call a contact, take a note. Rules first, then the on-device model.
3. **Store and day view.** Save to the encrypted store with transcript and signature. One day timeline screen. Undo.
4. **Spoken questions** about the record.
5. **All 14 kinds** through the model.
6. **Pod sync**, then **Kronk sharing**.

The test phone is a Pixel 8 or newer, so on-phone AI is the default and the server is only a fallback.

## Where it stands

**alpha 1 and 2** cover steps 1–3, without a model yet:

- **Speak** is the home page: the middle button in the bar. Tap the big button and talk. It shows what it heard and says what it did.
- **Speech to text** (alpha 2) is Vosk with its small English model inside the app (`voice/Listener.kt`), so it works on GrapheneOS, which has no Google speech service, and nothing leaves the phone. The model is fetched at build time (`fetchVoskModel`) and is not in git. Alpha 1 used Android's recogniser and failed on GrapheneOS with error 5. Whisper can replace Vosk later for accuracy. The APK is arm64 only.
- **Spoken replies** use the phone's text-to-speech. If there is none (GrapheneOS ships none; RHVoice or eSpeak can be installed), replies are only shown, and a call still asks yes or no.
- **Understanding** is the rule-based fast path in `voice/RuleParser.kt`. It covers food ("I just had lunch, chicken and a salad"), water, coffee and tea, alcohol, movement ("went for a walk for 40 minutes"), notes ("note that ..."), calls ("call Sam") and "undo". Anything else is kept as a Journal entry with your words. Tests are in `RuleParserTest`.
- **Records** are saved to the existing encrypted store with `source = "voice"` and the transcript in `metadata`, using the same value keys as manual entries. Today's entries show under the button, with **Undo last**.
- **Calls** look up the contact, ask "Call Sam?" out loud, then listen for yes or no (buttons too), and only then dial.
- **Opened to listen:** a headset or Bluetooth long press (`VOICE_COMMAND`), the assist gesture if Anthemos is chosen for it, or the `com.anthemos.LISTEN` intent all open Speak listening.
- **Updates itself** (`update/Updater.kt`): on opening, Speak checks `anthemos.org/builds/anthemos2-latest.json` quietly and puts a dot on its Settings button when a newer build is out. Settings shows the version, "Check for updates", and "Update ready" with one button (`update/UpdateCard.kt`). It downloads the APK, checks its SHA-256 and installs it through Android's package installer. The first time, Android asks to allow "Install unknown apps" for Anthemos 2. CI signs every build with one fixed key (secrets `ANTHEMOS_KEYSTORE_B64` and `ANTHEMOS_KEYSTORE_PASSWORD`) and numbers it 100 + the run number. Before this, each build had a throwaway key, so a new build wouldn't install over the old one.
- **Installed beside 1.x** as `com.anthemos.app.two` ("Anthemos 2"), so 1.x data is untouched. CI publishes each build as `anthemos.org/builds/anthemos2-latest.apk`.

**Next:** app commands: "play X on Spotify" (Android's play-from-search), pause and skip for whatever is playing, start and stop track recording in OsmAnd (its intent API), "open X", timers.

**alpha 3** (words first):

- **Several things per sentence**, kept as one entry with the words and the items found (`voice/RuleReader.kt`). Spoken numbers ("forty five minutes") count.
- **Questions, answered in words and aloud** (`voice/Questions.kt`): "When did I last call Dad?", "How many coffees this week?", "How much water today?", "Did I have a coffee today?", "What did I eat yesterday?". Periods: today, yesterday, this or last week or month, this year, the last N days.
- **Calls are kept as entries** when dialled, so they can be asked about. Only calls made through Anthemos are known; the phone's call log could be added.
- **Calling copes with mishearing**: call, ring, phone, "give Dad a call", and "call" heard as "cool" or "coal" (then only if a contact matches; otherwise the words are kept).
- **Who "Mum" is**: taught names ("Dad is John Moss", or it asks when it doesn't know), contact nicknames, relations on a contact card, and names one letter off. Taught names are in app-private preferences for now.

**alpha 4:** prompts and follow-ups (above); money ("fifteen dollars fifty", "$12") and "How much have I spent this week?".

**alpha 5:** calendar (above); **typing** as well as talking (the same path; typed turns are answered on screen, not aloud); **screen time and estimated sleep** (below, the first two).

**alpha 6:** changing entries by voice or typing (above); a quieter Speak screen (no example text, Undo only right after something is kept, one line per entry); help with Android's "restricted settings" block on usage access for apps not from a store.

**alpha 7:** changes ask which one when several of today's entries fit. The own-server and shared-server reader is parked for now (Tal, 2026-11-17); the rules keep growing meanwhile.

**alpha 8:** a waiting prompt or follow-up lasts three minutes, and only something to keep answers it; a follow-up answer that holds its own things ("I had a coffee") is a new entry, not added to the old one. "A coffee with lunch" is the coffee, not lunch as well.

**alpha 9:** listening goes on through short pauses and ends after about two seconds of quiet (or Stop), up to a minute; eight seconds of nothing at all ends it. A time said is when it happened ("a coffee at about 8 am"; a time still to come today is taken as the morning one), and parts said with different times become separate entries ("woke up at 5:30, having a coffee now"). Waking and going to bed are read as moments ("woke up", "went to bed"). Undo takes back everything from the last thing said.

**alpha 10:** more follow-ups: "What did you have?" after a meal with nothing in it, "How many hours did you sleep?" after waking, "How did you sleep?" after hours with no word for how. A check before keeping doubtful words: when nothing is found in them and either the recogniser was unsure of them (Vosk word confidence) or they sound like something eaten or drunk ("I have a thief"), it asks "Try again, or keep it as a note?".

**alpha 11:** nothing is a note unless asked for ("take a note", "note to self", "remember", "write down", "dear diary"). Anything else with nothing found in it asks "Try again, or keep it as a note?"; something found in words the recogniser wasn't sure of asks "log coffee? Say yes, or try again."

**alpha 12:** places and the trail, and live location through Kronk (above, Places and Kronk).

**alpha 14:** Hey Poly (above).

**alpha 15:** "Hey Polly" at the start of anything said is dropped before it's
read, so questions and the calendar hear it too. Undo takes "undo the last
entry", "delete my last one" and the like. "What time did I go to sleep
yesterday", "when did I wake up", "what time did I have coffee": going to
sleep is said ("went to bed") or worked out from a wake-up and the hours
slept, and a night belongs to the evening it began; the phone's guess
answers only when nothing was said. A question nothing answers is said so,
never logged (`questions.other`). Proton Calendar read through its share
link, and adding opens Proton filled in. Music: play, pause, skip, louder,
"play" anything.

**2.16:** versions are now 2.<release>.<build> (2.16.132): the release
(`anthemosRelease` in `gradle.properties`) goes up with each batch of
features, where the alphas counted before; the build is CI's run count.
Hey Poly and updates moved from Speak to Settings, with "Check for
updates"; Speak's Settings button shows a dot when an update is ready.

**2.17:** Home Assistant (above).

**2.18:** Timers, alarms, the torch, apps, battery, and the weather (above).

**alpha 13:** sharing also takes "tracking", "position" or "where I am" for location, and "on Kronk" at the end.

**What the phone already knows** (opt-in, each one separately):
- **Screen time** (alpha 5, `sense/PhoneSense.kt`): from Android's usage access, turned on in Settings from a card on Speak. Android greys out the switch for apps installed outside a store until App info, ⋮, Allow restricted settings; the card says so after the first try, with an App info button. "How much screen time today?" answers with the total and the top three apps; each morning yesterday's total is kept as an entry (source "phone").
- **Sleep, estimated** (alpha 5): the longest gap in phone use overnight (6 pm to 1 pm, three hours or more; glances under five minutes ignored). The morning prompt says "Phone down at 11:10 pm, up at 6:40 am. How did you sleep?" and keeps those hours if the answer gives none. "How long did I sleep?" answers from it.
- **Location** (alpha 12): stays at places named by voice, from the trail when it's on (above, Places).
- **Steps:** the phone's step counter sensor, no Google Fit.

**Not yet:** signatures on entries, the on-device language model, spoken questions, the full default-assistant role (`VoiceInteractionService`), a wake word, pod sync.

## Outside things we need

- [ ] **Phones.** A Pixel 8 or newer (have one), plus a second Android phone for testing if possible.
- [x] **Builds.** GitHub Actions builds and publishes every push to `anthemos-2`, signed with the fixed key. The keystore also needs a copy in ProtonPass.
- [ ] **Distribution.** Sideload first. Later: a Play Console account and a privacy policy. Play limits SMS permissions to default messaging apps, so calls are easy and texts are harder.
- [ ] **Speech model.** Whisper (base or small, about 75–250 MB). MIT licence.
- [ ] **Language model.** About 1 GB on the phone. Licence check: Qwen is Apache 2.0; Gemma and Llama have conditions.
- [ ] **Model hosting.** Somewhere for the app to download models from on first run, rather than bundling them in the APK.
- [ ] **Server.** api.anthemos.org is up with Ollama, but the droplet is small (2 CPU, 4 GB). Decide whether to upsize it as a fallback or stay phone-only.
- [ ] **Pod.** pod.anthemos.org returns 502. Repair it, or choose which pod server to run. Add backups.
- [ ] **Identity.** Choose how IDs and keys work (`did:key` or `did:web`). Keys live in the Android Keystore.
- [ ] **Wake word (optional).** openWakeWord is free; Porcupine needs a licence.
- [ ] **Car.** Headset and wheel buttons need no approval. Android Auto messaging needs Google's approval, so it comes later.
- [ ] **Domain.** Keep anthemos.org renewed.

## Open questions

- Which pod server, and does each person host their own or do we host for them at first?
- Which language model: smallest that understands the kinds well enough?
- Does a PineTime button make a good trigger?
