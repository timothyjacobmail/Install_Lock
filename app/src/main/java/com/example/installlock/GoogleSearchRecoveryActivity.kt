package com.example.installlock

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/** A place to edit the next search without reopening the still-blocked Google results. */
class GoogleSearchRecoveryActivity : Activity() {
    companion object {
        private const val BLOCKED_WORD = "blocked_word"
        private const val ORIGINAL_QUERY = "original_query"

        internal fun openIntent(context: Context, word: String? = null, query: String? = null): Intent =
            Intent(context, GoogleSearchRecoveryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BLOCKED_WORD, word)
                .putExtra(ORIGINAL_QUERY, query)

        internal fun searchIntent(query: String): Intent = Intent(Intent.ACTION_WEB_SEARCH)
            .setPackage(BrowserPackages.GOOGLE_APP)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(SearchManager.EXTRA_NEW_SEARCH, true)
            // A new query must replace the blocked results, rather than resume their task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { goHome() }
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val word = intent.getStringExtra(BLOCKED_WORD)
        val query = EditText(this).apply {
            hint = "Search for something else"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            isSaveEnabled = false
            setText(intent.getStringExtra(ORIGINAL_QUERY).orEmpty())
            setSelection(text.length)
        }
        val message = TextView(this)
        fun search(excludeTrigger: Boolean = false) {
            val entered = query.text.toString().trim()
            val text = if (excludeTrigger && word != null) GoogleSearchQuery.excluding(entered, word).orEmpty() else entered
            if (text.isEmpty()) {
                message.text = "Enter a new search."
                return
            }
            if (excludeTrigger) {
                query.setText(text)
                query.setSelection(text.length)
            }
            if (!DnsFilterService.isReady(this)) {
                message.text = "Google requires Install Lock's VPN. Turn on Google VPN filter in Install Lock, then retry your search."
                return
            }
            val words = if (Store.browserKeywordFilterEnabled(this)) Store.browserKeywords(this) else emptySet()
            val match = GoogleSearchQuery.blockedMatch(text, words,
                Store.keywordExceptions(this), Store.genericKeywordExceptions(this))
            if (match != null) {
                message.text = "That search includes the blocked word \"$match\". Try a different search."
                return
            }
            try {
                startActivity(searchIntent(text))
                BrowserKeywordService.rememberGoogleSearch(text)
                finish()
            } catch (_: ActivityNotFoundException) {
                message.text = "The Google app could not open a new search. Check that it is installed and enabled."
            } catch (_: SecurityException) {
                message.text = "Android did not allow Google to open. Check Why apps are locked in Install Lock."
            }
        }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@GoogleSearchRecoveryActivity).apply {
                text = if (word == null) "New Google search" else "Google result blocked"
                textSize = 24f
            })
            addView(TextView(this@GoogleSearchRecoveryActivity).apply {
                text = if (word == null) "Enter a new Google search. Your current app locks and filter settings still apply."
                    else "The results contain the blocked word \"$word\". " +
                            "Edit your search below, or retry it with that word excluded. " +
                            "If your original query is missing, enter it below first. Results are still checked for blocked words."
                textSize = 16f
            })
            addView(query)
            addView(message)
            if (word != null) addView(Button(this@GoogleSearchRecoveryActivity).apply {
                text = "Search without \"$word\""
                setOnClickListener { search(excludeTrigger = true) }
            })
            addView(Button(this@GoogleSearchRecoveryActivity).apply {
                text = "Search Google"
                setOnClickListener { search() }
            })
            addView(Button(this@GoogleSearchRecoveryActivity).apply {
                text = "Turn on Google VPN"
                setOnClickListener { message.text = Policy.enableGoogleFilter(this@GoogleSearchRecoveryActivity) }
            })
            addView(Button(this@GoogleSearchRecoveryActivity).apply {
                text = "Home"
                setOnClickListener { goHome() }
            })
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Suppress("OVERRIDE_DEPRECATION", "GestureBackNavigation")
    override fun onBackPressed() { goHome() }
}
