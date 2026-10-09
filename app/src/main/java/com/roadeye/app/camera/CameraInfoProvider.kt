package com.roadeye.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager


class CameraInfoProvider(
    private val context: Context
) {


    private val cameraManager =
        context.getSystemService(
            Context.CAMERA_SERVICE
        ) as CameraManager



    fun getBackCameraId(): String? {

        for (id in cameraManager.cameraIdList) {

            val characteristics =
                cameraManager.getCameraCharacteristics(id)


            val facing =
                characteristics.get(
                    CameraCharacteristics.LENS_FACING
                )


            if (facing ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {

                return id
            }
        }

        return null
    }




    fun getCharacteristics():
            CameraCharacteristics? {

        val id =
            getBackCameraId()
                ?: return null


        return cameraManager
            .getCameraCharacteristics(id)
    }




    fun getCameraReport(): String {


        val c =
            getCharacteristics()
                ?: return "Camera not found"



        val focusModes =
            c.get(
                CameraCharacteristics
                    .CONTROL_AF_AVAILABLE_MODES
            )


        val minFocus =
            c.get(
                CameraCharacteristics
                    .LENS_INFO_MINIMUM_FOCUS_DISTANCE
            )


        val sensor =
            c.get(
                CameraCharacteristics
                    .SENSOR_INFO_PIXEL_ARRAY_SIZE
            )


        val level =
            c.get(
                CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL
            )


        return """
Camera diagnostics

Hardware level:
$level

Sensor:
${sensor?.width} x ${sensor?.height}

Min focus distance:
$minFocus

Infinity focus:
0.0

AF modes:
${focusModes?.contentToString()}

        """.trimIndent()
    }
}