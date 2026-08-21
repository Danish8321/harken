package com.harken.android.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

// Retrofit needs a fixed base URL at construction, but this app's backend URL is set at
// runtime in Onboarding/Settings (AppSettings.baseUrl). Rather than rebuilding Retrofit
// on every change, one client is built against a placeholder host and this interceptor
// rewrites scheme/host/port to whatever AppSettings currently holds, read fresh on each
// request — the same problem MAUI's OnboardingPage/SettingsPage solved by mutating a
// single HttpClient's BaseAddress.
class BaseUrlInterceptor(private val currentBaseUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val target = currentBaseUrl().toHttpUrlOrNull()
            ?: return chain.proceed(original)

        val newUrl = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}

object NetworkModule {
    private const val PlaceholderBaseUrl = "http://localhost/"

    fun create(currentBaseUrl: () -> String): HarkenApi {
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(UUID::class.java, UuidAdapter())
            .create()

        val client = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor(currentBaseUrl))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(PlaceholderBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(HarkenApi::class.java)
    }
}

private class UuidAdapter :
    com.google.gson.JsonSerializer<UUID>,
    com.google.gson.JsonDeserializer<UUID> {

    override fun serialize(
        src: UUID,
        typeOfSrc: java.lang.reflect.Type,
        context: com.google.gson.JsonSerializationContext,
    ) = com.google.gson.JsonPrimitive(src.toString())

    override fun deserialize(
        json: com.google.gson.JsonElement,
        typeOfT: java.lang.reflect.Type,
        context: com.google.gson.JsonDeserializationContext,
    ): UUID = UUID.fromString(json.asString)
}
