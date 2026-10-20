package app.stereoplay

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Surface

@SuppressLint("ViewConstructor")
class StereoView(context: Context, engine: DepthEngine, onSurface: (Surface) -> Unit) : GLSurfaceView(context) {
    val renderer: StereoRenderer

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        renderer = StereoRenderer(
            engine,
            onSurface = { surface -> post { onSurface(surface) } },
            requestRender = { requestRender() },
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
