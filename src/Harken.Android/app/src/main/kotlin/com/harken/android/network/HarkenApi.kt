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
import retrofit2.http.Streaming
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

    // BACKEND WORK REQUIRED — this endpoint does not exist yet.
    //
    // The redesign's player needs the stored WAV back. Today an upload is one-way: once
    // a recording leaves the device there is no way to fetch it, so playback would only
    // ever work for files still sitting in filesDir. Streaming rather than downloading,
    // because a 3-hour session is ~345 MB and the phone should not have to hold it.
    //
    // Expected: 200 with audio/wav and Accept-Ranges: bytes so ExoPlayer/MediaPlayer can
    // seek; 404 when the row exists but the file was purged. Tracked in
    // docs/adr/0010-expressive-redesign.md under "Owed by the backend".
    @Streaming
    @GET("sessions/{id}/audio")
    suspend fun audio(@Path("id") id: UUID): Response<ResponseBody>

    // Soft delete: hides the Session from listSessions(), keeps the row and audio file.
    @DELETE("sessions/{id}")
    suspend fun deleteSession(@Path("id") id: UUID): Response<Unit>

    // Hard delete: permanently removes the row and the audio file from disk. Genuinely
    // irreversible — the UI must confirm before calling this.
    @DELETE("sessions/{id}/purge")
    suspend fun purgeSession(@Path("id") id: UUID): Response<Unit>
}
