package com.roadeye.app.camera

import android.graphics.RectF

/**
 * Один результат детекции YOLO — уже после NMS, в координатах
 * ИСХОДНОГО кадра камеры (не 416x416 входа модели).
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val box: RectF
)