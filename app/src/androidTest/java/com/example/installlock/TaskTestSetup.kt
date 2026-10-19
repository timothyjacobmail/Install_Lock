package com.example.installlock

import android.content.Context
import org.junit.Assert.assertNull

/** Deliberately shared credentials for older single-feature regression cases, bypassing only the setup wizard. */
internal fun installTaskTestCredentials(c: Context, password: String, vararg kinds: TaskKind) {
    assertNull(TaskPasswords.vault(c).install(kinds.map { NewTaskPassword(TaskScope(it), password) }, share = true))
}
