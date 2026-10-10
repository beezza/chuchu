package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.multiplexer.TmuxSessionIdentity
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.ui.theme.ChuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class TmuxSessionManagerUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var controller: TmuxManagerController
    private var terminated = 0
    private val session =
        RemoteMultiplexerSession(
            "coding",
            true,
            TmuxSessionIdentity("\$0", 1, 2, 3),
            2,
            3,
            3,
            "/projects/chuchu",
        )

    @Before
    fun setUp() {
        val context =
            TmuxManagerContext(
                "tab-a",
                TabSpec(host = "test-host", username = "test", multiplexer = MultiplexerType.Tmux),
            )
        val backend =
            object : TmuxManagerBackend {
                override fun currentContext() = context

                override suspend fun list(context: TmuxManagerContext) = listOf(session)

                override suspend fun create(
                    context: TmuxManagerContext,
                    name: String,
                    directory: String,
                ) {}

                override suspend fun rename(
                    context: TmuxManagerContext,
                    session: RemoteMultiplexerSession,
                    name: String,
                ) {}

                override suspend fun terminate(
                    context: TmuxManagerContext,
                    session: RemoteMultiplexerSession,
                ) {
                    terminated++
                }

                override suspend fun preview(
                    context: TmuxManagerContext,
                    session: RemoteMultiplexerSession,
                ) = "READ_ONLY_OUTPUT"

                override suspend fun connect(
                    context: TmuxManagerContext,
                    session: RemoteMultiplexerSession,
                ) {}
            }
        controller = TmuxManagerController(scope, backend)
        controller.open()
        compose.setContent {
            val state by controller.state.collectAsState()
            ChuTheme { TmuxSessionManager(state, controller) }
        }
    }

    @After
    fun tearDown() {
        controller.dismiss()
        scope.cancel()
    }

    // Robolectric #8460 loops for text fields in floating dialogs above its default 320dp width.
    // Keep form coverage at 320dp; menu and read-only dialogs still run at 360dp.
    @Config(qualifiers = "w320dp-h640dp")
    @Test
    fun nativeSheetSearchAndNewSessionAreAccessibleOnSmallScreen() {
        compose.onNodeWithText("tmux sessions").assertIsDisplayed()
        compose.onNodeWithText("＋ New session").assertIsDisplayed().performClick()
        compose.onNodeWithText("New tmux session").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("absent")
        compose.onNodeWithText("No sessions match your search.").assertIsDisplayed()
    }

    @Test
    fun terminateMenuRequiresConfirmationAndCancelDoesNothing() {
        compose.onNodeWithContentDescription("Actions for coding").performClick()
        compose.onNodeWithText("Terminate session").performClick()
        compose
            .onNodeWithText("Warning: 2 clients are attached and will be disconnected.")
            .assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, terminated)
        compose.onNodeWithContentDescription("Actions for coding").performClick()
        compose.onNodeWithText("Terminate session").performClick()
        compose.onNodeWithText("Terminate", useUnmergedTree = true).performClick()
        assertEquals(1, terminated)
    }

    @Test
    fun previewIsReadOnlyAndRefreshIsExplicit() {
        compose.onNodeWithContentDescription("Actions for coding").performClick()
        compose.onNodeWithText("Preview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("READ_ONLY_OUTPUT").assertExists()
        compose.onNodeWithText("Refresh", useUnmergedTree = true).assertExists()
        compose
            .onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("tmux-session-dialog")))
            .assertCountEquals(0)
    }
}
