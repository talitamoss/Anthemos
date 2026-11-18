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
- **Questions:** "What's on tomorrow?", "When's the dentist?", "Am I free Friday afternoon?"
- **After events:** "How was the dentist?" within three hours of the end, as a prompt (same limits; "Stop asking this" stops them all).
- Later: sync to the person's own server (CalDAV), which Proton can subscribe to.

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
- **Updates itself** (`update/Updater.kt`): on opening, Speak checks `anthemos.org/builds/anthemos2-latest.json` and shows "Update ready" with one button. It downloads the APK, checks its SHA-256 and installs it through Android's package installer. The first time, Android asks to allow "Install unknown apps" for Anthemos 2. CI signs every build with one fixed key (secrets `ANTHEMOS_KEYSTORE_B64` and `ANTHEMOS_KEYSTORE_PASSWORD`) and numbers it 100 + the run number. Before this, each build had a throwaway key, so a new build wouldn't install over the old one.
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

**What the phone already knows** (opt-in, each one separately):
- **Screen time** (alpha 5, `sense/PhoneSense.kt`): from Android's usage access, turned on in Settings from a card on Speak. Android greys out the switch for apps installed outside a store until App info, ⋮, Allow restricted settings; the card says so after the first try, with an App info button. "How much screen time today?" answers with the total and the top three apps; each morning yesterday's total is kept as an entry (source "phone").
- **Sleep, estimated** (alpha 5): the longest gap in phone use overnight (6 pm to 1 pm, three hours or more; glances under five minutes ignored). The morning prompt says "Phone down at 11:10 pm, up at 6:40 am. How did you sleep?" and keeps those hours if the answer gives none. "How long did I sleep?" answers from it.
- **Location:** places rather than raw tracks: home, work, "a new place", time spent, from the phone's own location (GrapheneOS has its own network location). OsmAnd tracks for walks and rides.
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
