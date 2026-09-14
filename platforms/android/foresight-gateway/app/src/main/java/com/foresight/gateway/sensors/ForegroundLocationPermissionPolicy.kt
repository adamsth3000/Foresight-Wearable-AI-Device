package com.foresight.gateway.sensors

enum class ForegroundLocationPermission { PRECISE, APPROXIMATE, DENIED }

object ForegroundLocationPermissionPolicy {
    fun resolve(fineGranted: Boolean, coarseGranted: Boolean): ForegroundLocationPermission = when {
        fineGranted -> ForegroundLocationPermission.PRECISE
        coarseGranted -> ForegroundLocationPermission.APPROXIMATE
        else -> ForegroundLocationPermission.DENIED
    }
}
