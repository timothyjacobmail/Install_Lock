package com.example.delayedsecrect

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Compatibility target for notifications already posted by the previous version. No separate UI. */
class InstallLockRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requests = InstallLockRequests(this)
        val id = intent.getStringExtra("request_id").orEmpty()
        try {
            if (intent.action == InstallLockRequests.REQUEST) requests.receive(id,
                intent.getStringExtra("scope").orEmpty(), intent.getStringExtra("task_title").orEmpty())
            startActivity(requests.openIntent(this, id))
        } finally {
            finish()
        }
    }
}
