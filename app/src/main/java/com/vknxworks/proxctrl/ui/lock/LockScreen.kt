package com.vknxworks.proxctrl.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.vknxworks.proxctrl.ui.components.PillButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.AppContainer
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.lock.AppLock
import com.vknxworks.proxctrl.data.lock.LockVault
import com.vknxworks.proxctrl.data.lock.PinRequest
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Covers the whole app while it's locked (and, for a moment at start-up, while the lock setting is
 * read), so nothing behind it is shown or can be tapped.
 */
@Composable
fun LockScreen(activity: FragmentActivity) {
    val state by AppLock.state.collectAsStateWithLifecycle()
    // Back leaves the app instead of reaching the screens behind the lock.
    BackHandler { activity.moveTaskToBack(true) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state == AppLock.State.LOCKED) {
            if (AppLock.legacy) LegacyLockedScreen(activity) else PinLockScreen(activity)
        }
    }
}

/**
 * PIN screens on top of the unlocked app: choosing a PIN after updating from the old lock, turning the
 * lock on or off, changing the PIN, and confirming a change ("Confirm changes").
 */
@Composable
fun LockOverlays(activity: FragmentActivity) {
    val context = LocalContext.current
    val c = context.container
    val vault = c.vault
    val confirm = AppLock.confirm
    when {
        AppLock.legacy -> PinSetupScreen(
            activity,
            title = stringResource(R.string.pin_choose_title),
            intro = stringResource(R.string.pin_migrate_intro),
            verifyCurrent = false,
            cancellable = false,
            onDone = { pin -> c.enableLock(pin, defaultBackgroundChecks(c)) },
            onCancel = {},
        )
        confirm != null -> ConfirmChangeScreen(activity, onResult = { ok -> confirm.complete(ok) })
        else -> when (AppLock.request) {
            PinRequest.ENABLE -> PinSetupScreen(
                activity,
                title = stringResource(R.string.pin_choose_title),
                intro = stringResource(R.string.pin_choose_intro, LockVault.PIN_MIN),
                verifyCurrent = false,
                cancellable = true,
                onDone = { pin -> c.enableLock(pin, defaultBackgroundChecks(c)); AppLock.request = null },
                onCancel = { AppLock.request = null },
            )
            PinRequest.CHANGE -> PinSetupScreen(
                activity,
                title = stringResource(R.string.pin_change_title),
                intro = stringResource(R.string.pin_change_intro, LockVault.PIN_MIN),
                verifyCurrent = true,
                cancellable = true,
                onDone = { pin -> withContext(Dispatchers.Default) { vault.changePin(pin) }; AppLock.request = null },
                onCancel = { AppLock.request = null },
            )
            PinRequest.DISABLE -> PinSetupScreen(
                activity,
                title = stringResource(R.string.pin_disable_title),
                intro = stringResource(R.string.pin_disable_intro),
                verifyCurrent = true,
                verifyOnly = true,
                cancellable = true,
                onDone = { c.disableLock(); AppLock.request = null },
                onCancel = { AppLock.request = null },
            )
            null -> Unit
        }
    }
}

/** Background checks stay on when they're in use (notifications or widgets), so turning the lock on doesn't silently stop them. */
private suspend fun defaultBackgroundChecks(c: AppContainer): Boolean {
    val n = c.notifySettings.current()
    return n.polling || n.push || WidgetRefresher.anyWidgets(c.context)
}

