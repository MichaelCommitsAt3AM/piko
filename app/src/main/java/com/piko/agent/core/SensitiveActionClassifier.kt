package com.piko.agent.core

import java.text.Normalizer
import java.util.Locale

/**
 * Classifies actions that need explicit human approval. This is a safety net,
 * not a replacement for the model's own request_confirmation action.
 */
object SensitiveActionClassifier {
    enum class Level { NONE, CONFIRM, BLOCK }
    enum class Category {
        NONE,
        MESSAGE_SEND,
        PAYMENT,
        DATA_DELETION,
        APP_INSTALL,
        SECURITY_SETTINGS,
        EXTERNAL_SHARE,
        AUTHENTICATION,
        CREDENTIAL_ENTRY,
    }

    data class Context(
        val action: AgentAction,
        val targetText: String? = null,
        val targetDescription: String? = null,
        val targetResourceId: String? = null,
        val targetPackage: String? = null,
        val isPasswordField: Boolean = false,
        val foregroundPackage: String? = null,
    )

    data class Assessment(
        val level: Level,
        val category: Category,
        val reason: String,
    ) {
        val requiresConfirmation: Boolean get() = level == Level.CONFIRM
        val isBlocked: Boolean get() = level == Level.BLOCK
    }

    private val paymentWords = listOf(
        "buy now", "purchase", "pay now", "place order", "checkout", "confirm payment",
        "subscribe", "transfer money", "send money", "add to cart",
        "comprar", "pagar", "acheter", "payer", "kaufen", "bezahlen", "lipa", "nunua",
    )
    private val sendWords = listOf(
        "send", "send message", "send email", "post", "publish", "reply",
        "enviar", "envoyer", "senden", "tuma", "invia",
    )
    private val deleteWords = listOf(
        "delete", "erase", "remove all", "clear data", "clear storage", "factory reset",
        "uninstall", "discard changes", "delete account", "format",
        "eliminar", "borrar", "supprimer", "effacer", "löschen", "futa", "cancella",
    )
    private val installWords = listOf(
        "install", "update app", "allow install", "install app",
        "instalar", "installer", "installieren", "sakinisha",
    )
    private val securityWords = listOf(
        "allow", "grant", "grant permission", "screen lock", "device admin", "find my device",
        "unknown apps", "install unknown apps", "accessibility", "developer options",
        "security settings", "change pin", "change password", "disable lock", "turn off lock",
        "permitir", "autoriser", "erlauben", "ruhusu",
    )
    private val authWords = listOf(
        "sign in", "log in", "login", "verify", "authenticate", "use password", "use pin",
        "fingerprint", "face unlock", "confirm identity", "two-factor", "2fa", "passkey",
        "iniciar sesión", "connexion", "anmelden", "ingia",
    )
    private val shareWords = listOf(
        "share", "share with", "share to", "send to", "copy link",
        "compartir", "partager", "teilen", "shiriki",
    )

    fun classify(context: Context): Assessment {
        val action = context.action
        if (action.type in setOf(
                ActionType.WAIT, ActionType.OBSERVE, ActionType.FINISH, ActionType.FAIL,
                ActionType.BACK, ActionType.HOME, ActionType.OPEN_NOTIFICATIONS,
            )
        ) {
            return clean()
        }

        val target = normalize(
            listOfNotNull(
                context.targetText,
                context.targetDescription,
                context.targetResourceId?.substringAfterLast('/'),
                action.packageName,
            ).joinToString(" "),
        )

        if (context.isPasswordField && action.type in setOf(ActionType.TYPE_TEXT, ActionType.CLEAR_TEXT)) {
            return Assessment(
                Level.BLOCK,
                Category.CREDENTIAL_ENTRY,
                "Piko does not enter passwords, PINs, one-time codes, or payment credentials. Please handle this step yourself.",
            )
        }
        if (action.type == ActionType.TYPE_TEXT && containsPaymentCredential(action.text.orEmpty())) {
            return Assessment(
                Level.BLOCK,
                Category.CREDENTIAL_ENTRY,
                "The text looks like a payment card number. Piko does not handle payment credentials.",
            )
        }
        if (action.type == ActionType.LAUNCH_APP) {
            return clean()
        }

        if (action.type == ActionType.CLEAR_TEXT) {
            return clean()
        }

        // Granting accessibility, notification access, device admin, or runtime
        // permissions must always be a human decision.
        if (target.contains("grant") || target.contains("allow") ||
            target.contains("while using the app") || target.contains("only this time")
        ) {
            return confirm(Category.AUTHENTICATION, "This appears to grant a permission or approve access.")
        }
        if (matches(target, authWords)) {
            return confirm(Category.AUTHENTICATION, "This appears to authenticate or verify identity.")
        }
        if (matches(target, paymentWords)) {
            return confirm(Category.PAYMENT, "This may make a purchase or payment.")
        }
        if (modeSensitiveSettings(context, target)) {
            return confirm(Category.SECURITY_SETTINGS, "This changes a security-sensitive setting.")
        }
        if (matches(target, deleteWords)) {
            return confirm(Category.DATA_DELETION, "This may delete data or uninstall an app.")
        }
        if (matches(target, installWords)) {
            return confirm(Category.APP_INSTALL, "This may install or update an application.")
        }
        if (matches(target, shareWords)) {
            return confirm(Category.EXTERNAL_SHARE, "This may share information outside Piko.")
        }
        if (matches(target, sendWords)) {
            return confirm(Category.MESSAGE_SEND, "This may send a message, email, or public post.")
        }
        return clean()
    }

