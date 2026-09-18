package com.example.input_ds.personalization

import android.content.Context
import java.io.File

/** Rebuilds local history and usable ONNX artifacts from the server after reinstall. */
class ServerDataRecoveryManager(context: Context) {
    data class Result(
        val restoredSessions: Int,
        val restoredModels: Int,
        val failures: List<String>
    )

    private val appContext = context.applicationContext
    private val repository = PersonalizationRepository(appContext)
    private val modelManager = UserModelManager(appContext)

    private data class RestoredSession(
        val session: TrainingSession,
        val restoredModels: Int,
        val activeRun: ModelRun?
    )

    fun recover(users: List<LocalUser>): Result {
        val binding = requireNotNull(repository.deviceServerBinding()) {
            "Device server account is not bound"
        }
        val client = BrainApiClient(binding.serverUserId, binding.apiKey)
        var restoredSessions = 0
        var restoredModels = 0
        val failures = mutableListOf<String>()

        users.filter { it.profileId != null }.forEach { user ->
            val profileId = requireNotNull(user.profileId)
            runCatching {
                val datasets = client.listDatasets(profileId)
                    .groupBy { it.sessionId }
                    .mapNotNull { (_, versions) ->
                        versions.maxWithOrNull(
                            compareBy<BrainApiClient.ServerDataset> { it.uploadedAt }
                                .thenBy { it.version }
                        )
                    }
                val jobs = client.listTrainingJobs(profileId)
                var bestModel: Pair<TrainingSession, ModelRun>? = null
                datasets.forEach { dataset ->
                    runCatching {
                        val matchingJobs = jobs.filter { job ->
                            job.datasets.any {
                                it.datasetId == dataset.datasetId && it.version == dataset.version
                            }
                        }
                        restoreSession(client, user, dataset, matchingJobs)
                    }.onSuccess { restored ->
                        restoredSessions += 1
                        restoredModels += restored.restoredModels
                        restored.activeRun?.let { candidate ->
                            val current = bestModel?.second
                            if (current == null ||
                                (candidate.accuracy ?: Double.NEGATIVE_INFINITY) >
                                (current.accuracy ?: Double.NEGATIVE_INFINITY)
                            ) {
                                bestModel = restored.session to candidate
                            }
                        }
                    }.onFailure { error ->
                        failures += "${user.displayName}/${dataset.sessionId}: " +
                            (error.message ?: error::class.java.simpleName)
                    }
                }
                bestModel?.let { (session, run) ->
                    modelManager.activate(user.localId, session, run)
                }
            }.onFailure { error ->
                failures += "${user.displayName}: ${error.message ?: error::class.java.simpleName}"
            }
        }
        return Result(restoredSessions, restoredModels, failures)
    }

