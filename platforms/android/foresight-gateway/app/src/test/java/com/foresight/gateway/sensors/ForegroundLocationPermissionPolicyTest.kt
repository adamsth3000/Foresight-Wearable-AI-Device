package com.foresight.gateway.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundLocationPermissionPolicyTest {
    @Test fun `fine permission is precise`() {
        assertEquals(ForegroundLocationPermission.PRECISE, ForegroundLocationPermissionPolicy.resolve(true, true))
    }

    @Test fun `coarse permission is approximate`() {
        assertEquals(ForegroundLocationPermission.APPROXIMATE, ForegroundLocationPermissionPolicy.resolve(false, true))
    }

    @Test fun `no location permission is denied`() {
        assertEquals(ForegroundLocationPermission.DENIED, ForegroundLocationPermissionPolicy.resolve(false, false))
    }
}
