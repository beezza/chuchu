package com.jossephus.chuchu.ui.screens.Terminal

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.multiplexer.RemoteMultiplexerSession
import com.jossephus.chuchu.service.multiplexer.TmuxSessionIdentity
import com.jossephus.chuchu.service.terminal.TabSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxManagerControllerTest {
    private val session =
        RemoteMultiplexerSession("coding", true, TmuxSessionIdentity("\$0", 1, 2, 3))
    private val spec =
        TabSpec(hostId = 1, host = "host-a", username = "user", multiplexer = MultiplexerType.Tmux)

    private class Backend(var context: TmuxManagerContext?) : TmuxManagerBackend {
        var listCalls = 0
        var terminationCalls = 0
        var connectCalls = 0
        var previewCalls = 0
        var listReply: suspend () -> List<RemoteMultiplexerSession> = { emptyList() }
        var terminateReply: suspend () -> Unit = {}

        override fun currentContext() = context

        override suspend fun list(context: TmuxManagerContext): List<RemoteMultiplexerSession> {
            listCalls++
            return listReply()
        }

        override suspend fun create(context: TmuxManagerContext, name: String, directory: String) {}

        override suspend fun rename(
            context: TmuxManagerContext,
            session: RemoteMultiplexerSession,
            name: String,
        ) {}

        override suspend fun terminate(
            context: TmuxManagerContext,
            session: RemoteMultiplexerSession,
        ) {
            terminationCalls++
            terminateReply()
        }

        override suspend fun preview(
            context: TmuxManagerContext,
            session: RemoteMultiplexerSession,
        ): String {
            previewCalls++
            return "preview"
        }

        override suspend fun connect(
            context: TmuxManagerContext,
            session: RemoteMultiplexerSession,
        ) {
            connectCalls++
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun cancelConfirmationNeverTerminates() {
        val backend = Backend(TmuxManagerContext("tab-a", spec))
        val controller = TmuxManagerController(scope(), backend)
        controller.open()
        controller.showDialog(TmuxManagerDialog.Terminate, session)
        controller.dismissDialog()
        controller.confirmTerminate()
        assertEquals(0, backend.terminationCalls)
        controller.dismiss()
    }

    @Test
    fun explicitConfirmationTerminatesOnceAndRefreshes() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend =
            Backend(TmuxManagerContext("tab-a", spec)).apply { terminateReply = { gate.await() } }
        val controller = TmuxManagerController(scope(), backend)
        controller.open()
        controller.showDialog(TmuxManagerDialog.Terminate, session)
        controller.confirmTerminate()
        controller.confirmTerminate()
        assertEquals(1, backend.terminationCalls)
        assertTrue(controller.state.value.busy)
        gate.complete(Unit)
        assertNull(controller.state.value.dialog)
        assertEquals(2, backend.listCalls)
        controller.dismiss()
    }

    @Test
    fun changedHostOrTabCannotReceiveOldActions() {
        for (next in
            listOf(
                TmuxManagerContext("tab-b", spec),
                TmuxManagerContext("tab-a", spec.copy(host = "host-b")),
            )) {
            val backend = Backend(TmuxManagerContext("tab-a", spec))
            val controller = TmuxManagerController(scope(), backend)
            controller.open()
            controller.showDialog(TmuxManagerDialog.Terminate, session)
            backend.context = next
            controller.confirmTerminate()
            assertEquals(0, backend.terminationCalls)
            assertFalse(controller.state.value.visible)
        }
    }

    @Test
    fun ignoredCancellationCannotOverwriteReopenedPanel() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend =
            Backend(TmuxManagerContext("tab-a", spec)).apply {
                listReply = {
                    withContext(NonCancellable) { gate.await() }
                    listOf(session)
                }
            }
        val controller = TmuxManagerController(scope(), backend)
        controller.open()
        controller.refresh()
        assertEquals(1, backend.listCalls)
        controller.dismiss()
        backend.listReply = { emptyList() }
        controller.open()
        gate.complete(Unit)
        yield()
        assertTrue(controller.state.value.visible)
        assertTrue(controller.state.value.sessions.isEmpty())
        assertFalse(controller.state.value.busy)
        controller.dismiss()
    }

    @Test
    fun switchingHostDiscardsPendingResultsAndClearsSecrets() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend =
            Backend(TmuxManagerContext("tab-a", spec)).apply {
                listReply = {
                    gate.await()
                    listOf(session)
                }
            }
        val controller = TmuxManagerController(scope(), backend)
        controller.open()
        backend.context = TmuxManagerContext("tab-b", spec.copy(hostId = 2, host = "host-b"))
        controller.connectionChanged()
        gate.complete(Unit)
        assertFalse(controller.state.value.visible)
        assertTrue(controller.state.value.sessions.isEmpty())
        assertNull(controller.state.value.preview)
    }

    @Test
    fun searchRetainsSessionsAndMakesNoRemoteRequests() {
        val backend =
            Backend(TmuxManagerContext("tab-a", spec)).apply { listReply = { listOf(session) } }
        val controller = TmuxManagerController(scope(), backend)
        controller.open()
        controller.search("COD")
        assertEquals(1, backend.listCalls)
        assertEquals(listOf(session), controller.state.value.sessions)
        controller.dismiss()
        controller.refresh()
        assertEquals(1, backend.listCalls)
    }

    @Test
    fun failedSwitchKeepsPanelOpenForRetryAndSuccessClosesIt() {
        val backend = Backend(TmuxManagerContext("tab-a", spec))
        val failing =
            object : TmuxManagerBackend by backend {
                override suspend fun connect(
                    context: TmuxManagerContext,
                    session: RemoteMultiplexerSession,
                ) {
                    error("SSH disconnected")
                }
            }
        val controller = TmuxManagerController(scope(), failing)
        controller.open()
        controller.connect(session)
        assertTrue(controller.state.value.visible)
        assertEquals("SSH disconnected", controller.state.value.error)
        controller.dismiss()
        val success = TmuxManagerController(scope(), backend)
        success.open()
        success.connect(session)
        assertFalse(success.state.value.visible)
        assertEquals(1, backend.connectCalls)
    }

    @Test
    fun previewIsOnDemandThrottledAndClearedOnClose() {
        var now = 0L
        val backend = Backend(TmuxManagerContext("tab-a", spec))
        val controller = TmuxManagerController(scope(), backend) { now }
        controller.open()
        assertEquals(0, backend.previewCalls)
        controller.showDialog(TmuxManagerDialog.Preview, session)
        controller.refreshPreview()
        assertEquals(1, backend.previewCalls)
        now += 2_000
        controller.refreshPreview()
        assertEquals(2, backend.previewCalls)
        assertEquals("preview", controller.state.value.preview)
        controller.dismissDialog()
        assertNull(controller.state.value.preview)
        controller.dismiss()
    }

    @Test
    fun failedRefreshPreservesSessionsAndRenameErrorPreservesDialog() {
        val backend =
            Backend(TmuxManagerContext("tab-a", spec)).apply { listReply = { listOf(session) } }
        val failing =
            object : TmuxManagerBackend by backend {
                override suspend fun rename(
                    context: TmuxManagerContext,
                    session: RemoteMultiplexerSession,
                    name: String,
                ) {
                    error("duplicate name")
                }
            }
        val controller = TmuxManagerController(scope(), failing)
        controller.open()
        backend.listReply = { error("SSH disconnected") }
        controller.refresh()
        assertEquals(listOf(session), controller.state.value.sessions)
        assertEquals("SSH disconnected", controller.state.value.error)
        controller.showDialog(TmuxManagerDialog.Rename, session)
        controller.rename("duplicate")
        assertEquals(TmuxManagerDialog.Rename, controller.state.value.dialog)
        assertEquals(session, controller.state.value.target)
        assertEquals("duplicate name", controller.state.value.error)
        controller.dismiss()
    }

    @Test
    fun operationTimeoutIsVisibleAndRetryRemainsAvailable() {
        val backend =
            Backend(TmuxManagerContext("tab-a", spec)).apply {
                listReply = { withTimeout(0) { emptyList() } }
            }
        val controller = TmuxManagerController(scope(), backend)
        controller.open()
        assertTrue(controller.state.value.error!!.contains("timed out"))
        assertFalse(controller.state.value.busy)
        backend.listReply = { listOf(session) }
        controller.refresh()
        assertNull(controller.state.value.error)
        assertEquals(listOf(session), controller.state.value.sessions)
        controller.dismiss()
    }

    @Test
    fun unsupportedTransportAndMultiplexerStayDisabled() {
        for (candidate in
            listOf(
                spec.copy(transport = Transport.Mosh),
                spec.copy(transport = Transport.LocalShell),
                spec.copy(multiplexer = MultiplexerType.Zmx),
            )) {
            val backend = Backend(TmuxManagerContext("tab-a", candidate))
            val controller = TmuxManagerController(scope(), backend)
            controller.open()
            assertFalse(controller.state.value.visible)
            assertEquals(0, backend.listCalls)
        }
    }
}
