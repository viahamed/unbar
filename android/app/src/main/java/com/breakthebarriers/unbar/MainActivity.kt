package com.breakthebarriers.unbar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakthebarriers.unbar.data.datastore.SettingsRepository
import com.breakthebarriers.unbar.model.AppLanguage
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.ui.MainViewModel
import com.breakthebarriers.unbar.ui.UnbarApp
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // The language applied in attachBaseContext on this launch. The
    // LaunchedEffect below only calls setApplicationLocales when the
    // stored language CHANGES while the app is running (user picks a
    // language in Settings) — never on cold start. Comparing against
    // AppCompatDelegate.getApplicationLocales() on first composition
    // was the race that recreated the activity on cold start (the
    // DataStore read could lag behind attachBaseContext), producing
    // the ~0.5s solid black flash.
    private var appliedLanguage: AppLanguage? = null

    // Apply the stored app language BEFORE the activity is created.
    // The LaunchedEffect in onCreate applies the language via
    // AppCompatDelegate.setApplicationLocales, which recreates a
    // running activity — on cold start that recreation is the ~0.5s
    // solid black flash (nothing is drawn while the activity is torn
    // down and rebuilt) and it also destroys the first activity
    // instance before the splash ever draws, so the logo + spinner
    // are never seen. By applying the locale here, up-front:
    //  - setApplicationLocales is called while no activity is running
    //    yet, so nothing is recreated;
    //  - the stored locales already match the target when the
    //    LaunchedEffect runs, so it does not call setApplicationLocales
    //    again (its `!=` check is false);
    //  - the base context is wrapped so the activity's resources use
    //    the right locale from the very first frame.
    // The LaunchedEffect stays as-is for RUNTIME language changes
    // (user picks a language in Settings while the app is running —
    // a recreation there is expected and acceptable).
    override fun attachBaseContext(newBase: Context) {
        val language = SettingsRepository.readLanguageSync(newBase)
        appliedLanguage = language
        val targetLocales = when (language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.PERSIAN -> LocaleListCompat.forLanguageTags("fa")
        }
        if (AppCompatDelegate.getApplicationLocales() != targetLocales) {
            AppCompatDelegate.setApplicationLocales(targetLocales)
        }
        val wrapped = if (targetLocales.isEmpty) {
            newBase
        } else {
            val locale = targetLocales[0]
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            newBase.createConfigurationContext(config)
        }
        super.attachBaseContext(wrapped)
    }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.connect()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedWithConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Compose splash (SplashScreen, shown by UnbarApp)
        // stays until the app data is ready (showHome); the 5s
        // timeout inside showHome is the safety net, so the splash
        // can never hang. The branded windowBackground (see
        // themes.xml) covers the window before the first frame, so
        // there is no white flash.
        handleIncomingUri(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(settings.language) {
                // Only a genuine runtime change recreates the activity.
                // On cold start settings.language already equals the
                // language applied in attachBaseContext, so this is a
                // no-op and the activity is never recreated.
                if (appliedLanguage != null && settings.language != appliedLanguage) {
                    val targetLocales = when (settings.language) {
                        AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
                        AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
                        AppLanguage.PERSIAN -> LocaleListCompat.forLanguageTags("fa")
                    }
                    AppCompatDelegate.setApplicationLocales(targetLocales)
                    appliedLanguage = settings.language
                }
            }

            val isRtl = when (settings.language) {
                AppLanguage.PERSIAN -> true
                AppLanguage.ENGLISH -> false
                AppLanguage.SYSTEM -> Locale.getDefault().language == "fa"
            }

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                UnbarApp(
                    viewModel = viewModel,
                    onRequestConnect = { startConnectionFlow() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingUri(intent)
    }

    private fun handleIncomingUri(intent: Intent?) {
        val dataString = intent?.dataString ?: return
        if (dataString.startsWith("unbar://", ignoreCase = true)) {
            viewModel.importConfigString(dataString)
        }
    }

    private fun startConnectionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        proceedWithConnection()
    }

    private fun proceedWithConnection() {
        if (viewModel.settings.value.connectionMode == ConnectionMode.VPN) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnConsentLauncher.launch(prepareIntent)
                return
            }
        }
        viewModel.connect()
    }
}
