package com.wxn.base.diagnostics

object DiagnosticSanitizer {
    private const val MAX_MESSAGE_CHARS = 2_000
    private const val MAX_STACK_LINES = 20
    private const val REDACTED = "[REDACTED]"
    private const val PRIVATE_PATH = "[PRIVATE_PATH]"

    private val urlSecret = Regex("(?i)(https://[^\\s?#]+)(?:[?#][^\\s]*)")
    private val authorization = Regex(
        "(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+",
    )
    private val bearer = Regex("(?i)\\bbearer\\s+[^\\s,;]+")
    private val namedSecret = Regex(
        "(?i)\\b(api[-_]?key|apikey|token|secret)\\s*[:=]\\s*[^\\s,;]+",
    )
    private val windowsPath = Regex("(?i)\\b[A-Z]:\\\\(?:[^\\s\\\\]+\\\\)*[^\\s]+")
    private val privateUnixPath = Regex(
        "(?<![:\\w])/(?:data/user/\\d+|home|Users|storage/emulated/\\d+|sdcard)/[^\\s]+",
    )

    fun sanitize(message: String): String = message
        .replace(urlSecret) { "${it.groupValues[1]}?$REDACTED" }
        .replace(authorization) { "${it.groupValues[1]}$REDACTED" }
        .replace(bearer, "Bearer $REDACTED")
        .replace(namedSecret) { "${it.groupValues[1]}=$REDACTED" }
        .replace(windowsPath, PRIVATE_PATH)
        .replace(privateUnixPath, PRIVATE_PATH)
        .replace("\r\n", "\\n")
        .replace("\r", "\\n")
        .replace("\n", "\\n")
        .take(MAX_MESSAGE_CHARS)

    fun sanitize(throwable: Throwable): String {
        val header = "${throwable::class.java.name}: ${throwable.message.orEmpty()}"
        val lines = buildList {
            add(header)
            throwable.stackTrace.take(MAX_STACK_LINES - 1).forEach { add("at $it") }
        }
        return sanitize(lines.joinToString("\n"))
    }
}
