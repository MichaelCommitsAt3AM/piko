package com.piko.agent.core

/**
 * Apps whose visible content or input surface would let Piko read credentials
 * or effectively run arbitrary shell commands. Piko refuses to observe or act
 * on these packages. The user can still operate them manually.
 */
object SensitiveApps {
    private val credentialManagers = setOf(
        "com.bitwarden",
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.google.android.apps.authenticator2",
        "com.microsoft.authenticator",
        "com.authy.authy",
        "com.azure.authenticator",
    )

    private val terminalsAndRemoteShells = setOf(
        "com.termux",
        "com.termux.window",
        "com.termux.boot",
        "jackpal.androidterm",
        "com.offsec.nhterm",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client",
        "com.microsoft.rdc.android",
        "com.microsoft.rdc.androidx",
        "com.realvnc.viewer.android",
        "net.dinglisch.android.taskerm",
        "com.llamalab.automate",
        "com.arlosoft.macrodroid",
        "com.arlosoft.macrodroid.pro",
        "com.joaomgcd.autotools",
    )

    fun blockReason(packageName: String?): String? {
        val pkg = packageName?.lowercase() ?: return null
        if (pkg in credentialManagers) {
            return "Piko does not read password managers or authenticator apps. Handle credentials manually."
        }
        if (pkg in terminalsAndRemoteShells) {
            return "Piko does not operate terminals or remote-shell apps, so it cannot execute arbitrary shell commands through the UI."
        }
        return null
    }

    fun isBlocked(packageName: String?): Boolean = blockReason(packageName) != null
}
