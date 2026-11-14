package com.icecreampost.pos.ui

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icecreampost.pos.data.repository.PosRelease
import com.icecreampost.pos.data.repository.PosUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PosUpdateState(
    val release: PosRelease? = null,
    val apk: File? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val upToDate: Boolean = false,
    val error: String? = null,
) {
    val busy get() = checking || downloading
}

@HiltViewModel
class PosUpdateViewModel @Inject constructor(private val updates: PosUpdateRepository) : ViewModel() {
    val isConfigured = updates.isConfigured
    private val _state = MutableStateFlow(PosUpdateState())
    val state = _state.asStateFlow()
    private var lastCheckAt: Long? = null

    fun check(automatic: Boolean = false) {
        val previous = _state.value
        if (!isConfigured || previous.busy) return
        val now = SystemClock.elapsedRealtime()
        if (automatic && lastCheckAt?.let { now - it < 60 * 60 * 1000L } == true) return
        lastCheckAt = now
        _state.value = previous.copy(checking = true, error = null)
        viewModelScope.launch {
            try {
                val release = updates.latestRelease()
                _state.value = PosUpdateState(release = release, upToDate = release == null,
                    apk = previous.apk?.takeIf { release == previous.release && it.isFile })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = previous.copy(error = if (error is IllegalStateException) error.message
                    else "Could not check for updates. Check your internet connection and try again.")
            }
        }
    }

    fun download() {
        val previous = _state.value
        val release = previous.release ?: return
        if (previous.busy) return
        _state.value = previous.copy(apk = null, downloading = true, error = null)
        viewModelScope.launch {
            try {
                _state.value = previous.copy(apk = updates.download(release), error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = previous.copy(apk = null, error = if (error is IllegalStateException) error.message
                    else "Could not download the update. Try again when connected.")
            }
        }
    }

    fun installerIntent(): Intent? {
        val current = _state.value
        val apk = current.apk ?: return null
        if (!apk.isFile) {
            _state.value = current.copy(apk = null, error = "Download the update again to continue.")
            return null
        }
        return updates.installerIntent(apk)
    }

    fun showInstallerError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}
