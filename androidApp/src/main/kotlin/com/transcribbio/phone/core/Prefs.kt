package com.transcribbio.phone.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Lightweight persisted settings + pairing state (SharedPreferences backed). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("transcribbio", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().substring(0, 8).also {
        sp.edit().putString("deviceId", it).apply()
    }

    private val _language = MutableStateFlow(sp.getString("language", "sk") ?: "sk")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _token = MutableStateFlow(sp.getString("token", "") ?: "")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _desktopName = MutableStateFlow(sp.getString("desktopName", "") ?: "")
    val desktopName: StateFlow<String> = _desktopName.asStateFlow()

    private val _desktopDeviceId = MutableStateFlow(sp.getString("desktopDeviceId", "") ?: "")
    val desktopDeviceId: StateFlow<String> = _desktopDeviceId.asStateFlow()

    val isPaired: Boolean get() = _token.value.isNotBlank()

    fun setLanguage(value: String) {
        sp.edit().putString("language", value).apply()
        _language.value = value
    }

    fun setPairing(token: String, desktopName: String, desktopDeviceId: String) {
        sp.edit()
            .putString("token", token)
            .putString("desktopName", desktopName)
            .putString("desktopDeviceId", desktopDeviceId)
            .apply()
        _token.value = token
        _desktopName.value = desktopName
        _desktopDeviceId.value = desktopDeviceId
    }

    fun clearPairing() {
        sp.edit().remove("token").remove("desktopName").remove("desktopDeviceId").apply()
        _token.value = ""
        _desktopName.value = ""
        _desktopDeviceId.value = ""
    }
}
