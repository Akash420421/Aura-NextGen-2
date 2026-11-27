package com.example.gemini.data.automation

import java.net.URLEncoder
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
        "google flow" to "labs.google/fx/tools/flow",
        "google flow ai" to "labs.google/fx/tools/flow",
        "labs flow" to "labs.google/fx/tools/flow",
        "labs google flow" to "labs.google/fx/tools/flow",
        "flow" to "labs.google/fx/tools/flow",
        "runway" to "runwayml.com",
        "pika" to "pika.art",
        "midjourney" to "midjourney.com",
        "canva" to "canva.com",
        "huggingface" to "huggingface.co",
        "firebase" to "firebase.google.com",
        "google" to "google.com",
        "gmail" to "mail.google.com",
        "maps" to "maps.google.com",
        "youtube" to "m.youtube.com",
        "yt" to "m.youtube.com",
        "instagram" to "instagram.com",
        "facebook" to "facebook.com",
        "twitter" to "x.com",
        "x" to "x.com",
        "linkedin" to "linkedin.com",
        "reddit" to "reddit.com",
        "amazon" to "amazon.in",
        "flipkart" to "flipkart.com",
        "netflix" to "netflix.com",
        "wikipedia" to "en.wikipedia.org"
    )

    fun normalizeToUrl(spokenText: String): String {
        val raw = spokenText.trim()
        if (raw.isBlank()) return ""
        val trimmed = raw.lowercase(Locale.ROOT)

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("file://") || trimmed.startsWith("about:") || trimmed.startsWith("data:")
        ) {
            return raw
        }

        // Exact shortcut match (e.g. "vercel", "github", "chatgpt", "x")
        KNOWN_DOMAINS[trimmed]?.let { domain ->
            return "https://$domain"
        }

        // Explicit open-site phrasing (e.g. "open vercel", "github website", "flipkart kholo")
        val stripped = trimmed
            .replace(Regex("""^(open|visit|go\s+to|launch)\s+"""), "")
            .replace(Regex("""\s+(website|site|dot\s*com|kholo|open\s+karo)$"""), "")
            .trim()
        KNOWN_DOMAINS[stripped]?.let { domain ->
            return "https://$domain"
        }

        if (trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1") ||
            trimmed.startsWith("192.168.") || trimmed.startsWith("10.0.")
        ) {
            return "http://$raw"
        }

        if (trimmed.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$"))) {
            return "https://$raw"
        }

        if (raw.contains(":") && raw.substringBefore(":").all { it.isLetterOrDigit() || it == '.' }) {
            return "http://$raw"
        }

        return "https://www.google.com/search?q=" + URLEncoder.encode(raw, "UTF-8")
    }
}
