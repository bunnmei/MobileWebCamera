package space.webkombinat.MobileWebCamera.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import space.webkombinat.MobileWebCamera.camera.CameraController
import space.webkombinat.MobileWebCamera.camera.CameraUiState
import space.webkombinat.MobileWebCamera.stream.StreamManager
import space.webkombinat.MobileWebCamera.stream.StreamStatus
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val camera: CameraController,
    private val streams: StreamManager,
) : ViewModel() {

    val state: StateFlow<CameraUiState> = camera.state
    val streamStatus: StateFlow<StreamStatus> = streams.status

    fun onPermissionGranted() = camera.onPermissionGranted()

    fun toggleStreaming() {
        if (state.value.streaming) camera.stopStreaming() else camera.startStreaming()
    }

    fun refreshAddresses() = streams.refreshAddresses()
}
