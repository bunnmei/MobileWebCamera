package space.webkombinat.MobileWebCamera.ui.components

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.Surface

/**
 * Camera2 のプレビュー先 SurfaceView。
 * バッファサイズは [bufferSize]（センサー向き）に固定し、表示の回転は SurfaceView/カメラ側に任せる。
 */
@Composable
fun CameraPreview(
    bufferSize: Size?,
    onSurfaceReady: (Surface, Size) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready by rememberUpdatedState(onSurfaceReady)
    val destroyed by rememberUpdatedState(onSurfaceDestroyed)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        ready(holder.surface, Size(width, height))
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        destroyed()
                    }
                })
            }
        },
        update = { view ->
            if (bufferSize != null && view.tag != bufferSize) {
                view.tag = bufferSize
                view.holder.setFixedSize(bufferSize.width, bufferSize.height)
            }
        },
    )
}
