package com.remotemonitor.app.data

import com.remotemonitor.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// ── Data classes ─────────────────────────────────────────────
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(
    val success: Boolean,
    val data: LoginData?
)
data class LoginData(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: String,
    val user: UserInfo
)
data class UserInfo(val id: Int, val email: String, val fullName: String, val role: String)

data class RegisterDeviceRequest(
    val deviceName: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String
)
data class RegisterDeviceResponse(val success: Boolean, val data: DeviceTokenData?)
data class DeviceTokenData(val deviceToken: String)

data class RefreshRequest(val refreshToken: String)
data class RefreshResponse(val success: Boolean, val data: RefreshData?)
data class RefreshData(val accessToken: String, val refreshToken: String)

// ── Retrofit Interface ────────────────────────────────────────
interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body req: LoginRequest): Response<LoginResponse>

    @POST("api/devices/register")
    suspend fun registerDevice(@Body req: RegisterDeviceRequest): Response<RegisterDeviceResponse>

    @POST("api/auth/refresh")
    suspend fun refreshToken(@Body req: RefreshRequest): Response<RefreshResponse>
}

// ── Singleton builder ─────────────────────────────────────────
object RetrofitClient {
    @Volatile private var instance: ApiService? = null

    fun getInstance(serverUrl: String): ApiService {
        return instance ?: synchronized(this) {
            instance ?: build(serverUrl).also { instance = it }
        }
    }

    private fun build(baseUrl: String): ApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .also { builder ->
                if (BuildConfig.DEBUG) {
                    builder.addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
                    )
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + '/')
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    /** Reset if server URL changes */
    fun reset() { instance = null }
}