    private fun restoreSession(
        client: BrainApiClient,
        user: LocalUser,
        dataset: BrainApiClient.ServerDataset,
        jobs: List<BrainApiClient.ServerTrainingJob>
    ): RestoredSession {
        val sessionId = dataset.sessionId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() }
            ?: dataset.datasetId
        val sessionDirectory = repository.createSessionDirectory(user.localId, sessionId)
        val recordingName = dataset.originalFilename
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.endsWith(".npz", ignoreCase = true) }
            ?: "recording.npz"
        val recordingFile = File(sessionDirectory, recordingName)
        val recordingError = runCatching {
            if (!recordingFile.isFile || recordingFile.length() == 0L) {
                client.downloadDataset(
                    profileId = requireNotNull(user.profileId),
                    datasetId = dataset.datasetId,
                    version = dataset.version,
                    destination = recordingFile
                )
            }
        }.onFailure {
            recordingFile.delete()
        }.exceptionOrNull()
        val existingSession = repository.loadSession(user.localId, sessionId)
        if (existingSession != null &&
            existingSession.status !in PersonalizationRepository.TERMINAL_STATUSES
        ) {
            val activeRun = existingSession.modelRuns
                .filter { it.modelFile != null }
                .maxWithOrNull(
                    compareBy<ModelRun> { it.accuracy ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.updatedAtEpochMs }
                )
            return RestoredSession(existingSession, 0, activeRun)
        }
        val baseRuns = jobs.distinctBy { it.jobId }.map { job ->
            val existingModel = existingSession?.modelRuns
                ?.firstOrNull { it.jobId == job.jobId }
                ?.modelFile
                ?.takeIf { File(repository.modelDirectory(user.localId, user.profileId), it).isFile }
            ModelRun(
                modelKey = job.modelKey,
                preset = PreprocessingPreset.UNIFIED_1_45,
                datasetId = dataset.datasetId,
                datasetVersion = dataset.version,
                preprocessingSha256 = dataset.preprocessingSha256,
                jobId = job.jobId,
                status = job.status,
                progress = job.progress,
                accuracy = job.accuracy,
                error = job.error,
                modelFile = existingModel
            )
        }
        var session = TrainingSession(
            sessionId = sessionId,
            localUserId = user.localId,
            clientProfileId = user.clientProfileId,
            profileId = user.profileId,
            profileDisplayName = user.displayName,
            collectedAtIso = dataset.collectedAt,
            rounds = dataset.rounds,
            actionSeconds = dataset.windowPoints.toFloat() / CollectionSettings.SAMPLE_RATE_HZ,
            trainingEpochs = dataset.trainingEpochs,
            sampleCount = dataset.sampleCount,
            markers = dataset.markers,
            asyncTrials = dataset.asyncTrials,
            npzFile = recordingFile.name,
            protocol = dataset.protocol,
            labelNames = dataset.labelNames,
            status = WorkflowStatus.PARTIAL,
            modelRuns = baseRuns,
            error = recordingError?.let {
                "原始采集文件恢复失败：${it.message ?: it::class.java.simpleName}"
            }
        )

        var restoredModels = 0
        val restoredRuns = baseRuns.map { run ->
            val job = jobs.firstOrNull { it.jobId == run.jobId }
            if (run.modelFile != null) return@map run
            if (job?.status != "succeeded" || !job.artifactAvailable) return@map run
            runCatching {
                val temp = File.createTempFile("restore-${job.jobId}-", ".onnx", appContext.cacheDir)
                try {
                    client.downloadModel(job.jobId, temp)
                    val validated = modelManager.validateDownloaded(temp, session, run)
                    val accepted = modelManager.acceptDownloaded(
                        localUserId = user.localId,
                        profileId = requireNotNull(user.profileId),
                        jobId = job.jobId,
                        run = run,
                        validated = validated
                    )
                    restoredModels += 1
                    run.copy(modelFile = accepted.name, error = null)
                } finally {
                    temp.delete()
                }
            }.getOrElse { error ->
                if (requiresUnifiedPreprocessingRetraining(error)) {
                    run.copy(
                        status = RETRAIN_REQUIRED,
                        error = "旧模型预处理契约与当前统一 1–45 Hz 契约不兼容，将使用原始数据重新训练"
                    )
                } else {
                    run.copy(error = "模型恢复失败：${error.message ?: error::class.java.simpleName}")
                }
            }
        }

        val active = restoredRuns.filter { it.modelFile != null }
            .maxWithOrNull(compareBy<ModelRun> { it.accuracy ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.updatedAtEpochMs })
        val needsRetraining = restoredRuns.any { it.status == RETRAIN_REQUIRED }
        val normalizedRuns = if (needsRetraining) {
            restoredRuns.map { run ->
                if (run.status == RETRAIN_REQUIRED) {
                    run.copy(
                        datasetId = null,
                        datasetVersion = null,
                        preprocessingSha256 = null,
                        jobId = null,
                        status = "pending",
                        progress = 0.0,
                        accuracy = null,
                        error = "已排队按统一 1–45 Hz 预处理重新训练",
                        modelFile = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                } else {
                    // Force the worker to upload one new dataset version carrying the
                    // current unified preprocessing contract instead of reusing the old one.
                    run.copy(
                        datasetId = null,
                        datasetVersion = null,
                        preprocessingSha256 = null
                    )
                }
            }
        } else {
            restoredRuns
        }
        session = session.copy(
            status = when {
                needsRetraining && recordingError == null -> WorkflowStatus.PACKAGED
                needsRetraining -> WorkflowStatus.PARTIAL
                active != null && recordingError == null -> WorkflowStatus.SUCCEEDED
                active != null -> WorkflowStatus.PARTIAL
                normalizedRuns.any { it.status == "failed" } -> WorkflowStatus.FAILED
                else -> WorkflowStatus.PARTIAL
            },
            modelRuns = normalizedRuns,
            activeModelKey = active?.modelKey,
            error = when {
                needsRetraining && recordingError == null ->
                    "检测到旧预处理契约，已使用服务器原始数据排队按统一 1–45 Hz 契约重新训练"
                needsRetraining -> "检测到旧预处理契约，但原始采集文件尚未恢复，稍后重试"
                recordingError != null -> "模型记录已恢复，但原始采集文件下载失败：" +
                    (recordingError.message ?: recordingError::class.java.simpleName)
                active != null -> null
                normalizedRuns.isEmpty() -> "服务器已有采集数据，但没有训练任务"
                else -> normalizedRuns.mapNotNull { it.error }.firstOrNull()
                    ?: "服务器训练任务尚无可恢复模型"
            }
        )
        repository.saveSession(session)
        return RestoredSession(session, restoredModels, active)
    }

    private companion object {
        const val RETRAIN_REQUIRED = "retrain_required"
    }
}

internal fun requiresUnifiedPreprocessingRetraining(error: Throwable): Boolean {
    val messages = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
    return messages.contains("预处理") ||
        messages.contains("inference_preprocessing", ignoreCase = true)
}
