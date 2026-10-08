package io.github.springthief1123.lovelyspace.lock

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private class MemoryStore : AppLockPersistence {
        var config = AppLockConfig()
        var saltAndHash: Pair<String, String>? = null
        var attempts = 0 to 0L
        override fun attempts() = attempts
        override fun saveAttempts(failures: Int, lockedOutUntil: Long) { attempts = failures to lockedOutUntil }
        override fun load() = config
        override fun secret() = saltAndHash
        override fun save(config: AppLockConfig, salt: String?, hash: String?) {
            this.config = config
            if (config.method == null) saltAndHash = null
            else if (salt != null && hash != null) saltAndHash = salt to hash
        }
    }

    @Test fun secretIsStoredAsSaltedHash() {
        val salt = LockSecret.newSalt()
        val hash = LockSecret.hash("1234", salt)
        assertNotEquals("1234", hash)
        assertTrue(LockSecret.matches("1234", salt, hash))
        assertFalse(LockSecret.matches("1235", salt, hash))
        // 同じパスコードでも塩が違えば別のハッシュになる。
        assertNotEquals(hash, LockSecret.hash("1234", LockSecret.newSalt()))
    }

    @Test fun locksOnlyAfterTheChosenDelay() {
        assertFalse(shouldLock(null, 1_000, LockDelay.IMMEDIATE))
        assertTrue(shouldLock(1_000, 1_000, LockDelay.IMMEDIATE))
        assertFalse(shouldLock(0, 5 * 60_000L - 1, LockDelay.MINUTES_5))
        assertTrue(shouldLock(0, 5 * 60_000L, LockDelay.MINUTES_5))
        assertFalse(shouldLock(0, 59 * 60_000L, LockDelay.HOUR_1))
    }

    @Test fun controllerLocksAtStartAndAfterBackground() {
        var now = 0L
        val store = MemoryStore()
        val first = AppLockController(store, { now }, { now })
        assertFalse(first.state.value.locked)
        first.enable(LockMethod.PASSCODE, "2468")
        first.setDelay(LockDelay.MINUTES_5)
        assertEquals(4, store.config.passcodeLength)

        // 起動し直したらロックから始まる。
        val controller = AppLockController(store, { now }, { now })
        assertTrue(controller.state.value.locked)
        assertFalse(controller.unlock("1111"))
        assertTrue(controller.unlock("2468"))
        assertFalse(controller.state.value.locked)

        controller.onBackground(); now += 60_000; controller.onForeground()
        assertFalse(controller.state.value.locked)
        controller.onBackground(); now += 5 * 60_000L; controller.onForeground()
        assertTrue(controller.state.value.locked)
    }

    @Test fun immediateDelayLocksAsSoonAsTheAppLeaves() {
        val store = MemoryStore()
        val controller = AppLockController(store, { 0 }, { 0 })
        controller.enable(LockMethod.PATTERN, patternSecret(listOf(0, 1, 2, 5)))
        controller.onBackground()
        assertTrue(controller.state.value.locked)
        assertTrue(controller.unlock("0,1,2,5"))
    }

    @Test fun repeatedFailuresWaitBeforeTheNextTry() {
        var now = 0L
        val controller = AppLockController(MemoryStore(), { now }, { now })
        controller.enable(LockMethod.PASSCODE, "2468")
        controller.onBackground()
        repeat(AppLockController.MAX_FAILURES) { assertFalse(controller.unlock("0000")) }
        assertFalse(controller.unlock("2468"))
        now += AppLockController.LOCKOUT_MILLIS
        assertTrue(controller.unlock("2468"))
    }

    @Test fun lockoutSurvivesARestart() {
        var now = 0L
        val store = MemoryStore()
        AppLockController(store, { now }, { now }).apply {
            enable(LockMethod.PASSCODE, "2468")
            repeat(AppLockController.MAX_FAILURES) { unlock("0000") }
        }
        // アプリを終了させて開き直しても、待ち時間は残る。
        val restarted = AppLockController(store, { now }, { now })
        assertFalse(restarted.unlock("2468"))
        now += AppLockController.LOCKOUT_MILLIS
        assertTrue(restarted.unlock("2468"))
    }

    @Test fun backgroundTimeUsesTheMonotonicClock() {
        var wall = 1_000_000L
        var elapsed = 0L
        val controller = AppLockController(MemoryStore(), { wall }, { elapsed })
        controller.enable(LockMethod.PASSCODE, "2468")
        controller.setDelay(LockDelay.MINUTES_5)
        controller.onBackground()
        // 端末の時計を戻しても、実際に5分たてばロックする。
        wall -= 60 * 60_000L
        elapsed += 5 * 60_000L
        controller.onForeground()
        assertTrue(controller.state.value.locked)
    }

    @Test fun disablingRemovesTheSecret() {
        val store = MemoryStore()
        val controller = AppLockController(store, { 0 }, { 0 })
        controller.enable(LockMethod.PASSCODE, "2468")
        controller.setBiometric(true)
        controller.disable()
        assertNull(store.saltAndHash)
        assertFalse(store.config.biometric)
        controller.onBackground()
        assertFalse(controller.state.value.locked)
    }

    @Test fun biometricUnlocksOnlyWhenTurnedOn() {
        val controller = AppLockController(MemoryStore(), { 0 }, { 0 })
        controller.enable(LockMethod.PASSCODE, "2468")
        controller.onBackground()
        controller.unlockWithBiometric()
        assertTrue(controller.state.value.locked)
        controller.setBiometric(true)
        controller.unlockWithBiometric()
        assertFalse(controller.state.value.locked)
    }

    @Test fun patternFindsDotsAndTheDotsInBetween() {
        assertEquals(0, patternDotAt(Offset(50f, 50f), 300f))
        assertEquals(8, patternDotAt(Offset(250f, 250f), 300f))
        // 点と点の間は拾わない。
        assertNull(patternDotAt(Offset(100f, 50f), 300f))
        assertEquals(1, patternDotBetween(0, 2, emptyList()))
        assertEquals(4, patternDotBetween(0, 8, emptyList()))
        assertNull(patternDotBetween(0, 8, listOf(4)))
        assertNull(patternDotBetween(0, 5, emptyList()))
    }
}
