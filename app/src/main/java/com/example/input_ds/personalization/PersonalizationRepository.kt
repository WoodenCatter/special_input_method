package com.example.input_ds.personalization

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class PersonalizationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT_DIR).apply { mkdirs() }
    private val usersFile = File(root, "users.json")
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val credentials = SecureCredentialStore(appContext)

    /** One server account belongs to this device and is shared by all local collection users. */
    @Synchronized
    fun deviceServerBinding(): DeviceServerBinding? {
        credentials.loadDeviceBinding()?.let { return it }

        // One-time migration from releases that stored one binding per local user.
        val users = readUsers()
        val legacy = users.firstNotNullOfOrNull { user ->
            val serverUserId = user.serverUserId ?: return@firstNotNullOfOrNull null
            val apiKey = credentials.load(user.localId) ?: return@firstNotNullOfOrNull null
            DeviceServerBinding(serverUserId, apiKey)
        } ?: return null
        credentials.saveDeviceBinding(legacy.serverUserId, legacy.apiKey)
        clearLegacyServerBindings(users)
        return legacy
    }

    @Synchronized
    fun bindDeviceServer(serverUserId: String, apiKey: String) {
        val cleanServerId = serverUserId.trim()
        require(cleanServerId.isNotEmpty()) { "服务端用户 ID 不能为空" }
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val previousServerId = credentials.loadDeviceBinding()?.serverUserId
        credentials.saveDeviceBinding(cleanServerId, apiKey)
        clearLegacyServerBindings(readUsers())
        if (previousServerId != null && previousServerId != cleanServerId) {
            clearRemoteProfileBindings()
        }
    }

    @Synchronized
    fun unbindDeviceServer() {
        credentials.deleteDeviceBinding()
        clearLegacyServerBindings(readUsers())
    }

    private fun clearLegacyServerBindings(users: List<LocalUser>) {
        users.forEach { credentials.delete(it.localId) }
        if (users.any { it.serverUserId != null }) {
            writeUsers(users.map { it.copy(serverUserId = null) })
        }
    }

    @Synchronized
    fun listUsers(): List<LocalUser> = readUsers()

    fun findUser(localUserId: String): LocalUser? = listUsers().firstOrNull { it.localId == localUserId }

    /** Rebuilds the local identity index from a profile that already exists on the server. */
    @Synchronized
    fun restoreRemoteProfile(
        profileId: String,
        clientProfileId: String,
        displayName: String,
        enabled: Boolean
    ): LocalUser {
        requireProfileIdentifier(profileId)
        requireProfileIdentifier(clientProfileId)
        val cleanName = displayName.trim().ifEmpty { "服务器用户" }
        val (users, restored) = mergeRecoveredProfile(
            users = readUsers(),
            profileId = profileId,
            clientProfileId = clientProfileId,
            displayName = cleanName,
            enabled = enabled
        )
        writeUsers(users)
        userDirectory(restored.localId).mkdirs()
        if (activeUserId() == null) setActiveUser(restored.localId)
        return restored
    }

    @Synchronized
    fun saveRemoteProfile(
        localUserId: String,
        profileId: String,
        enabled: Boolean,
        displayName: String? = null
    ): LocalUser {
        requireProfileIdentifier(profileId)
        val users = readUsers().toMutableList()
        val index = users.indexOfFirst { it.localId == localUserId }
        require(index >= 0) { "Local user does not exist" }
        val current = users[index]
        val updated = current.copy(
            displayName = displayName?.trim()?.takeIf(String::isNotEmpty) ?: current.displayName,
            profileId = profileId,
            profileEnabled = enabled,
            profileSyncError = null
        )
        users[index] = updated
        writeUsers(users)
        return updated
    }

    @Synchronized
    fun saveProfileSyncError(localUserId: String, message: String, disabled: Boolean = false) {
        val users = readUsers().toMutableList()
        val index = users.indexOfFirst { it.localId == localUserId }
        if (index < 0) return
        users[index] = users[index].copy(
            profileEnabled = if (disabled) false else users[index].profileEnabled,
            profileSyncError = message
        )
        writeUsers(users)
    }

    @Synchronized
    fun createUser(displayName: String): LocalUser {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty()) { "用户名不能为空" }
        require(cleanName.length <= 40) { "用户名不能超过 40 个字符" }
        val users = readUsers().toMutableList()
        require(users.none { it.displayName.equals(cleanName, ignoreCase = true) }) { "用户名已存在" }
        val user = LocalUser(displayName = cleanName)
        users += user
        writeUsers(users)
        userDirectory(user.localId).mkdirs()
        if (activeUserId() == null) setActiveUser(user.localId)
        return user
    }

    @Synchronized
    fun bindServer(localUserId: String, serverUserId: String, apiKey: String): LocalUser {
        val cleanServerId = serverUserId.trim()
        require(cleanServerId.isNotEmpty()) { "服务端用户 ID 不能为空" }
        val users = readUsers().toMutableList()
        val index = users.indexOfFirst { it.localId == localUserId }
        require(index >= 0) { "本地用户不存在" }
        val updated = users[index].copy(serverUserId = cleanServerId)
        credentials.save(localUserId, apiKey.trim())
        users[index] = updated
        writeUsers(users)
        return updated
    }

    @Synchronized
    fun unbindServer(localUserId: String): LocalUser {
        val users = readUsers().toMutableList()
        val index = users.indexOfFirst { it.localId == localUserId }
        require(index >= 0) { "本地用户不存在" }
        credentials.delete(localUserId)
        val updated = users[index].copy(serverUserId = null)
        users[index] = updated
        writeUsers(users)
        return updated
    }

    @Synchronized
    fun deleteLocalUser(localUserId: String) {
        val users = readUsers().toMutableList()
        require(users.removeAll { it.localId == localUserId }) { "本地用户不存在" }
        writeUsers(users)
        credentials.delete(localUserId)
        val directory = userDirectory(localUserId)
        check(directory.canonicalPath.startsWith(root.canonicalPath + File.separator))
        directory.deleteRecursively()
        if (activeUserId() == localUserId) setActiveUser(users.firstOrNull()?.localId)
    }

    fun activeUserId(): String? = preferences.getString(KEY_ACTIVE_USER, null)

    fun activeUser(): LocalUser? = activeUserId()?.let { id -> listUsers().firstOrNull { it.localId == id } }

    fun setActiveUser(localUserId: String?) {
        if (localUserId != null) require(listUsers().any { it.localId == localUserId })
        preferences.edit().apply {
            if (localUserId == null) remove(KEY_ACTIVE_USER) else putString(KEY_ACTIVE_USER, localUserId)
        }.apply()
    }

    fun userDirectory(localUserId: String): File = File(root, "users/$localUserId")

    fun createSessionDirectory(localUserId: String, sessionId: String = UUID.randomUUID().toString()): File {
        require(listUsers().any { it.localId == localUserId }) { "本地用户不存在" }
        return File(userDirectory(localUserId), "sessions/$sessionId").apply { mkdirs() }
    }

    /** Models and the active pointer are isolated by the server profile ID. */
    @Synchronized
    fun modelDirectory(localUserId: String, profileId: String? = null): File {
        val resolvedProfileId = profileId ?: findUser(localUserId)?.profileId
        val rootDirectory = File(userDirectory(localUserId), "models").apply { mkdirs() }
        if (resolvedProfileId == null) return rootDirectory
        requireProfileIdentifier(resolvedProfileId)
        val profileDirectory = File(rootDirectory, "profiles/$resolvedProfileId").apply { mkdirs() }

        // One-time migration from the pre-profile layout. Existing scoped files always win.
        rootDirectory.listFiles().orEmpty().filter(File::isFile).forEach { legacyFile ->
            val target = File(profileDirectory, legacyFile.name)
            if (!target.exists() && !legacyFile.renameTo(target)) {
                legacyFile.copyTo(target)
                legacyFile.delete()
            }
        }
        return profileDirectory
    }

    @Synchronized
    fun saveSession(session: TrainingSession) {
        val directory = createSessionDirectory(session.localUserId, session.sessionId)
        atomicWrite(File(directory, SESSION_FILE), session.toJson().toString(2))
    }

    fun loadSession(localUserId: String, sessionId: String): TrainingSession? =
        readJson(File(userDirectory(localUserId), "sessions/$sessionId/$SESSION_FILE"))?.let(TrainingSession::fromJson)

    fun listSessions(localUserId: String): List<TrainingSession> {
        val sessionsDir = File(userDirectory(localUserId), "sessions")
        return sessionsDir.listFiles()
            ?.filter(File::isDirectory)
            ?.mapNotNull { readJson(File(it, SESSION_FILE))?.let(TrainingSession::fromJson) }
            ?.sortedByDescending { it.collectedAtIso }
            .orEmpty()
    }

    fun listIncompleteSessions(): List<TrainingSession> = listUsers().flatMap { user ->
        listSessions(user.localId).filter { it.status !in TERMINAL_STATUSES }
    }

    /** Reopens historical uploads that failed before zero-based legacy markers were normalized. */
    fun requeueRepairableFailures(): List<TrainingSession> = listUsers().flatMap { user ->
        listSessions(user.localId).mapNotNull { session ->
            if (session.status != WorkflowStatus.FAILED ||
                !isLegacyMarkerRoundValidationError(session.error)
            ) {
                return@mapNotNull null
            }
            session.copy(
                status = WorkflowStatus.PACKAGED,
                error = "已兼容旧采集标记并重新加入同步队列"
            ).also(::saveSession)
        }
    }

    /**
     * Removes the collected files for [modelKey] and removes that
     * model run from the local session record. Other downloaded models from
     * the same session are deliberately retained.
     *
     * @return true when no model runs remained and the whole session directory
     * was removed; false when a metadata-only session record was retained.
     */
    @Synchronized
    fun deleteCollectedDataForRun(
        localUserId: String,
        sessionId: String,
        modelKey: String
    ): Boolean {
        val session = loadSession(localUserId, sessionId) ?: error("本地训练记录不存在")
        val matchingRun = session.modelRuns.firstOrNull { it.modelKey == modelKey }
            ?: error("该模型任务不属于所选训练记录")
        val remainingRuns = session.modelRuns.filterNot { it.modelKey == modelKey }
        val sessionDirectory = checkedSessionDirectory(localUserId, sessionId)

        if (remainingRuns.isEmpty()) {
            check(!sessionDirectory.exists() || sessionDirectory.deleteRecursively()) {
                "无法删除本地采集数据目录"
            }
            return true
        }

        sessionDirectory.listFiles().orEmpty()
            .filterNot { it.name == SESSION_FILE }
            .forEach { collectedFile ->
                check(collectedFile.deleteRecursively()) {
                    "无法删除本地采集文件 ${collectedFile.name}"
                }
            }
        saveSession(
            session.copy(
                modelRuns = remainingRuns,
                activeModelKey = session.activeModelKey.takeUnless { it == matchingRun.modelKey },
                error = "本地采集数据已删除，仅保留其余已下载模型"
            )
        )
        return false
    }

    fun newSessionId(): String {
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "${formatter.format(Date())}-${UUID.randomUUID().toString().take(8)}"
    }

    private fun readUsers(): List<LocalUser> = runCatching {
        val array = JSONArray(usersFile.readText(Charsets.UTF_8))
        List(array.length()) { LocalUser.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeUsers(users: List<LocalUser>) {
        atomicWrite(usersFile, JSONArray().apply { users.forEach { put(it.toJson()) } }.toString(2))
    }

    private fun clearRemoteProfileBindings() {
        val users = readUsers()
        if (users.any { it.profileId != null || it.profileEnabled != null || it.profileSyncError != null }) {
            writeUsers(users.map {
                it.copy(profileId = null, profileEnabled = null, profileSyncError = null)
            })
        }
    }

    private fun requireProfileIdentifier(profileId: String) {
        require(profileId.matches(PROFILE_ID_REGEX)) { "Invalid profile_id" }
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        if (!file.isFile) null else JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun checkedSessionDirectory(localUserId: String, sessionId: String): File {
        val sessionsRoot = File(userDirectory(localUserId), "sessions").canonicalFile
        val directory = File(sessionsRoot, sessionId).canonicalFile
        require(directory.parentFile == sessionsRoot) { "训练记录路径无效" }
        return directory
    }

    companion object {
        private const val ROOT_DIR = "personalization"
        private const val PREFS_NAME = "personalization_state"
        private const val KEY_ACTIVE_USER = "active_user_id"
        private const val SESSION_FILE = "session.json"
        private val PROFILE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val TERMINAL_STATUSES = setOf(WorkflowStatus.SUCCEEDED, WorkflowStatus.PARTIAL, WorkflowStatus.FAILED)
    }
}

internal fun mergeRecoveredProfile(
    users: List<LocalUser>,
    profileId: String,
    clientProfileId: String,
    displayName: String,
    enabled: Boolean
): Pair<List<LocalUser>, LocalUser> {
    val merged = users.toMutableList()
    val index = merged.indexOfFirst {
        it.profileId == profileId || it.clientProfileId == clientProfileId
    }
    val restored = if (index >= 0) {
        merged[index].copy(
            displayName = displayName,
            clientProfileId = clientProfileId,
            profileId = profileId,
            profileEnabled = enabled,
            profileSyncError = null,
            serverUserId = null
        ).also { merged[index] = it }
    } else {
        LocalUser(
            localId = clientProfileId,
            displayName = displayName,
            clientProfileId = clientProfileId,
            profileId = profileId,
            profileEnabled = enabled
        ).also { merged += it }
    }
    return merged to restored
}

internal fun isLegacyMarkerRoundValidationError(message: String?): Boolean {
    val value = message?.lowercase().orEmpty()
    return value.contains("markers") &&
        value.contains("round") &&
        value.contains("greater than or equal to 1") &&
        (value.contains("\"input\":0") || value.contains("input: 0"))
}
