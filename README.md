# Gin

A client-server assistant that hooks your old Google Glass (GDK, XE24, API 19) up to an LLM through your phone. Talk to it or show it something, and it talks back on the HUD.

You record or snap a photo on Glass, it gets shipped over Bluetooth to a companion app running on your phone, the phone fires the request off to whichever provider you've configured (Gemini, OpenAI, Claude, or your own self-hosted endpoint), and the answer comes back to render on the HUD and get read out loud.

---

## How it works

1. **Glass:** records mic audio (8kHz mono WAV) or grabs a photo, sends it to the phone over Bluetooth.
2. **Phone:** forwards it to whichever API you've set up, cleans up the response.
3. **Glass again:** shows it on the HUD and reads it out loud.

---

## Voice Commands

From the Glass home screen:

- **"ok glass, make a request"** — starts the Voice Assistant and starts recording immediately.
- **"ok glass, check this out"** — starts the Visual Assistant, takes a photo, sends it off for analysis.
- **"ok glass, log a meal"** — opens Gin Settings.

---

## Touchpad Controls

### On the query / response screen

- **Tap (while recording):** extends the recording limit, cycles 10s → 15s → 20s → 30s.
- **Two-finger tap (while recording):** stops recording and sends the audio right away.
- **Swipe forward / backward:** scrolls through the text response on the HUD.
- **Tap (while response is being read):** stops TTS and opens the options menu.
- **Swipe down:** dismisses and goes back to the home screen.

### On the settings screen

- **Swipe forward / backward:** scrolls through the settings cards.
- **Tap on the transport card:** switches between BLE and RFCOMM.
- **Tap on the model card:** cycles through your 3 configured presets.

---

## Phone Companion Settings

The phone app is both the server and the config panel.

### Global

- **Transport mode:** Bluetooth Classic (RFCOMM) or BLE. BLE is pretty buggy right now and basically doesn't work reliably, so stick with RFCOMM if you want things to actually run smooth.
- **API keys:** Gemini, OpenAI, Anthropic, plus a custom base URL if you're running something self-hosted (LocalAI, Ollama, vLLM, whatever... as long as it's OpenAI API compatible it'll work).
- **System prompt:** injected into every query. Default is `"You are an AI assistant on Google Glass. Keep responses very short, concise, and direct (max 2 sentences). Don't fear going into details if the user has explicitly asked for it." Keeping responses short and concise helps improving the UX on Glass, keep this in mind if you decide to modify the default prompt.
- **Chat Mode / Quick Mode:** Chat Mode keeps a rolling context of 40 messages (20 of your requests, audio or image, plus 20 model responses), Quick Mode wipes it after every request.

### Model presets (1 to 3)

The 3 presets are meant for a quickdraw setup: a small/fast model (like Gemini 3.1 Flash Lite) for day to day stuff, something heavier (Gemini Pro, Claude Opus, GPT 5.6 Terra) loaded on another preset for when the question actually needs it and a specialized model (or your local one) for specific tasks like using tools / API calls. Cycle with a tap on the model card.

Each preset gets its own:

- **Name:** shown on Glass, so make it short.
- **Provider & model:** e.g. `gemini-3.5-flash`, `claude-sonnet-5`, `gpt-5.6-luna`. For your local model use the custom URL field and set the model name to whatever you're running.
- **Reasoning level:** *None* for a straight answer, *High* to make the model think step by step first.
- **Direct audio streaming:** on, and the WAV goes straight to the target model's multimodal API. Off, and the phone transcribes it first with `gemini-3.1-flash-lite` before querying your actual target model with text.

---

## Choosing the Right Model

The app is meant to be model agnostic, so you are free to pick whatever model/provider you want. That said to get the best experience I would suggest sticking to small models: Claude Haiku, Gemini Flash-Lite, GPT mini/nano, things like that.

Latency is a particularly sensitive metrics for use on Glass, and natively multimodal models that can ingest audio are favoured due to not needing the transcription pass (shortening the wait time before getting a response).

Using bigger models with high reasoning can be tempting, but there's not much value in superintelligence when it's confined to a couple of paragraphs, and the drawback of the long delay for the response can quickly become a nuisance.  

---

## Getting Started

### 1. Download and install

Grab the APKs from the [GitHub Releases](https://github.com/Glass-Bar/Gin/releases) page, then install them onto their respective target devices.

### 2. Connect

1. Pair Glass with your phone the usual way, through system Bluetooth settings.
2. Open the phone app, go to Settings, drop in your API keys, hit Save.
3. Tap Start Server on the phone.
4. Launch Gin on Glass.