package com.example.input_ds.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileRecoveryTest {
    @Test
    fun emptyInstallRestoresOriginalClientIdentity() {
        val (users, restored) = mergeRecoveredProfile(
            users = emptyList(),
            profileId = "profile-1",
            clientProfileId = "client-1",
            displayName = "测试用户",
            enabled = true
        )

        assertEquals(1, users.size)
        assertEquals("client-1", restored.localId)
        assertEquals("client-1", restored.clientProfileId)
        assertEquals("profile-1", restored.profileId)
    }

    @Test
    fun repeatedRestoreUpdatesInsteadOfCreatingDuplicate() {
        val local = LocalUser(
            localId = "client-1",
            displayName = "旧名称",
            clientProfileId = "client-1",
            profileId = "profile-1",
            profileSyncError = "旧错误",
            serverUserId = "legacy-binding"
        )
        val (users, restored) = mergeRecoveredProfile(
            users = listOf(local),
            profileId = "profile-1",
            clientProfileId = "client-1",
            displayName = "服务器名称",
            enabled = true
        )

        assertEquals(1, users.size)
        assertEquals("服务器名称", restored.displayName)
        assertNull(restored.profileSyncError)
        assertNull(restored.serverUserId)
    }

    @Test
    fun oldPreprocessingContractIsQueuedForRetraining() {
        assertTrue(
            requiresUnifiedPreprocessingRetraining(
                IllegalArgumentException("ONNX 预处理契约校验失败")
            )
        )
        assertTrue(
            requiresUnifiedPreprocessingRetraining(
                IllegalArgumentException("missing inference_preprocessing metadata")
            )
        )
        assertFalse(
            requiresUnifiedPreprocessingRetraining(
                IllegalArgumentException("ONNX 输出类别数与 Session 不一致")
            )
        )
    }

    @Test
    fun historicalProfileGetsStableRecoveryIdentity() {
        assertEquals(
            legacyRecoveryClientId("legacy-default"),
            legacyRecoveryClientId("legacy-default")
        )
        assertFalse(
            legacyRecoveryClientId("legacy-default") == legacyRecoveryClientId("another-profile")
        )
    }

    @Test
    fun legacyZeroRoundIsOmittedFromCurrentUploadContract() {
        assertNull(markerRoundForUpload(0))
        assertNull(markerRoundForUpload(-1))
        assertEquals(1, markerRoundForUpload(1))
        assertEquals(20, markerRoundForUpload(20))
    }

    @Test
    fun onlyLegacyRoundValidationFailureIsAutomaticallyReopened() {
        assertTrue(
            isLegacyMarkerRoundValidationError(
                "[{\"loc\":[\"markers\",0,\"round\"]," +
                    "\"msg\":\"Input should be greater than or equal to 1\",\"input\":0}]"
            )
        )
        assertFalse(isLegacyMarkerRoundValidationError("HTTP 422: label_names invalid"))
        assertFalse(isLegacyMarkerRoundValidationError("网络暂不可用"))
    }
}
