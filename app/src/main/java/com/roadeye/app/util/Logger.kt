package com.roadeye.app.util

import android.util.Log


object Logger {

    private const val TAG = "RoadEyeCamera"


    fun camera(message: String) {

        Log.d(
            TAG,
            message
        )

    }


    fun error(message: String) {

        Log.e(
            TAG,
            message
        )

    }

}