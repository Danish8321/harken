package com.harken.android.network

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.UUID

// Matches the 7 endpoints on src/Harken.Api/Program.cs exactly — no auth (ADR-0009),
// no endpoint this client calls that the backend doesn't expose, and vice versa.
interface HarkenApi {

    @GET("health")
    suspend fun health(): Response<ResponseBody>

    @Multipart
    @POST("sessions")
    suspend fun upload(
        @Part audio: MultipartBody.Part,
        @Part("source") source: okhttp3.RequestBody,
        @Part("recordingId") recordingId: okhttp3.RequestBody?,
    ): Response<SessionListItem>

    @GET("sessions")
    suspend fun listSessions(): Response<List<SessionListItem>>

    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: UUID): Response<SessionDetail>

    @POST("sessions/{id}/summary")
    suspend fun summarize(@Path("id") id: UUID): Response<SessionSummary>

    // Soft delete: hides the Session from listSessions(), keeps the row and audio file.
    @DELETE("sessions/{id}")
    suspend fun deleteSession(@Path("id") id: UUID): Response<Unit>

    // Hard delete: permanently removes the row and the audio file from disk. Genuinely
    // irreversible — the UI must confirm before calling this.
    @DELETE("sessions/{id}/purge")
    suspend fun purgeSession(@Path("id") id: UUID): Response<Unit>
}
