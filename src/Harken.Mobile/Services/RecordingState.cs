using Harken.Core.Audio;

namespace Harken.Mobile.Services;

/// <summary>A finished recording: the file to upload, and why capture ended.</summary>
/// <param name="StopReason"><see cref="RecordingStopReason.None"/> for a manual stop.</param>
public sealed record RecordingCompleted(Guid RecordingId, string FilePath, RecordingStopReason StopReason);

/// <summary>
/// Shared state for the in-progress (or just-finished) recording. The platform's
/// foreground service writes it; the page reads it through <see cref="IRecordingService"/>.
///
/// A singleton rather than a bound-service connection: the foreground service runs in this
/// same process, so binding would be ceremony around a field read. Every member is guarded
/// because the writer runs on the capture thread and the reader on the UI thread.
/// </summary>
public sealed class RecordingState
{
	private readonly object _gate = new();

	private Guid? _recordingId;
	private string? _filePath;
	private DateTimeOffset? _startedAt;

	/// <summary>
	/// Raised once per recording, after the writer is closed and the file is complete.
	/// Every stop routes through here — the Stop button, the silence timeout, and the
	/// session cap alike — so the upload has exactly one trigger and an auto-stop uploads
	/// the same way a manual one does (ADR-0007: auto-stop *and upload*, not just stop).
	/// </summary>
	public event Action<RecordingCompleted>? Completed;

	/// <summary>Id the file is named for, generated at start so a retried upload is the
	/// same recording (slice-06 Task 7).</summary>
	public Guid? RecordingId
	{
		get { lock (_gate) { return _recordingId; } }
	}

	public bool IsRecording
	{
		get { lock (_gate) { return _startedAt is not null; } }
	}

	public TimeSpan Elapsed
	{
		get
		{
			lock (_gate)
			{
				return _startedAt is null ? TimeSpan.Zero : DateTimeOffset.UtcNow - _startedAt.Value;
			}
		}
	}

	public void MarkStarted(Guid recordingId, string filePath)
	{
		lock (_gate)
		{
			_recordingId = recordingId;
			_filePath = filePath;
			_startedAt = DateTimeOffset.UtcNow;
		}
	}

	public void MarkStopped(RecordingStopReason stopReason = RecordingStopReason.None)
	{
		RecordingCompleted? completed = null;

		lock (_gate)
		{
			// Only promote a path that was actually being recorded — MarkStopped can arrive
			// twice (a Stop tap racing an auto-stop), and the second must not clear the first
			// result or resurrect a stale one. That same check makes Completed fire once.
			if (_filePath is not null)
			{
				completed = new RecordingCompleted(_recordingId!.Value, _filePath, stopReason);
			}

			_filePath = null;
			_startedAt = null;
		}

		// Raised outside the lock: a handler uploads, and holding the lock across that would
		// block every property read on the UI thread for the length of a network call.
		if (completed is not null)
		{
			Completed?.Invoke(completed);
		}
	}
}
