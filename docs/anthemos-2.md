# Anthemos 2.0

Anthemos 2.0 is a voice-first tool for recording a life and getting things done, with very few screens. You talk, and it writes down what you said, works out what you meant, and either records it or acts on it. Everything stays yours: it is kept encrypted on the phone, signed, synced to your pod, and shared with Kronk only when you choose.

This branch starts from `smart-anthemos`, which added the Direct AI chat screen. It reuses the 14 kinds of entry and the encrypted storage from 1.x.

## What it does

Two kinds of request, two rules:

- **Records** ("I just had lunch, chicken and a salad") save immediately. Anthemos reads back "Lunch logged", and "undo" takes it back.
- **Actions** ("Call Sam") ask first: "Calling Sam?", and you say "yes". Anything that leaves the phone always confirms first: calls, messages, posts to Kronk.

Every entry keeps your exact words next to what Anthemos understood. A better model later can re-read the history and find more in it.

## Organising entries

- **The kinds are the backbone.** The 1.x plugins each define a kind and its fields: Food, Water, Caffeine, Alcohol, Sleep, Movement, Meditation, Work, Social, Medical, Journal, Poo, Audio and Screen time. The voice chooses a kind and fills its fields. Fields you didn't mention stay blank; it doesn't quiz you.
- **Nothing is lost.** Anything that fits no kind is saved as a Journal entry with your words. A pattern that keeps appearing can become a new kind later.
- **Finding things back.** Ask by voice ("What did I eat yesterday?", "How much coffee this week?"), or look at one simple day screen that shows the day as a timeline.

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
- **Installed beside 1.x** as `com.anthemos.app.two` ("Anthemos 2"), so 1.x data is untouched. CI publishes each build as `anthemos.org/builds/anthemos2-latest.apk`.

**Next (alpha 3):** app commands: "play X on Spotify" (Android's play-from-search), pause and skip for whatever is playing, start and stop track recording in OsmAnd (its intent API), "open X", timers.

**Not yet:** signatures on entries, the on-device language model, spoken questions, the full default-assistant role (`VoiceInteractionService`), a wake word, pod sync.

## Outside things we need

- [ ] **Phones.** A Pixel 8 or newer (have one), plus a second Android phone for testing if possible.
- [ ] **Builds.** A GitHub Actions APK build. `smart-anthemos` added a build workflow; check that it still runs. Signing keys go in ProtonPass.
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
