package io.github.springthief1123.lovelyspace.lock

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** ロックを解く方法。生体認証は、これに加えて使うかどうかを選ぶ（使えないときはこちらに戻れる）。 */
enum class LockMethod(val label: String) {
    PASSCODE("パスコード"),
    PATTERN("パターン"),
}

/** アプリを背景に回してからロックするまでの時間。 */
enum class LockDelay(val label: String, val minutes: Int) {
    IMMEDIATE("すぐ", 0),
    MINUTES_5("5分後", 5),
    MINUTES_10("10分後", 10),
    MINUTES_20("20分後", 20),
    MINUTES_30("30分後", 30),
    HOUR_1("1時間後", 60),
    ;

    val millis: Long get() = minutes * 60_000L
}

/** ロックの設定。[method] が null ならロックしない。パスコードは桁数だけを持ち、中身はハッシュで別に保存する。 */
data class AppLockConfig(
    val method: LockMethod? = null,
    val passcodeLength: Int = 0,
    val biometric: Boolean = false,
    val delay: LockDelay = LockDelay.IMMEDIATE,
) {
    val enabled: Boolean get() = method != null
}

/** パスコード・パターンをハッシュにする。平文は保存しない。 */
object LockSecret {
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256

    fun newSalt(random: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))

    fun hash(secret: String, salt: String): String {
        val spec = PBEKeySpec(secret.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(key)
        } finally {
            spec.clearPassword()
        }
    }

    fun matches(secret: String, salt: String, expected: String): Boolean =
        MessageDigest.isEqual(hash(secret, salt).toByteArray(), expected.toByteArray())
}

/** パターンは 0〜8 の点の番号を並べた文字列にしてからハッシュにする。 */
fun patternSecret(dots: List<Int>): String = dots.joinToString(",")

/** 背景に回った時刻 [backgroundAt] から [now] までに [delay] 以上たっていればロックする。 */
fun shouldLock(backgroundAt: Long?, now: Long, delay: LockDelay): Boolean =
    backgroundAt != null && now - backgroundAt >= delay.millis

/** ロックの設定とハッシュの保存先。起動直後に画面を隠せるよう、同期で読める SharedPreferences に置く。 */
interface AppLockPersistence {
    fun load(): AppLockConfig
    fun secret(): Pair<String, String>?
    fun save(config: AppLockConfig, salt: String?, hash: String?)
}

class AppLockStore(private val prefs: SharedPreferences) : AppLockPersistence {
    override fun load(): AppLockConfig = AppLockConfig(
        method = prefs.getString(KEY_METHOD, null)?.let { runCatching { LockMethod.valueOf(it) }.getOrNull() }
            ?.takeIf { secret() != null },
        passcodeLength = prefs.getInt(KEY_LENGTH, 0),
        biometric = prefs.getBoolean(KEY_BIOMETRIC, false),
        delay = prefs.getString(KEY_DELAY, null)?.let { runCatching { LockDelay.valueOf(it) }.getOrNull() } ?: LockDelay.IMMEDIATE,
    )

    override fun secret(): Pair<String, String>? {
        val salt = prefs.getString(KEY_SALT, null) ?: return null
        val hash = prefs.getString(KEY_HASH, null) ?: return null
        return salt to hash
    }

    override fun save(config: AppLockConfig, salt: String?, hash: String?) {
        prefs.edit().apply {
            if (config.method == null) remove(KEY_METHOD) else putString(KEY_METHOD, config.method.name)
            putInt(KEY_LENGTH, config.passcodeLength)
            putBoolean(KEY_BIOMETRIC, config.biometric)
            putString(KEY_DELAY, config.delay.name)
            if (config.method == null) { remove(KEY_SALT); remove(KEY_HASH) }
            else if (salt != null && hash != null) { putString(KEY_SALT, salt); putString(KEY_HASH, hash) }
        }.commit()
    }

    companion object {
        const val PREFS = "app_lock"
        private const val KEY_METHOD = "method"
        private const val KEY_LENGTH = "passcode_length"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_DELAY = "delay"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
    }
}

/** ロック画面に出す状態。続けて間違えたら [lockedOutUntil] まで入力を受け付けない。 */
data class AppLockState(
    val config: AppLockConfig = AppLockConfig(),
    val locked: Boolean = false,
    val failures: Int = 0,
    val lockedOutUntil: Long = 0,
)

/**
 * アプリロックの判定。アプリの起動時と、設定した時間より長く背景にあったときにロックする。
 * 背景の巡回・順番待ちの通知はロックと関係なく届く（通知から開いてもロック画面が先に出る）。
 */
class AppLockController(
    private val store: AppLockPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(store.load().let { AppLockState(config = it, locked = it.enabled) })
    val state: StateFlow<AppLockState> = _state.asStateFlow()
    private var backgroundAt: Long? = null

    fun onBackground() {
        backgroundAt = clock()
        val config = _state.value.config
        // 「すぐ」なら背景に回った時点でロックし、戻ったときに中身が一瞬見えないようにする。
        if (config.enabled && config.delay == LockDelay.IMMEDIATE) _state.update { it.copy(locked = true) }
    }

    fun onForeground() {
        val config = _state.value.config
        if (config.enabled && shouldLock(backgroundAt, clock(), config.delay)) _state.update { it.copy(locked = true) }
        backgroundAt = null
    }

    /** パスコード・パターンで解く。合っていれば true。 */
    fun unlock(secret: String): Boolean {
        val now = clock()
        if (now < _state.value.lockedOutUntil) return false
        val (salt, hash) = store.secret() ?: return false
        if (LockSecret.matches(secret, salt, hash)) {
            _state.update { it.copy(locked = false, failures = 0, lockedOutUntil = 0) }
            return true
        }
        _state.update {
            val failures = it.failures + 1
            if (failures >= MAX_FAILURES) it.copy(failures = 0, lockedOutUntil = now + LOCKOUT_MILLIS)
            else it.copy(failures = failures)
        }
        return false
    }

    /** 生体認証が通ったとき。 */
    fun unlockWithBiometric() {
        if (_state.value.config.biometric) _state.update { it.copy(locked = false, failures = 0, lockedOutUntil = 0) }
    }

    /** ロックを有効にする、または解除の方法を変える。[secret] はパスコードの数字かパターンの文字列。 */
    fun enable(method: LockMethod, secret: String) {
        val salt = LockSecret.newSalt()
        val config = _state.value.config.copy(method = method, passcodeLength = if (method == LockMethod.PASSCODE) secret.length else 0)
        store.save(config, salt, LockSecret.hash(secret, salt))
        _state.update { it.copy(config = config, locked = false) }
    }

    fun disable() {
        val config = _state.value.config.copy(method = null, passcodeLength = 0, biometric = false)
        store.save(config, null, null)
        _state.update { it.copy(config = config, locked = false, failures = 0, lockedOutUntil = 0) }
    }

    fun setBiometric(enabled: Boolean) = updateConfig { it.copy(biometric = enabled) }
    fun setDelay(delay: LockDelay) = updateConfig { it.copy(delay = delay) }

    private fun updateConfig(change: (AppLockConfig) -> AppLockConfig) {
        val config = change(_state.value.config)
        store.save(config, null, null)
        _state.update { it.copy(config = config) }
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCKOUT_MILLIS = 30_000L
    }
}