    private fun modeSensitiveSettings(context: Context, target: String): Boolean {
        val settingsPackage = context.foregroundPackage == "com.android.settings" ||
            context.targetPackage == "com.android.settings"
        if (!settingsPackage) return false
        return target.contains("security") || target.contains("screen lock") ||
            target.contains("device admin") || target.contains("accessibility") ||
            target.contains("unknown apps") || target.contains("find my device") ||
            target.contains("factory reset") || target.contains("system update")
    }

    private fun matches(target: String, words: List<String>): Boolean =
        words.any { word ->
            val normalizedWord = normalize(word)
            Regex("(^|\\W)${Regex.escape(normalizedWord)}(\\W|$)").containsMatchIn(target)
        }

    private fun containsPaymentCredential(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { !(it.isDigit() || it == ' ' || it == '-') }) return false
        val digits = trimmed.filter(Char::isDigit)
        return digits.length in 13..19
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun clean() = Assessment(Level.NONE, Category.NONE, "")
    private fun confirm(category: Category, reason: String) = Assessment(Level.CONFIRM, category, reason)
}

/**
 * Redacts values that must not be sent to a model or written to logs.
 */
object SensitiveTextRedactor {
    private val sensitiveKeys = listOf(
        "password", "passwd", "passcode", "pin", "otp", "one-time", "token", "secret",
        "cvv", "cvc", "card_number", "cardnumber", "credit_card", "creditcard", "ssn",
        "social_security", "aadhaar",
    )
    private val otpPattern = Regex(
        "(?i)(\\b(?:otp|one[- ]time(?: code| password)?|verification code|security code|2fa code)\\b[^\\d]{0,24})(\\d{4,8})",
    )
    private val cardPattern = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    fun shouldRedact(
        resourceId: String?,
        hint: String? = null,
        className: String? = null,
        isPassword: Boolean = false,
    ): Boolean {
        if (isPassword) return true
        val haystack = listOfNotNull(resourceId, hint, className).joinToString(" ").lowercase(Locale.ROOT)
        return sensitiveKeys.any { haystack.contains(it) }
    }

    fun redact(value: String?): String? {
        if (value.isNullOrBlank()) return value
        return "[redacted]"
    }

    /**
     * Best-effort redaction for visible text. This catches common OTP message
     * formats and card-number-like digit runs even when the containing widget
     * has no password/OTP metadata.
     */
    fun redactVisibleText(value: String?): String? {
        if (value.isNullOrBlank()) return value
        var redacted = value.replace(otpPattern, "$1[redacted]")
        redacted = redacted.replace(cardPattern, "[redacted]")
        return redacted
    }

    /** Text displayed in UI traces. Never allow a credential-shaped value through. */
    fun forTrace(value: String?): String? {
        if (value.isNullOrBlank()) return value
        val visible = redactVisibleText(value)
        return if (visible?.contains("[redacted]") == true) visible else value.take(500)
    }
}
