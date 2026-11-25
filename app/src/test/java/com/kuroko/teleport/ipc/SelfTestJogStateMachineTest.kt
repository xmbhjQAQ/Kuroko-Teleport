package com.kuroko.teleport.ipc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestJogStateMachineTest {

    @Test
    fun setModeFailureAbortsBeforeHoldAndLeavesNothingDirty() = runBlocking {
        val sm = SelfTestJogStateMachine()
        var holdCalls = 0

        val outcome = sm.startJogPulse(
            prevActiveSnapshot = false,
            prevMotionModeSnapshot = KurokoProtocol.MOTION_WALK,
            prevCarryModeSnapshot = KurokoProtocol.CARRY_POCKET,
            currentDaemonPid = 4100,
            setMode = { _, _ -> Result.failure(IllegalStateException("mode failed")) },
            hold = {
                holdCalls += 1
                Result.success("OK HOLD")
            }
        )

        assertEquals(JogPulseStartOutcome.MODE_FAILED, outcome)
        assertEquals(0, holdCalls)
        assertFalse(sm.modeModified)
        assertFalse(sm.holdStarted)
        assertFalse(sm.jogActiveUi)
        assertFalse(sm.hasAnyDirtyState())
        assertEquals(0, sm.daemonPid)
    }

    @Test
    fun holdFailureWithImmediateRollbackRestoresModeWithoutCallingStop() = runBlocking {
        val sm = SelfTestJogStateMachine()
        val modeCalls = mutableListOf<Pair<Int, Int>>()
        var stopCalls = 0

        val outcome = sm.startJogPulse(
            prevActiveSnapshot = false,
            prevMotionModeSnapshot = KurokoProtocol.MOTION_WALK,
            prevCarryModeSnapshot = KurokoProtocol.CARRY_POCKET,
            currentDaemonPid = 4100,
            setMode = { m, c ->
                modeCalls.add(m to c)
                Result.success("OK SET_MODE")
            },
            hold = { Result.failure(IllegalStateException("hold failed")) }
        )

        assertEquals(JogPulseStartOutcome.HOLD_FAILED_ROLLED_BACK, outcome)
        assertEquals(
            listOf(
                KurokoProtocol.MOTION_JOG to KurokoProtocol.CARRY_HAND_SWING,
                KurokoProtocol.MOTION_WALK to KurokoProtocol.CARRY_POCKET
            ),
            modeCalls
        )
        assertFalse(sm.modeModified)
        assertFalse(sm.holdStarted)
        assertFalse(sm.jogActiveUi)
        assertFalse(sm.hasAnyDirtyState())
        assertEquals(0, sm.daemonPid)

        val cleanup = sm.runCleanup(
            inSession = false,
            currentDaemonPid = 4100,
            setMode = { m, c ->
                modeCalls.add(m to c)
                Result.success("OK")
            },
            stop = {
                stopCalls += 1
                Result.success("OK")
            },
            restoreTargets = { Result.success("OK") }
        )
        assertFalse(cleanup.hadPendingWork)
        assertEquals(0, stopCalls)
    }

    @Test
    fun holdFailureWithFailedRollbackDefersModeRestoreAndPreservesOriginalMode() = runBlocking {
        val sm = SelfTestJogStateMachine()
        sm.markTargetsInjected(pid = 4200)

        var modeCallIdx = 0
        val modeCalls = mutableListOf<Pair<Int, Int>>()
        var stopCalls = 0
        var targetRestoreCalls = 0

        // 1) SET_MODE succeeds, HOLD fails, immediate rollback SET_MODE fails
        val outcome1 = sm.startJogPulse(
            prevActiveSnapshot = false,
            prevMotionModeSnapshot = KurokoProtocol.MOTION_WALK,
            prevCarryModeSnapshot = KurokoProtocol.CARRY_POCKET,
            currentDaemonPid = 4200,
            setMode = { m, c ->
                modeCalls.add(m to c)
                modeCallIdx += 1
                if (modeCallIdx == 1) Result.success("OK") else Result.failure(IllegalStateException("offline"))
            },
            hold = { Result.failure(IllegalStateException("hold failed")) }
        )

        assertEquals(JogPulseStartOutcome.HOLD_FAILED_ROLLBACK_PENDING, outcome1)
        assertTrue(sm.modeModified)
        assertFalse(sm.holdStarted)
        assertFalse(sm.jogActiveUi)
        assertTrue(sm.needsCleanup(inSession = true))
        assertEquals(KurokoProtocol.MOTION_WALK, sm.prevMotionMode)
        assertEquals(KurokoProtocol.CARRY_POCKET, sm.prevCarryMode)

        // 2) Retrying startJogPulse while modeModified=true must NOT overwrite saved prevMotionMode with MOTION_JOG
        val outcome2 = sm.startJogPulse(
            prevActiveSnapshot = false,
            prevMotionModeSnapshot = KurokoProtocol.MOTION_JOG,
            prevCarryModeSnapshot = KurokoProtocol.CARRY_HAND_SWING,
            currentDaemonPid = 4200,
            setMode = { _, _ -> Result.failure(IllegalStateException("still offline")) },
            hold = { Result.failure(IllegalStateException("unreached")) }
        )
        assertEquals(JogPulseStartOutcome.MODE_FAILED, outcome2)
        assertEquals(KurokoProtocol.MOTION_WALK, sm.prevMotionMode)
        assertEquals(KurokoProtocol.CARRY_POCKET, sm.prevCarryMode)

        // 3) In-session pollOnce cleanup retries ONLY setMode, leaving targetsActive untouched and NOT calling stop
        val restoredModes = mutableListOf<Pair<Int, Int>>()
        val inSessionCleanup = sm.runCleanup(
            inSession = true,
            currentDaemonPid = 4200,
            setMode = { m, c ->
                restoredModes.add(m to c)
                Result.success("OK")
            },
            stop = {
                stopCalls += 1
                Result.success("OK")
            },
            restoreTargets = {
                targetRestoreCalls += 1
                Result.success("OK")
            }
        )
        assertTrue(inSessionCleanup.hadPendingWork)
        assertTrue(inSessionCleanup.allCleaned)
        assertFalse(inSessionCleanup.clearedByDaemonRestart)
        assertEquals(listOf(KurokoProtocol.MOTION_WALK to KurokoProtocol.CARRY_POCKET), restoredModes)
        assertFalse(sm.modeModified)
        assertFalse(sm.holdStarted)
        assertTrue(sm.targetsActive)
        assertEquals(0, stopCalls)
        assertEquals(0, targetRestoreCalls)
    }

    @Test
    fun daemonPidChangeClearsDeferredStateWithoutIssuingStaleCommands() = runBlocking {
        val sm = SelfTestJogStateMachine()
        sm.markTargetsInjected(pid = 4200)
        val outcome = sm.startJogPulse(
            prevActiveSnapshot = false,
            prevMotionModeSnapshot = KurokoProtocol.MOTION_RUN,
            prevCarryModeSnapshot = KurokoProtocol.CARRY_HAND_SWING,
            currentDaemonPid = 4200,
            setMode = { _, _ -> Result.success("OK") },
            hold = { Result.success("OK") }
        )
        assertEquals(JogPulseStartOutcome.ACTIVATED, outcome)
        assertTrue(sm.targetsActive)
        assertTrue(sm.modeModified)
        assertTrue(sm.holdStarted)

        var anyCommandCalled = false
        val cleanup = sm.runCleanup(
            inSession = false,
            currentDaemonPid = 5300,
            setMode = { _, _ -> anyCommandCalled = true; Result.success("OK") },
            stop = { anyCommandCalled = true; Result.success("OK") },
            restoreTargets = { anyCommandCalled = true; Result.success("OK") }
        )

        assertTrue(cleanup.hadPendingWork)
        assertTrue(cleanup.clearedByDaemonRestart)
        assertTrue(cleanup.allCleaned)
        assertFalse(anyCommandCalled)
        assertFalse(sm.hasAnyDirtyState())
        assertEquals(0, sm.daemonPid)
    }

    @Test
    fun partialEndCleanupRetriesOnlyFailedStepsOnReconnect() = runBlocking {
        val sm = SelfTestJogStateMachine()
        sm.markTargetsInjected(pid = 4200)
        val outcome = sm.startJogPulse(
            prevActiveSnapshot = false,
            prevMotionModeSnapshot = KurokoProtocol.MOTION_STILL,
            prevCarryModeSnapshot = KurokoProtocol.CARRY_POCKET,
            currentDaemonPid = 4200,
            setMode = { _, _ -> Result.success("OK") },
            hold = { Result.success("OK") }
        )
        assertEquals(JogPulseStartOutcome.ACTIVATED, outcome)

        var modeCalls = 0
        var stopCalls = 0
        var targetCalls = 0

        // First cleanup attempt: setMode & restoreTargets succeed, stop fails
        val first = sm.runCleanup(
            inSession = false,
            currentDaemonPid = 4200,
            setMode = { _, _ -> modeCalls += 1; Result.success("OK") },
            stop = { stopCalls += 1; Result.failure(IllegalStateException("timeout")) },
            restoreTargets = { targetCalls += 1; Result.success("OK") }
        )
        assertTrue(first.hadPendingWork)
        assertFalse(first.allCleaned)
        assertFalse(sm.jogActiveUi)
        assertFalse(sm.modeModified)
        assertTrue(sm.holdStarted)
        assertFalse(sm.targetsActive)
        assertEquals(1, modeCalls)
        assertEquals(1, stopCalls)
        assertEquals(1, targetCalls)

        // Reconnect retry: ONLY stop is retried
        val second = sm.runCleanup(
            inSession = false,
            currentDaemonPid = 4200,
            setMode = { _, _ -> modeCalls += 1; Result.success("OK") },
            stop = { stopCalls += 1; Result.success("OK") },
            restoreTargets = { targetCalls += 1; Result.success("OK") }
        )
        assertTrue(second.hadPendingWork)
        assertTrue(second.allCleaned)
        assertFalse(sm.hasAnyDirtyState())
        assertEquals(0, sm.daemonPid)
        assertEquals(1, modeCalls)
        assertEquals(2, stopCalls)
        assertEquals(1, targetCalls)
    }
}
