package dz.association.app.core.network

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Typed failure used by the UI to pick a translated message. */
sealed class AppError {
    data object Network : AppError()
    data object Unauthorized : AppError()
    data class RateLimited(val retryAfterSeconds: Int?) : AppError()
    /** [code] is the backend's machine-readable error code (e.g. "already_voted"). */
    data class Api(val status: Int, val code: String) : AppError()
    data object Unknown : AppError()
}

sealed class Result<out T> {
    data class Ok<T>(val value: T) : Result<T>()
    data class Err(val error: AppError) : Result<Nothing>()
}

private val errorJson = Json { ignoreUnknownKeys = true }

fun Throwable.toAppError(): AppError = when (this) {
    is HttpException -> {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val code = body?.let { runCatching { errorJson.decodeFromString(ApiError.serializer(), it).error }.getOrNull() } ?: "http_${code()}"
        when (code()) {
            401 -> if (code == "invalid_credentials") AppError.Api(401, code) else AppError.Unauthorized
            429 -> AppError.RateLimited(response()?.headers()?.get("retry-after")?.toIntOrNull())
            else -> AppError.Api(code(), code)
        }
    }
    is IOException -> AppError.Network
    else -> AppError.Unknown
}

suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.Err(e.toAppError())
}
