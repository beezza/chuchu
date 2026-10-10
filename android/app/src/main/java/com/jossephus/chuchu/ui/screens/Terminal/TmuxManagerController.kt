package com.jossephus.chuchu.ui.screens.Terminal

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.multiplexer.TmuxSessionIdentity
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.service.terminal.sameEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TmuxManagerContext(val tabId: String, val spec: TabSpec) {
    fun matches(other: TmuxManagerContext?): Boolean =
        other != null && tabId == other.tabId && spec.sameEndpoint(other.spec)
}

interface TmuxManagerBackend {
    fun currentContext(): TmuxManagerContext?

    suspend fun list(context: TmuxManagerContext): List<RemoteMultiplexerSession>

    suspend fun create(context: TmuxManagerContext, name: String, directory: String)

    suspend fun rename(context: TmuxManagerContext, session: RemoteMultiplexerSession, name: String)

    suspend fun terminate(context: TmuxManagerContext, session: RemoteMultiplexerSession)

    suspend fun preview(context: TmuxManagerContext, session: RemoteMultiplexerSession): String

    suspend fun connect(context: TmuxManagerContext, session: RemoteMultiplexerSession)
}

enum class TmuxManagerDialog {
    Create,
    Rename,
    Terminate,
    Preview,
    Info,
}

data class TmuxManagerState(
    val visible: Boolean = false,
    val sourceTabId: String? = null,
    val hostLabel: String = "",
    val sessions: List<RemoteMultiplexerSession> = emptyList(),
    val currentIdentity: TmuxSessionIdentity? = null,
    val query: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val dialog: TmuxManagerDialog? = null,
    val target: RemoteMultiplexerSession? = null,
    val preview: String? = null,
)

/** One operation at a time, tied to an exact tab/endpoint and panel generation. No polling. */
class TmuxManagerController(
    private val scope: CoroutineScope,
    private val backend: TmuxManagerBackend,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(TmuxManagerState())
    val state = mutableState.asStateFlow()
    private var context: TmuxManagerContext? = null
    private var generation = 0L
    private var job: Job? = null
    private var previewAt = Long.MIN_VALUE / 2

    fun open() {
        val source = backend.currentContext() ?: return
        if (source.spec.multiplexer != MultiplexerType.Tmux || !source.spec.usesRuntimeMultiplexer)
            return
        dismiss()
        context = source
        mutableState.value =
            TmuxManagerState(
                visible = true,
                sourceTabId = source.tabId,
                hostLabel = source.spec.tabLabel,
                currentIdentity = source.spec.tmuxSessionIdentity,
            )
        refresh()
    }

    fun dismiss() {
        generation++
        job?.cancel()
        job = null
        context = null
        mutableState.value = TmuxManagerState()
    }

    fun openCreateDialog() {
        open()
        if (state.value.visible)
            mutableState.value = state.value.copy(dialog = TmuxManagerDialog.Create)
    }

    fun connectionChanged() {
        if (state.value.visible && context?.matches(backend.currentContext()) != true) dismiss()
    }

    fun search(query: String) {
        mutableState.value = state.value.copy(query = query)
    }

    fun showDialog(dialog: TmuxManagerDialog, target: RemoteMultiplexerSession? = null) {
        if (state.value.busy) return
        if (dialog != TmuxManagerDialog.Create && target?.identity == null) return
        mutableState.value =
            state.value.copy(dialog = dialog, target = target, error = null, preview = null)
        previewAt = Long.MIN_VALUE / 2
        if (dialog == TmuxManagerDialog.Preview) refreshPreview()
    }

    fun dismissDialog() {
        if (state.value.busy && state.value.dialog != TmuxManagerDialog.Preview) return
        if (state.value.dialog == TmuxManagerDialog.Preview) {
            generation++
            job?.cancel()
        }
        mutableState.value =
            state.value.copy(
                dialog = null,
                target = null,
                preview = null,
                busy = false,
                error = null,
            )
    }

    fun refresh() = request(loading = true) { source -> reload(source) }

    fun create(name: String, directory: String) {
        if (state.value.dialog != TmuxManagerDialog.Create) return
        request { source ->
            backend.create(source, name, directory)
            currentCoroutineContext().ensureActive()
            update { it.copy(dialog = null, notice = "Session created. Tap it to connect.") }
            reload(source)
        }
    }

    fun rename(name: String) {
        val target = state.value.target ?: return
        if (state.value.dialog != TmuxManagerDialog.Rename) return
        request { source ->
            backend.rename(source, target, name)
            currentCoroutineContext().ensureActive()
            update { it.copy(dialog = null, target = null, notice = "Session renamed") }
            reload(source)
        }
    }

    // The only termination entry point requires the confirmation dialog to still be open.
    fun confirmTerminate() {
        val target = state.value.target ?: return
        if (state.value.dialog != TmuxManagerDialog.Terminate) return
        request { source ->
            backend.terminate(source, target)
            currentCoroutineContext().ensureActive()
            update {
                it.copy(
                    dialog = null,
                    target = null,
                    notice = "Session terminated. SSH tabs can be reconnected or closed.",
                )
            }
            reload(source)
        }
    }

    fun connect(target: RemoteMultiplexerSession) = request { source ->
        backend.connect(source, target)
        if (source.matches(backend.currentContext())) dismiss()
    }

    fun refreshPreview() {
        val target = state.value.target ?: return
        if (state.value.dialog != TmuxManagerDialog.Preview || nowMs() - previewAt < 2_000) return
        if (state.value.busy) return
        previewAt = nowMs()
        request { source ->
            val preview = backend.preview(source, target)
            currentCoroutineContext().ensureActive()
            update { it.copy(preview = preview) }
        }
    }

    private suspend fun reload(source: TmuxManagerContext) {
        val sessions = backend.list(source)
        currentCoroutineContext().ensureActive()
        if (source.matches(backend.currentContext())) {
            update {
                it.copy(
                    sessions = sessions,
                    currentIdentity = backend.currentContext()?.spec?.tmuxSessionIdentity,
                )
            }
        }
    }

    private fun update(transform: (TmuxManagerState) -> TmuxManagerState) {
        if (state.value.visible && context?.matches(backend.currentContext()) == true) {
            mutableState.value = transform(state.value)
        }
    }

    private fun request(loading: Boolean = false, block: suspend (TmuxManagerContext) -> Unit) {
        if (!state.value.visible || state.value.busy) return
        val source = context ?: return
        if (!source.matches(backend.currentContext())) {
            dismiss()
            return
        }
        val requestGeneration = generation
        mutableState.value =
            state.value.copy(busy = true, loading = loading, error = null, notice = null)
        job = scope.launch {
            try {
                block(source)
            } catch (e: TimeoutCancellationException) {
                if (requestGeneration == generation)
                    update { it.copy(error = "SSH/tmux operation timed out. Please retry.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == generation)
                    update { it.copy(error = e.message ?: "SSH/tmux operation failed") }
            } finally {
                if (requestGeneration == generation)
                    update { it.copy(busy = false, loading = false) }
            }
        }
    }
}
