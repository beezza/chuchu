package com.jossephus.chuchu

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.service.terminal.CommandCompletionNotifier
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.ApplicationNavController
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTheme
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import com.jossephus.chuchu.ui.theme.resolveActiveThemeName
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        val settings = SettingsRepository.getInstance(this)
        lifecycleScope.launch {
            settings.hideScreenContents.collect { hideScreenContents ->
                if (hideScreenContents) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0x00000000),
        )
        setContent {
            AppRoot()
        }
    }

    override fun onStart() {
        super.onStart()
        TerminalSessionRepository.getInstance(application).setAppInForeground(true)
    }

    override fun onStop() {
        TerminalSessionRepository.getInstance(application).setAppInForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != CommandCompletionNotifier.ACTION_OPEN_TAB) return
        val tabId = intent.getStringExtra(CommandCompletionNotifier.EXTRA_TAB_ID) ?: return
        TerminalSessionRepository.getInstance(application).requestOpenTab(tabId)
        intent.removeExtra(CommandCompletionNotifier.EXTRA_TAB_ID)
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    GhosttyThemeRegistry.init(context)
    val settings = SettingsRepository.getInstance(context)
    val fontName by settings.fontName.collectAsStateWithLifecycle()
    val themeName by settings.themeName.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val lightThemeName by settings.lightThemeName.collectAsStateWithLifecycle()
    val resolvedThemeName = resolveActiveThemeName(
        themeMode = themeMode,
        darkThemeName = themeName,
        lightThemeName = lightThemeName,
    )

    ChuTheme(themeName = resolvedThemeName, fontName = fontName) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ChuColors.current.background),
        ) {
            ApplicationNavController()
        }
    }
}
