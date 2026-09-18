package com.example.input_ds.engine

import android.content.Context
import com.example.input_ds.personalization.BrainApiClient
import com.example.input_ds.personalization.ContextCompletionRequest
import com.example.input_ds.personalization.ContextCompletionResponse
import com.example.input_ds.personalization.DeviceServerBinding
import com.example.input_ds.personalization.InitialSentenceRequest
import com.example.input_ds.personalization.InitialSentenceResponse
import com.example.input_ds.personalization.SecureCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authenticated, optional remote provider. Missing credentials and network failures are normal fallbacks. */
class LlmPredictionProvider(context: Context) {
    private val credentialStore = SecureCredentialStore(context.applicationContext)

    @Volatile
    private var cachedBinding: DeviceServerBinding? = null
    @Volatile
    private var cachedClient: BrainApiClient? = null

    fun isConfigured(): Boolean = credentialStore.loadDeviceBinding() != null

    suspend fun predictContext(
        request: ContextCompletionRequest
    ): RemotePredictionResult<ContextCompletionResponse> = call {
        predictContextCompletion(request)
    }

    suspend fun predictInitial(
        request: InitialSentenceRequest
    ): RemotePredictionResult<InitialSentenceResponse> = call {
        predictInitialSentence(request)
    }

    private suspend fun <T> call(block: BrainApiClient.() -> T): RemotePredictionResult<T> =
        withContext(Dispatchers.IO) {
            val client = clientOrNull()
                ?: return@withContext RemotePredictionResult(error = "未配置服务器凭据")
            try {
                RemotePredictionResult(value = client.block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: BrainApiClient.ApiException) {
                RemotePredictionResult(
                    error = error.message ?: "远程预测失败",
                    retryAfterSeconds = error.retryAfterSeconds
                )
            } catch (error: Throwable) {
                RemotePredictionResult(error = error.message ?: "远程预测失败")
            }
        }

    @Synchronized
    private fun clientOrNull(): BrainApiClient? {
        val binding = credentialStore.loadDeviceBinding() ?: run {
            cachedBinding = null
            cachedClient = null
            return null
        }
        if (binding != cachedBinding || cachedClient == null) {
            cachedBinding = binding
            cachedClient = BrainApiClient(
                binding.serverUserId,
                binding.apiKey,
                readTimeoutMs = PREDICTION_READ_TIMEOUT_MS
            )
        }
        return cachedClient
    }

    private companion object {
        const val PREDICTION_READ_TIMEOUT_MS = 6_000
    }
}

data class RemotePredictionResult<T>(
    val value: T? = null,
    val error: String? = null,
    val retryAfterSeconds: Long? = null
)
