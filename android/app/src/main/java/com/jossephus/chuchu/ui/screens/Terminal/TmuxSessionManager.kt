package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.multiplexer.filterMultiplexerSessions
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxSessionManager(state: TmuxManagerState, controller: TmuxManagerController) {
    if (!state.visible) return
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboard?.hide() }
    ModalBottomSheet(
        onDismissRequest = controller::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        dragHandle = null,
    ) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(horizontal = 16.dp)
                .focusRequester(focus)
                .focusable()
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ChuText("tmux sessions", style = typography.title, modifier = Modifier.weight(1f))
                ManagerButton("⟳", "Refresh tmux sessions", !state.busy, controller::refresh)
                ManagerButton("×", "Close tmux manager", true, controller::dismiss)
            }
            ChuText(
                "Connected: ${state.hostLabel}",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            ChuTextField(
                value = state.query,
                onValueChange = controller::search,
                label = "Search sessions",
                placeholder = "Search sessions",
                singleLine = true,
                autoFocus = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            )
            if (state.busy) {
                ChuText(
                    if (state.loading) "Loading sessions…" else "Working…",
                    style = typography.label,
                    color = colors.accent,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (state.dialog == null) {
                state.error?.let { ManagerError(it) }
                state.notice?.let {
                    ChuText(
                        it,
                        style = typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            val sessions =
                remember(state.sessions, state.query) {
                    filterMultiplexerSessions(state.sessions, state.query)
                }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (sessions.isEmpty() && !state.loading) {
                    item {
                        ChuText(
                            when {
                                state.error != null && state.sessions.isEmpty() ->
                                    "Could not load sessions. Tap ⟳ to retry."
                                state.sessions.isEmpty() -> "No tmux sessions. Create one below."
                                else -> "No sessions match your search."
                            },
                            style = typography.body,
                            color = colors.textMuted,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(sessions, key = { it.identity?.token ?: it.name }) { session ->
                    TmuxManagerRow(
                        session,
                        session.identity == state.currentIdentity,
                        state.busy,
                        controller,
                    )
                }
            }
            ChuButton(
                onClick = { controller.showDialog(TmuxManagerDialog.Create) },
                enabled = !state.busy,
                variant = ChuButtonVariant.Outlined,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 12.dp),
            ) {
                ChuText("＋ New session", style = typography.label, color = colors.accent)
            }
        }
    }
    if (state.dialog != null) TmuxManagerDialogContent(state, controller)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TmuxManagerRow(
    session: RemoteMultiplexerSession,
    current: Boolean,
    busy: Boolean,
    controller: TmuxManagerController,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 72.dp)
            .border(1.dp, if (current) colors.accent else colors.border)
            .combinedClickable(
                enabled = !busy,
                onClick = { controller.connect(session) },
                onLongClick = { menu = true },
            )
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            .semantics {
                contentDescription =
                    "${session.name}${if (current) ", current ChuChu session" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText(
            if (current) "●" else "○",
            style = typography.body,
            color = if (current) colors.success else colors.textMuted,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ChuText(
                session.name,
                style = typography.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ChuText(
                "${session.windowCount ?: "?"} windows · " +
                    if (session.clientCount == 0) "Detached"
                    else "${session.clientCount} clients attached",
                style = typography.labelSmall,
                color = colors.textSecondary,
            )
            if (current)
                ChuText(
                    "Current ChuChu session",
                    style = typography.labelSmall,
                    color = colors.success,
                )
            session.workingDirectory?.let {
                ChuText(
                    it,
                    style = typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            session.createdAt?.let {
                ChuText(
                    "Created ${formatSessionDate(it)}",
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        Box {
            ManagerButton("⋮", "Actions for ${session.name}", !busy) { menu = true }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = colors.surfaceVariant,
            ) {
                listOf("Connect", "Preview", "Rename", "Session info", "Terminate session")
                    .forEach { action ->
                        DropdownMenuItem(
                            text = {
                                ChuText(
                                    action,
                                    style = typography.body,
                                    color =
                                        if (action == "Terminate session") colors.error
                                        else colors.textPrimary,
                                )
                            },
                            onClick = {
                                menu = false
                                when (action) {
                                    "Connect" -> controller.connect(session)
                                    "Preview" ->
                                        controller.showDialog(TmuxManagerDialog.Preview, session)
                                    "Rename" ->
                                        controller.showDialog(TmuxManagerDialog.Rename, session)
                                    "Session info" ->
                                        controller.showDialog(TmuxManagerDialog.Info, session)
                                    else ->
                                        controller.showDialog(TmuxManagerDialog.Terminate, session)
                                }
                            },
                        )
                    }
            }
        }
    }
}

@Composable
private fun TmuxManagerDialogContent(state: TmuxManagerState, controller: TmuxManagerController) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val dialog = state.dialog ?: return
    val target = state.target
    val focus = remember { FocusRequester() }
    var name by
        rememberSaveable(dialog, target?.identity?.token) {
            mutableStateOf(if (dialog == TmuxManagerDialog.Rename) target?.name.orEmpty() else "")
        }
    var directory by rememberSaveable(dialog) { mutableStateOf("") }
    val title =
        when (dialog) {
            TmuxManagerDialog.Create -> "New tmux session"
            TmuxManagerDialog.Rename -> "Rename session"
            TmuxManagerDialog.Terminate -> "Terminate \"${target?.name}\"?"
            TmuxManagerDialog.Preview -> "Preview: ${target?.name}"
            TmuxManagerDialog.Info -> "Session info"
        }
    Dialog(onDismissRequest = controller::dismissDialog) {
        // Opening a form must not automatically focus an editor or raise the keyboard.
        LaunchedEffect(dialog, target?.identity) { focus.requestFocus() }
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.border)
                .padding(16.dp)
                .focusRequester(focus)
                .focusable()
                .testTag("tmux-session-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChuText(title, style = typography.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (dialog) {
                    TmuxManagerDialog.Create,
                    TmuxManagerDialog.Rename -> {
                        ChuTextField(
                            name,
                            { name = it },
                            "Session name",
                            placeholder = "chuchu-N (automatic)",
                            singleLine = true,
                            autoFocus = false,
                        )
                        if (dialog == TmuxManagerDialog.Create) {
                            ChuText(
                                "Optional — blank uses chuchu-N",
                                style = typography.labelSmall,
                                color = colors.textMuted,
                            )
                            ChuTextField(
                                directory,
                                { directory = it },
                                "Working directory",
                                placeholder = "/home/user/projects",
                                singleLine = true,
                                autoFocus = false,
                            )
                            ChuText(
                                "Optional — use an absolute path; blank uses the default directory.",
                                style = typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                    TmuxManagerDialog.Terminate -> {
                        ChuText(
                            "This will terminate the tmux session and processes running inside it.",
                            style = typography.body,
                        )
                        if ((target?.clientCount ?: 0) > 0)
                            ChuText(
                                "Warning: ${target?.clientCount} clients are attached and will be disconnected.",
                                style = typography.body,
                                color = colors.error,
                            )
                        ChuText(
                            "Closing a ChuChu tab only disconnects it. Terminate ends the remote session.",
                            style = typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    TmuxManagerDialog.Preview -> {
                        ChuText(
                            "Read-only · last 80 lines · up to 32 KiB · updates at most every 2 seconds",
                            style = typography.labelSmall,
                            color = colors.textMuted,
                        )
                        ChuText(
                            state.preview
                                ?: if (state.busy) "Loading preview…" else "No preview available",
                            style = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    TmuxManagerDialog.Info -> {
                        ChuText(
                            "Host: ${state.hostLabel}\nSession: ${target?.name}\nID: ${target?.identity?.id}\nWindows: ${target?.windowCount}\nClients: ${target?.clientCount}\nCreated: ${target?.createdAt?.let(::formatSessionDate)}" +
                                (target?.workingDirectory?.let { "\nDirectory: $it" } ?: "") +
                                "\nActive pane: ${target?.activePaneId ?: "unavailable"}",
                            style = typography.body,
                        )
                    }
                }
                state.error?.let { ManagerError(it) }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ManagerButton(
                    if (dialog == TmuxManagerDialog.Preview || dialog == TmuxManagerDialog.Info)
                        "Close"
                    else "Cancel",
                    "Dismiss session dialog",
                    !state.busy || dialog == TmuxManagerDialog.Preview,
                    controller::dismissDialog,
                )
                if (dialog != TmuxManagerDialog.Info) {
                    ChuButton(
                        onClick = {
                            when (dialog) {
                                TmuxManagerDialog.Create -> controller.create(name, directory)
                                TmuxManagerDialog.Rename -> controller.rename(name)
                                TmuxManagerDialog.Terminate -> controller.confirmTerminate()
                                TmuxManagerDialog.Preview -> controller.refreshPreview()
                                else -> Unit
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        ChuText(
                            if (state.busy) "Working…"
                            else
                                when (dialog) {
                                    TmuxManagerDialog.Create -> "Create"
                                    TmuxManagerDialog.Rename -> "Rename"
                                    TmuxManagerDialog.Terminate -> "Terminate"
                                    else -> "Refresh"
                                },
                            style = typography.label,
                            color = colors.onAccent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagerButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ChuButton(
        onClick = onClick,
        enabled = enabled,
        variant = ChuButtonVariant.Ghost,
        contentDescription = description,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        ChuText(label, style = ChuTypography.current.label, color = ChuColors.current.accent)
    }
}

@Composable
private fun ManagerError(message: String) {
    ChuText(
        message,
        style = ChuTypography.current.bodySmall,
        color = ChuColors.current.error,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

private fun formatSessionDate(seconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1_000))
