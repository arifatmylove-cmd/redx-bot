# RedxBot 🤖

An uncensored AI chatbot Android app built with Kotlin. Powered by free open-source models via [OpenRouter](https://openrouter.ai).

## Features

- 💬 Chat with uncensored AI — no restrictions
- 🖼️ Image upload support (vision AI)
- 📝 Markdown + code block rendering
- 🌑 Dark theme UI
- ⚡ Lightweight APK (~12MB, no on-device model)
- 📱 Works on low-RAM devices (2GB+)

## Models Used

| Purpose | Model |
|---------|-------|
| Text chat | `meta-llama/llama-3.1-8b-instruct:free` |
| Image + text | `meta-llama/llama-3.2-11b-vision-instruct:free` |

Both models are **completely free** via OpenRouter.

## Setup

1. Download the APK from [Releases](../../releases) or from the latest [Actions artifact](../../actions)
2. Install on your Android device (minSdk 24 / Android 7.0+)
3. Open the app → tap ⚙️ Settings
4. Get a **free** API key from [openrouter.ai](https://openrouter.ai) (no credit card required)
5. Paste your key and tap Save
6. Start chatting!

## Build from Source

```bash
git clone https://github.com/arifatmylove-cmd/redx-bot.git
cd redx-bot
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

Every push to `main` triggers an automatic APK build. Download the APK from the **Actions** tab → latest workflow run → `redxbot-debug-apk` artifact.

## Tech Stack

- **Language:** Kotlin
- **UI:** Material Design 3, RecyclerView, ViewBinding
- **Networking:** OkHttp
- **Markdown:** Markwon
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
