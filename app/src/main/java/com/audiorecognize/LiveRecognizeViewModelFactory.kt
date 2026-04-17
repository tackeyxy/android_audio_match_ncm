package com.audiorecognize

import android.app.Application
import androidx.lifecycle.ViewModel

class LiveRecognizeViewModelFactory(private val application: Application) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LiveRecognizeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LiveRecognizeViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}