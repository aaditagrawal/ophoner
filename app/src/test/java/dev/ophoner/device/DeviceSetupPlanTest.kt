package dev.ophoner.device

import org.junit.Assert.*
import org.junit.Test

class DeviceSetupPlanTest {
    @Test fun `already granted steps and declined steps are skipped`() {
        assertEquals(DeviceSetupStep.NOTIFICATIONS, nextSetupStep(DeviceSetupStep.entries,
            setOf(DeviceSetupStep.ACCESSIBILITY), setOf(DeviceSetupStep.PERMISSIONS)))
    }
    @Test fun `all resolved steps finish without claiming all granted`() {
        assertNull(nextSetupStep(DeviceSetupStep.entries, DeviceSetupStep.entries.drop(1).toSet(), setOf(DeviceSetupStep.PERMISSIONS)))
    }
    @Test fun `review retries deferred steps`() {
        assertEquals(DeviceSetupStep.PERMISSIONS, nextSetupStep(DeviceSetupStep.entries, emptySet(), emptySet()))
    }
    @Test fun `running via USB does not require wireless debugging`() {
        assertEquals(ShizukuSetupStage.AUTHORIZE, shizukuSetupStage(true, true, false, true, false, false))
        assertEquals(ShizukuSetupStage.READY, shizukuSetupStage(true, true, true, true, false, false))
    }
    @Test fun `setup follows install developer options wireless start`() {
        assertEquals(ShizukuSetupStage.INSTALL, shizukuSetupStage(false, false, false, true, false, false))
        assertEquals(ShizukuSetupStage.DEVELOPER_OPTIONS, shizukuSetupStage(true, false, false, true, false, false))
        assertEquals(ShizukuSetupStage.WIRELESS_DEBUGGING, shizukuSetupStage(true, false, false, true, true, false))
        assertEquals(ShizukuSetupStage.START, shizukuSetupStage(true, false, false, true, true, true))
    }
    @Test fun `older Android goes directly to computer instructions`() {
        assertEquals(ShizukuSetupStage.START, shizukuSetupStage(true, false, false, false, false, false))
    }
}
