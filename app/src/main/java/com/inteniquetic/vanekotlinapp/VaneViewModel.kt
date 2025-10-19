package com.inteniquetic.vanekotlinapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteniquetic.vanekotlin.VaneConfigurationBuilder
import com.inteniquetic.vanekotlin.VaneSession
import kotlinx.coroutines.launch

class VaneViewModel : ViewModel() {
    val config = VaneConfigurationBuilder()
        .baseUrl("http://127.0.0.1:8000")
        .defaultHeaders(mapOf("Authorization" to "Bearer token"))
        .timeout(30u)
        .build()
    private val session = VaneSession(config)

    fun get() = viewModelScope.launch {
        val response = session.request("/get")
            .header("Accept", "application/json")
            .queryParam("page", "1")
            .responseString()
        Log.i("VaneViewModel", "Response: $response")
    }
}