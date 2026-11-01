package com.family.tvguard.util

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.family.tvguard.TvGuardApp

/**
 * Credential-encrypted prefs are unavailable until the user is unlocked.
 * Hisense TVs usually have no PIN, but LOCKED_BOOT_COMPLETED still starts
 * directBootAware receivers in that window. Fail open if the check itself throws.
 */
object UserUnlock {
    fun isUnlocked(context: Context): Boolean {
        return try {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            um.isUserUnlocked
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "isUserUnlocked failed, assuming unlocked: ${e.message}")
            true
        }
    }
}
