package com.example.gemini.data.automation

import java.util.Locale

object DomainNormalizer {

    private val KNOWN_DOMAINS = mapOf(
        "vercel" to "vercel.com",
        "github" to "github.com",
        "gitlab" to "gitlab.com",
        "stackoverflow" to "stackoverflow.com",
        "chatgpt" to "chatgpt.com",
        "openai" to "openai.com",
        "claude" to "claude.ai",
        "gemini" to "gemini.google.com",
        "google flow" to "labs.google/flow",
        "runway" to "runwayml.com",
        "pika" to "pika.art",
        "midjourney" to "midjourney.com",
        "canva" to "canva.com",
        "huggingface" to "huggingface.co",
        "firebase" to "firebase.google.com",
        "google" to "google.com",
        "gmail" to "mail.google.com",
        "maps" to "maps.google.com",
        "youtube" to "youtube.com",
        "instagram" to "instagram.com",
        "facebook" to "facebook.com",
        "twitter" to "x.com",
        "x" to "x.com",
        "linkedin" to "linkedin.com",
        "reddit" to "reddit.com",
        "amazon" to "amazon.in",
        "flipkart" to "flipkart.com",
        "netflix" to "netflix.com",
        "wikipedia" to "wikipedia.org"
    )

    fun normalizeToUrl(spokenText: String): String {
        val trimmed = spokenText.trim().lowercase(Locale.ROOT)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return spokenText.trim()
        }

        KNOWN_DOMAINS[trimmed]?.let { domain ->
            return "https://$domain"
        }

        for ((key, domain) in KNOWN_DOMAINS) {
            if (trimmed.contains(key)) {
                return "https://$domain"
            }
        }

        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }

        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(spokenText.trim(), "UTF-8")
    }
}