@Composable
private fun LegacyLockedScreen(activity: FragmentActivity) {
    var error by remember { mutableStateOf<String?>(null) }
    fun unlock() = AppLock.legacyAuthenticate(activity) { error = it }
    LaunchedEffect(AppLock.lockCount) {
        // No screen lock or fingerprint on the phone any more: there's nothing to confirm with, and
        // anyone holding the phone could already open it, so go straight to choosing a PIN.
        if (!AppLock.legacyAvailable(activity)) AppLock.markUnlocked() else unlock()
    }
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LockBadge()
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.pin_legacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            PillButton(stringResource(R.string.lock_unlock), onClick = { unlock() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LockBadge() {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Lock, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
}

/** The lock screen: PIN pad, fingerprint, and "Forgot PIN?". */
@Composable
private fun PinLockScreen(activity: FragmentActivity) {
    val c = LocalContext.current.container
    val vault = c.vault
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }
    var forgetting by remember { mutableStateOf(false) }
    val bioAvailable = remember(AppLock.lockCount) {
        vault.biometricEnabled && AppLock.biometricAvailable(activity) && !vault.pinRequired()
    }
    val wrongText = stringResource(R.string.pin_wrong)
    val changedText = stringResource(R.string.pin_fingerprint_changed)
    val failedText = stringResource(R.string.pin_fingerprint_failed)
    val promptTitle = stringResource(R.string.lock_prompt_title)
    val promptSubtitle = stringResource(R.string.lock_prompt_subtitle)

    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    fun askFingerprint() {
        val cipher = vault.biometricUnlockCipher() ?: run {
            message = if (!vault.biometricEnabled) changedText else null
            return
        }
        AppLock.biometricPrompt(activity, promptTitle, promptSubtitle, cipher, onError = { message = it }) { unlocked ->
            if (vault.unlockWithBiometric(unlocked)) c.onUnlocked() else message = failedText
        }
    }
    LaunchedEffect(AppLock.lockCount) { if (bioAvailable) askFingerprint() }

    fun submit() {
        if (checking || lockout > 0 || pin.length < LockVault.PIN_MIN) return
        val entered = pin
        checking = true
        scope.launch {
            // Slow on purpose (it makes guessing slow too), so off the main thread.
            val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
            checking = false
            pin = ""
            if (result == LockVault.PinResult.Ok) {
                c.onUnlocked()
            } else {
                lockout = vault.lockoutRemaining()
                message = wrongText
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45))
            }
        }
    }

    PinPadScreen(
        title = stringResource(R.string.lock_title),
        message = when {
            lockout > 0 -> stringResource(R.string.pin_lockout, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            vault.biometricEnabled && vault.pinRequired() -> stringResource(R.string.pin_enter_restart)
            else -> stringResource(R.string.pin_enter)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = checking,
        enabled = lockout == 0L && !checking,
        onDigit = { d ->
            if (pin.length < LockVault.PIN_MAX) {
                message = null
                pin += d
                if (pin.length == vault.pinLength) submit()
            }
        },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::submit,
        showConfirm = pin.length >= LockVault.PIN_MIN && pin.length != vault.pinLength,
        bottom = {
            if (bioAvailable) {
                TextButton(onClick = ::askFingerprint) {
                    Icon(Icons.Outlined.Fingerprint, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.pin_use_fingerprint))
                }
            }
            TextButton(onClick = { forgetting = true }) { Text(stringResource(R.string.pin_forgot)) }
        },
    )
    if (forgetting) {
        ConfirmDialog(
            title = stringResource(R.string.pin_forgot_title),
            body = stringResource(R.string.pin_forgot_body),
            confirmLabel = stringResource(R.string.pin_forgot_confirm),
            onDismiss = { forgetting = false },
            onConfirm = {
                forgetting = false
                scope.launch { c.forgetEverything() }
            },
        )
    }
}

/** "Confirm changes": the fingerprint or the PIN before something changes on a server. */
@Composable
private fun ConfirmChangeScreen(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    val c = LocalContext.current.container
    val vault = c.vault
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }
    val bioAvailable = remember { vault.biometricEnabled && AppLock.biometricAvailable(activity) && !vault.pinRequired() }
    val wrongText = stringResource(R.string.pin_wrong)
    val failedText = stringResource(R.string.pin_fingerprint_failed)
    val promptTitle = stringResource(R.string.lock_confirm_title)
    val promptSubtitle = stringResource(R.string.lock_confirm_subtitle)

    BackHandler { onResult(false) }
    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    fun askFingerprint() {
        val cipher = vault.biometricUnlockCipher() ?: return
        AppLock.biometricPrompt(activity, promptTitle, promptSubtitle, cipher, onError = { message = it }) { unlocked ->
            if (vault.unlockWithBiometric(unlocked)) onResult(true) else message = failedText
        }
    }
    LaunchedEffect(Unit) { if (bioAvailable) askFingerprint() }

    fun submit() {
        if (checking || lockout > 0 || pin.length < LockVault.PIN_MIN) return
        val entered = pin
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
            checking = false
            pin = ""
            if (result == LockVault.PinResult.Ok) {
                onResult(true)
            } else {
                lockout = vault.lockoutRemaining()
                message = wrongText
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45))
            }
        }
    }

    PinPadScreen(
        title = stringResource(R.string.lock_confirm_title),
        message = when {
            lockout > 0 -> stringResource(R.string.pin_lockout, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            else -> stringResource(R.string.pin_confirm_body)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = checking,
        enabled = lockout == 0L && !checking,
        onDigit = { d ->
            if (pin.length < LockVault.PIN_MAX) {
                message = null
                pin += d
                if (pin.length == vault.pinLength) submit()
            }
        },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::submit,
        showConfirm = pin.length >= LockVault.PIN_MIN && pin.length != vault.pinLength,
        bottom = {
            if (bioAvailable) {
                TextButton(onClick = ::askFingerprint) {
                    Icon(Icons.Outlined.Fingerprint, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.pin_use_fingerprint))
                }
            }
            TextButton(onClick = { onResult(false) }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Choosing a PIN (twice), changing it (current, then new twice) or confirming it to turn the lock off. */
@Composable
private fun PinSetupScreen(
    activity: FragmentActivity,
    title: String,
    intro: String,
    verifyCurrent: Boolean,
    cancellable: Boolean,
    onDone: suspend (String) -> Unit,
    onCancel: () -> Unit,
    verifyOnly: Boolean = false,
) {
    val vault = LocalContext.current.container.vault
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    // 0 = current PIN, 1 = new PIN, 2 = repeat new PIN
    var step by remember { mutableIntStateOf(if (verifyCurrent) 0 else 1) }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }
    val wrongText = stringResource(R.string.pin_wrong)
    val easyText = stringResource(R.string.pin_too_easy)
    val mismatchText = stringResource(R.string.pin_mismatch)
    val failedText = stringResource(R.string.pin_save_failed)

    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    BackHandler { if (cancellable) onCancel() else activity.moveTaskToBack(true) }

    fun wrong(text: String) {
        message = text
        pin = ""
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45)) }
    }

    fun finish(newPin: String) {
        busy = true
        scope.launch {
            runCatching { onDone(newPin) }.onFailure {
                busy = false
                wrong(failedText)
            }
        }
    }

    fun next() {
        if (busy || lockout > 0) return
        when (step) {
            0 -> {
                if (pin.length < LockVault.PIN_MIN) return
                val entered = pin
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
                    busy = false
                    if (result == LockVault.PinResult.Ok) {
                        pin = ""
                        message = null
                        if (verifyOnly) finish(entered) else step = 1
                    } else {
                        lockout = vault.lockoutRemaining()
                        wrong(wrongText)
                    }
                }
            }
            1 -> {
                if (pin.length < LockVault.PIN_MIN) return
                if (pin.toSet().size == 1 || "0123456789012".contains(pin) || "9876543210987".contains(pin)) {
                    wrong(easyText)
                    return
                }
                first = pin
                pin = ""
                message = null
                step = 2
            }
            else -> {
                if (pin != first) {
                    first = ""
                    step = 1
                    wrong(mismatchText)
                    return
                }
                finish(pin)
            }
        }
    }

    PinPadScreen(
        title = title,
        message = when {
            lockout > 0 -> stringResource(R.string.pin_lockout, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            step == 0 -> stringResource(R.string.pin_current)
            step == 1 -> intro
            else -> stringResource(R.string.pin_repeat)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = busy,
        enabled = !busy && lockout == 0L,
        onDigit = { d -> if (pin.length < LockVault.PIN_MAX) { message = null; pin += d } },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::next,
        showConfirm = pin.length >= LockVault.PIN_MIN,
        confirmLabel = stringResource(if (step == 2 || (step == 0 && verifyOnly)) R.string.pin_done else R.string.pin_next),
        bottom = { if (cancellable) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PinPadScreen(
    title: String,
    message: String,
    isError: Boolean,
    pin: String,
    shake: Float,
    checking: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    showConfirm: Boolean,
    confirmLabel: String = stringResource(R.string.pin_ok),
    bottom: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LockBadge()
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) cs.error else cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
            Spacer(Modifier.height(24.dp))
            Box(Modifier.height(20.dp).offset { IntOffset(shake.roundToInt(), 0) }, contentAlignment = Alignment.Center) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(maxOf(pin.length, LockVault.PIN_MIN)) { i ->
                            Box(Modifier.size(12.dp).clip(CircleShape).background(if (i < pin.length) cs.primary else cs.surfaceContainerHighest))
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf("123", "456", "789").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { d ->
                            DigitKey(d.toString(), enabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDigit(d)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBackspace, enabled = enabled && pin.isNotEmpty(), modifier = Modifier.size(72.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.pin_delete))
                    }
                    DigitKey("0", enabled) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigit('0')
                    }
                    TextButton(onClick = onConfirm, enabled = enabled && showConfirm, modifier = Modifier.size(72.dp)) { Text(confirmLabel) }
                }
            }
            Spacer(Modifier.height(16.dp))
            bottom()
        }
    }
}

@Composable
private fun DigitKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.size(72.dp),
    ) { Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium) }
}
