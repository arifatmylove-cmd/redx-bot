package com.redxbot.builder

data class AppSpec(
    val appName: String,           // e.g. "CookBot"
    val packageSuffix: String,     // e.g. "cookbot" → package com.cookbot
    val primaryColor: String,      // hex e.g. "#FF6B35"
    val iconLetter: String,        // single uppercase letter for adaptive icon
    val systemPrompt: String,      // AI persona prompt for the generated chatbot
    val appDescription: String     // one-liner shown in the app
)
