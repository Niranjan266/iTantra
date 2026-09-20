package com.itantra

import android.app.Application
import android.util.Log
import com.itantra.alert.AlertPolicy
import com.itantra.lang.CodebookInstaller
import com.itantra.lang.LanguagePack
import com.itantra.lang.LanguagePackManager
import com.itantra.session.SessionController
import com.itantra.transport.LoopbackTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the session for as long as the app is alive.
 *
 * ## Why this class exists
 *
 * The session used to live in the Activity and run on `lifecycleScope`. That looked
 * fine and was quietly broken: `lifecycleScope` is cancelled when the Activity goes
 * away, so locking the screen, rotating the phone or switching apps **silently
 * cancelled whatever was in flight** — with no exception and no log line, because
 * cancellation is not an error.
 *
 * Two consequences, both serious for this product:
 *
 *  - Loading the speech models takes seconds. Lock the screen during that window and
 *    the load was abandoned; the app then sat there with no recogniser and no message
 *    explaining why. This was found exactly that way — the models loaded when the
 *    phone was unlocked and never loaded when it was locked.
 *  - A live Bluetooth link would drop on a screen rotation, mid-conversation.
 *
 * A walkie-talkie that stops working because someone turned the screen off is not a
 * walkie-talkie. The session, its transport and its models outlive any one screen, so
 * they are scoped to the application and the Activity merely observes them.
 */
class ITantraApp : Application() {

    companion object {
        private const val TAG = "iTantra.App"
    }

    /**
     * SupervisorJob: one failing child must not tear down the others. A voice model
     * that fails to load should not also kill the transport.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    lateinit var session: SessionController
        private set

    lateinit var packManager: LanguagePackManager
        private set

    /**
     * Packs found on disk. Re-read rather than cached forever, so a pack copied onto
     * the device while the app was closed shows up on the next launch — which is the
     * demonstration in PRD section 10.5.
     */
    var installedPacks: List<LanguagePack> = emptyList()
        private set

    override fun onCreate() {
        super.onCreate()

        packManager = LanguagePackManager(this)
        packManager.installBundledIfNeeded()
        refreshPacks()

        session = SessionController(
            scope = appScope,
            transport = LoopbackTransport(),
            alertPolicy = AlertPolicy(this, appScope),
        )

        appScope.launch {
            session.connect()
            // Runs to completion regardless of what the screen is doing.
            session.selectLanguage(installedPacks.firstOrNull())
        }
    }

    fun refreshPacks(): List<LanguagePack> {
        // Before loading: a pack without a codebook cannot translate in either direction,
        // and every codebook ships in the APK. See CodebookInstaller.
        val updated = CodebookInstaller.installInto(this, packManager.packDirs())
        if (updated > 0) Log.i(TAG, "installed $updated codebook(s)")
        installedPacks = packManager.installed()
        if (installedPacks.isEmpty()) {
            Log.w(TAG, "no usable language pack; running without speech models")
        } else {
            Log.i(TAG, "packs: ${installedPacks.joinToString { "${it.code}(${it.id})" }}")
        }
        return installedPacks
    }
}
