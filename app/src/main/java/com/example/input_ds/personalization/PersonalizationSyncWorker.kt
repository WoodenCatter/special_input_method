package com.example.input_ds.personalization

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class PersonalizationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val localUserId = inputData.getString(KEY_LOCAL_USER_ID) ?: return Result.failure()
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val repository = PersonalizationRepository(applicationContext)
        var session = repository.loadSession(localUserId, sessionId) ?: return Result.failure()
        if (repository.listUsers().none { it.localId == localUserId }) return Result.failure()
        val binding = repository.deviceServerBinding()
        if (binding == null) {
            repository.saveSession(session.copy(status = WorkflowStatus.PACKAGED, error = "等待此设备绑定服务端账号"))
            return Result.success()
        }

        try {
            val syncedUser = ProfileSyncManager(applicationContext).synchronize(localUserId)
            if (syncedUser.profileEnabled == false) {
                repository.saveSession(
                    session.copy(status = WorkflowStatus.PACKAGED, error = "当前具体用户已在服务器停用，等待重新启用")
                )
                return Result.retry()
            }
            require(session.clientProfileId == null || session.clientProfileId == syncedUser.clientProfileId) {
                "采集记录与当前本地用户身份不一致，已阻止跨用户上传"
            }
            require(session.profileId == null || session.profileId == syncedUser.profileId) {
                "采集记录属于另一个服务器 profile，已阻止跨 profile 上传"
            }
            session = session.copy(
                clientProfileId = syncedUser.clientProfileId,
                profileId = requireNotNull(syncedUser.profileId),
                profileDisplayName = syncedUser.displayName,
                error = null
            ).also(repository::saveSession)
        } catch (apiError: BrainApiClient.ApiException) {
            val message = if (apiError.errorCode == ProfileSyncManager.PROFILE_DISABLED) {
                "当前具体用户已在服务器停用"
            } else {
                "同步服务器用户失败：${apiError.message}"
            }
            repository.saveSession(session.copy(error = message))
            return if (apiError.isTransient) Result.retry() else Result.failure()
        } catch (error: Throwable) {
            repository.saveSession(session.copy(status = WorkflowStatus.FAILED, error = error.message))
            return Result.failure()
        }

        val client = BrainApiClient(binding.serverUserId, binding.apiKey)
        val modelManager = UserModelManager(applicationContext)
        val sessionDirectory = repository.createSessionDirectory(localUserId, sessionId)
        val npz = File(sessionDirectory, session.npzFile)
        if (!npz.isFile) {
            repository.saveSession(session.copy(status = WorkflowStatus.FAILED, error = "本地连续 NPZ 已丢失"))
            return Result.failure()
        }

        try {
            for (index in session.modelRuns.indices) {
                var run = session.modelRuns[index]
                if (run.status == "failed" || run.modelFile != null) continue
                try {
                    if (run.datasetId == null) {
                        session = updateSession(repository, session, index, run, WorkflowStatus.UPLOADING)
                        val uploaded = client.uploadContinuousDataset(session, run, npz)
                        run = run.copy(
                            datasetId = uploaded.datasetId,
                            datasetVersion = uploaded.version,
                            preprocessingSha256 = uploaded.preprocessingSha256,
                            status = "uploaded",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                        session = updateSession(repository, session, index, run, WorkflowStatus.UPLOADING)
                    }

                    if (run.jobId == null) {
                        val created = client.findTrainingJob(session, run)
                            ?: client.createTrainingJob(session, run)
                        run = applySnapshot(run, created)
                        session = updateSession(repository, session, index, run, WorkflowStatus.TRAINING)
                    }

                    val snapshot = client.getTrainingJob(requireNotNull(run.jobId))
                    run = applySnapshot(run, snapshot)
                    session = updateSession(repository, session, index, run, WorkflowStatus.TRAINING)
                    when (snapshot.status) {
                        "failed" -> {
                            run = run.copy(status = "failed", error = snapshot.error ?: "训练失败")
                            session = updateSession(repository, session, index, run, WorkflowStatus.TRAINING)
                        }
                        "succeeded" -> {
                            val temporary = File(
                                repository.modelDirectory(localUserId, session.profileId),
                                ".${run.modelKey}-${run.jobId}.onnx.tmp"
                            )
                            if (temporary.exists()) temporary.delete()
                            session = session.copy(status = WorkflowStatus.DOWNLOADING).also(repository::saveSession)
                            client.downloadModel(requireNotNull(run.jobId), temporary)
                            val validated = modelManager.validateDownloaded(temporary, session, run)
                            val accepted = modelManager.acceptDownloaded(
                                localUserId,
                                requireNotNull(session.profileId),
                                requireNotNull(run.jobId),
                                run,
                                validated
                            )
                            run = run.copy(
                                status = "succeeded",
                                modelFile = accepted.name,
                                error = null,
                                updatedAtEpochMs = System.currentTimeMillis()
                            )
                            session = updateSession(repository, session, index, run, WorkflowStatus.DOWNLOADING)
                        }
                    }
                } catch (apiError: BrainApiClient.ApiException) {
                    if (apiError.isTransient) throw apiError
                    if (apiError.errorCode == ProfileSyncManager.PROFILE_DISABLED) {
                        repository.saveProfileSyncError(localUserId, apiError.message.orEmpty(), disabled = true)
                        repository.saveSession(
                            session.copy(status = WorkflowStatus.PACKAGED, error = "当前具体用户已在服务器停用，等待重新启用")
                        )
                        return Result.retry()
                    }
                    if (apiError.statusCode == 401 || apiError.statusCode == 403) {
                        repository.saveSession(session.copy(error = "账号凭据已失效，请重新绑定"))
                        return Result.failure()
                    }
                    run = run.copy(status = "failed", error = "HTTP ${apiError.statusCode}: ${apiError.message}")
                    session = updateSession(repository, session, index, run, WorkflowStatus.TRAINING)
                } catch (validationError: Throwable) {
                    run = run.copy(status = "failed", error = validationError.message ?: "模型验证失败")
                    session = updateSession(repository, session, index, run, WorkflowStatus.TRAINING)
                }
            }

            val successful = session.modelRuns.filter { it.status == "succeeded" && it.modelFile != null }
            val allTerminal = session.modelRuns.all { it.status == "failed" || (it.status == "succeeded" && it.modelFile != null) }
            if (!allTerminal) {
                repository.saveSession(session.copy(status = WorkflowStatus.TRAINING, error = null))
                return Result.retry()
            }
            if (successful.isEmpty()) {
                repository.saveSession(session.copy(status = WorkflowStatus.FAILED, error = "所选模型均未训练或验证成功"))
                return Result.failure()
            }

            val best = successful.maxWithOrNull(
                compareBy<ModelRun> { it.accuracy ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.updatedAtEpochMs }
            ) ?: return Result.failure()
            modelManager.activate(localUserId, session, best)
            val finalStatus = if (successful.size == session.modelRuns.size) WorkflowStatus.SUCCEEDED else WorkflowStatus.PARTIAL
            session = session.copy(
                status = finalStatus,
                activeModelKey = best.modelKey,
                error = if (finalStatus == WorkflowStatus.PARTIAL) "部分模型失败，已启用验证准确率最高的有效模型" else null
            )
            repository.saveSession(session)
            return Result.success()
        } catch (error: BrainApiClient.ApiException) {
            repository.saveSession(session.copy(error = "网络暂不可用：${error.message}"))
            return Result.retry()
        }
    }

    private fun applySnapshot(run: ModelRun, snapshot: BrainApiClient.JobSnapshot): ModelRun = run.copy(
        jobId = snapshot.jobId,
        status = snapshot.status,
        progress = snapshot.progress,
        accuracy = snapshot.accuracy ?: run.accuracy,
        error = snapshot.error,
        updatedAtEpochMs = System.currentTimeMillis()
    )

    private fun updateSession(
        repository: PersonalizationRepository,
        session: TrainingSession,
        runIndex: Int,
        run: ModelRun,
        status: WorkflowStatus
    ): TrainingSession {
        val runs = session.modelRuns.toMutableList().apply { this[runIndex] = run }
        return session.copy(modelRuns = runs, status = status, error = null).also(repository::saveSession)
    }

    companion object {
        const val KEY_LOCAL_USER_ID = "local_user_id"
        const val KEY_SESSION_ID = "session_id"
    }
}

object TrainingWorkScheduler {
    fun enqueue(context: Context, session: TrainingSession) {
        val input = Data.Builder()
            .putString(PersonalizationSyncWorker.KEY_LOCAL_USER_ID, session.localUserId)
            .putString(PersonalizationSyncWorker.KEY_SESSION_ID, session.sessionId)
            .build()
        val request = OneTimeWorkRequestBuilder<PersonalizationSyncWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("personalization-training")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "personalization-${session.localUserId}-${session.sessionId}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun resumeIncomplete(context: Context) {
        val repository = PersonalizationRepository(context)
        repository.listIncompleteSessions().forEach { session ->
            if (session.status != WorkflowStatus.COLLECTING) enqueue(context, session)
        }
    }
}
