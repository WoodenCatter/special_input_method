package com.example.input_ds.personalization

import android.content.Context

/** Keeps the offline local identity linked to exactly one server profile for this device. */
class ProfileSyncManager(context: Context) {
    private val repository = PersonalizationRepository(context.applicationContext)

    fun synchronize(localUserId: String): LocalUser {
        val localUser = requireNotNull(repository.findUser(localUserId)) { "Local user does not exist" }
        val binding = requireNotNull(repository.deviceServerBinding()) { "Device server account is not bound" }
        return try {
            val profile = BrainApiClient(binding.serverUserId, binding.apiKey).syncProfile(
                clientProfileId = localUser.clientProfileId,
                displayName = localUser.displayName
            )
            require(profile.clientProfileId == localUser.clientProfileId) {
                "Server returned a mismatched client_profile_id"
            }
            repository.saveRemoteProfile(
                localUserId = localUserId,
                profileId = profile.profileId,
                enabled = profile.enabled,
                displayName = profile.displayName
            )
        } catch (error: BrainApiClient.ApiException) {
            repository.saveProfileSyncError(
                localUserId,
                error.message ?: "Profile sync failed",
                disabled = error.errorCode == PROFILE_DISABLED
            )
            throw error
        } catch (error: Throwable) {
            repository.saveProfileSyncError(localUserId, error.message ?: "Profile sync failed")
            throw error
        }
    }

    fun listUnassociatedLegacyProfiles(): List<BrainApiClient.ServerProfile> {
        val binding = requireNotNull(repository.deviceServerBinding()) { "Device server account is not bound" }
        return BrainApiClient(binding.serverUserId, binding.apiKey).listProfiles()
            .filter { it.isLegacy && it.clientProfileId == null }
    }

    fun associateLegacyProfile(localUserId: String, profileId: String): LocalUser {
        val localUser = requireNotNull(repository.findUser(localUserId)) { "Local user does not exist" }
        require(localUser.profileId == null) { "This local user already has a server profile" }
        val binding = requireNotNull(repository.deviceServerBinding()) { "Device server account is not bound" }
        return try {
            val profile = BrainApiClient(binding.serverUserId, binding.apiKey).associateLegacyProfile(
                profileId = profileId,
                clientProfileId = localUser.clientProfileId,
                displayName = localUser.displayName
            )
            repository.saveRemoteProfile(
                localUserId = localUserId,
                profileId = profile.profileId,
                enabled = profile.enabled,
                displayName = profile.displayName
            )
        } catch (error: Throwable) {
            repository.saveProfileSyncError(localUserId, error.message ?: "Historical profile association failed")
            throw error
        }
    }

    companion object {
        const val PROFILE_DISABLED = "PROFILE_DISABLED"
    }
}
