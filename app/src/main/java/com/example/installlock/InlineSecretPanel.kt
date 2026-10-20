package com.example.installlock

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.delayedsecrect.InlineSecretChoice
import com.example.delayedsecrect.InlineSecretClaim
import com.example.delayedsecrect.InlineSecrets
import com.example.delayedsecrect.RevealState
import com.example.delayedsecrect.CLAIM_WINDOW_MS
import com.example.delayedsecrect.copySecretToClipboard
import com.example.delayedsecrect.clearClipboardIfMatches

/** Keeps the originating form alive. No secret or claim is saved in view/activity state. */
internal class InlineSecretPanel(
    private val host: MainActivity,
    private val task: () -> DelayedTask?,
    private val unlock: (String, InlineSecretClaim, (Boolean) -> Unit) -> Unit
) : LinearLayout(host), DefaultLifecycleObserver {
    var requestId: String? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val content = LinearLayout(host).apply { orientation = VERTICAL; visibility = GONE }
    private val status = UiStyle.label(host, "")
    private val taskTitle = UiStyle.label(host, "")
    private val message = UiStyle.label(host, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val shownPassword = UiStyle.label(host, "").apply { isSaveEnabled = false }
    private val search = EditText(host).apply { hint = "Find a secret"; isSaveEnabled = false }
    private val picker = Spinner(host)
    private var vault: InlineSecrets? = null
    private var choices = emptyList<InlineSecretChoice>()
    private var displayedChoices = emptyList<InlineSecretChoice>()
    private var scope = ""
    private var expanded = false
    private var busy = false
    private var foreground = false
    private var securedWindow = false
    private val use = UiStyle.button(host, "Start waiting / use selected secret") { select() }
    private val notifications = UiStyle.button(host, "Enable ready notifications") {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            androidx.core.app.ActivityCompat.requestPermissions(host, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 711)
    }
    private val unlockButton = UiStyle.button(host, "Unlock", primary = true) { performUnlock() }
    private val copy = UiStyle.button(host, "Copy password") { claim()?.let { revealed ->
        copySecretToClipboard(host, revealed.value)
        message.text = "Password copied. Clipboard clears in 5 seconds."
        handler.postDelayed({ clearClipboardIfMatches(host.applicationContext, revealed.value) },
            minOf(5000L, (revealed.expiresElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
        refresh()
    } }
    private val show: com.google.android.material.button.MaterialButton = UiStyle.button(host, "Show password") {
        if (shownPassword.text.isNotEmpty()) hidePassword()
        else claim()?.let { revealed ->
            val alreadySecure = host.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            if (!alreadySecure) { host.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); securedWindow = true }
            shownPassword.text = revealed.value
            show.text = "Hide password"
        }
        refresh()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!expanded || !foreground || !isAttachedToWindow) return
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        addView(UiStyle.button(host, "Use delayed secrets") { if (expanded) collapse() else open() })
        addView(content)
        content.addView(UiStyle.label(host, "Choose the secret for this exact task. Its existing wait, hard lock and expiry still apply."))
        content.addView(taskTitle)
        content.addView(search)
        content.addView(picker)
        content.addView(use)
        content.addView(status)
        content.addView(message)
        content.addView(unlockButton)
        content.addView(copy)
        content.addView(show)
        content.addView(shownPassword)
        content.addView(notifications)
        content.addView(UiStyle.button(host, "Cancel request") {
            requestId?.let { id ->
                try { vault?.cancel(id); DelayedSecrets.take(host, id) }
                catch (_: Exception) { message.text = "Could not cancel. Please try again."; return@button }
            }
            requestId = null
            collapse()
        })
        content.addView(UiStyle.button(host, "Manage / import secrets") {
            host.startActivity(android.content.Intent(host, com.example.delayedsecrect.MainActivity::class.java))
        })
        search.doAfterTextChanged { filterChoices() }
    }

    fun open(savedTask: DelayedTask? = null) {
        try {
            val snapshot = savedTask ?: task() ?: return
            val controller = vault ?: InlineSecrets(host).also { vault = it }
            val id = DelayedSecrets.prepare(host, snapshot)
            controller.prepare(id, snapshot.scope, snapshot.title)
            if (requestId != id) hidePassword()
            requestId = id
            taskTitle.text = snapshot.title
            scope = snapshot.scope
            choices = controller.choices(scope)
            filterChoices()
            expanded = true
            content.visibility = VISIBLE
            handler.removeCallbacks(tick)
            refresh()
            if (foreground) handler.post(tick)
            post { requestRectangleOnScreen(android.graphics.Rect(0, 0, width, minOf(height, host.window.decorView.height / 2)), false) }
        } catch (_: Exception) {
            expanded = true
            content.visibility = VISIBLE
            message.text = "Could not load delayed secrets. Please try again."
        }
    }

    private fun filterChoices() {
        val selectedId = displayedChoices.getOrNull(picker.selectedItemPosition)?.id
        val visible = visibleChoices()
        displayedChoices = visible
        picker.adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_dropdown_item,
            visible.map { "${it.name} (${it.delayMinutes} min)" }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    (this as? android.widget.TextView)?.apply { setSingleLine(false); maxLines = Int.MAX_VALUE }
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).apply {
                    (this as? android.widget.TextView)?.apply { setSingleLine(false); maxLines = Int.MAX_VALUE }
                }
        }
        visible.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { picker.setSelection(it) }
        use.isEnabled = visible.isNotEmpty() && !busy
    }
    private fun visibleChoices() = choices.filter { it.name.contains(search.text.toString(), ignoreCase = true) }
    private fun select() {
        val id = requestId ?: return
        val secret = displayedChoices.getOrNull(picker.selectedItemPosition) ?: return
        hidePassword()
        message.text = try {
            if (vault?.select(id, secret.id) == true) "" else "Secret unavailable or hard locked. Choose another or wait for the hard lock to end."
        } catch (_: Exception) { "Could not start the request. Please try again." }
        refresh()
    }
    private fun claim(): InlineSecretClaim? {
        val claimed = try { requestId?.let { vault?.claim(it) } } catch (_: Exception) { null }
        if (claimed == null) { hidePassword(); message.text = "This password is not ready or has expired." }
        return claimed
    }
    private fun performUnlock() {
        if (busy) return
        val id = requestId ?: return
        val claimed = claim() ?: return
        hidePassword()
        busy = true
        refresh()
        unlock(id, claimed) { success ->
            busy = false
            message.text = if (success) "Unlocked the requested task." else "Could not unlock. Check the secret matches this task and request it again."
            refresh()
        }
    }
    private fun refresh() {
        val state = try { requestId?.let { vault?.status(it) } } catch (_: Exception) { null }
        val info = state?.info
        val now = SystemClock.elapsedRealtime()
        val ready = info?.state == RevealState.REVEALED
        unlockButton.isEnabled = ready && !busy
        copy.isEnabled = ready && !busy
        show.isEnabled = ready && !busy
        use.isEnabled = displayedChoices.isNotEmpty() && !busy
        notifications.visibility = if (android.os.Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) VISIBLE else GONE
        if (!ready) hidePassword()
        status.text = when {
            choices.isEmpty() -> "No secrets found. Use Manage / import secrets to add your existing list."
            state == null -> "Choose a secret to start waiting. Expired or used passwords require a new wait."
            info?.state == RevealState.COUNTING_DOWN -> "${state.name}\nReady in ${duration(info.revealAtElapsedMs - now)}. You can stay on this screen."
            ready -> "${state.name}\nPassword ready. Unlock? Expires in ${duration((if (info.hideAtElapsedMs > 0) info.hideAtElapsedMs else info.revealAtElapsedMs + CLAIM_WINDOW_MS) - now)}."
            else -> "Secret unavailable."
        }
    }
    private fun duration(ms: Long): String {
        val seconds = ((ms + 999) / 1000).coerceAtLeast(0)
        return "${seconds / 60}m ${seconds % 60}s"
    }
    private fun hidePassword() {
        shownPassword.text = ""
        show.text = "Show password"
        if (securedWindow) { host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); securedWindow = false }
    }
    private fun collapse() { expanded = false; content.visibility = GONE; hidePassword(); handler.removeCallbacks(tick) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); host.lifecycle.addObserver(this) }
    override fun onDetachedFromWindow() {
        host.lifecycle.removeObserver(this)
        handler.removeCallbacks(tick)
        hidePassword()
        super.onDetachedFromWindow()
    }
    override fun onStart(owner: LifecycleOwner) { foreground = true; if (expanded) { handler.removeCallbacks(tick); handler.post(tick) } }
    override fun onStop(owner: LifecycleOwner) { foreground = false; handler.removeCallbacks(tick); hidePassword() }
}
