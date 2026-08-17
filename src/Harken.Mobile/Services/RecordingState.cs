namespace Harken.Mobile.Services;

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
	private string? _lastCompletedFilePath;

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

	/// <summary>Path being written to right now; null when not recording.</summary>
	public string? CurrentFilePath
	{
		get { lock (_gate) { return _filePath; } }
	}

	/// <summary>Path of the last finished recording, whether it stopped by user action,
	/// silence timeout, or session cap. Survives past the recording so the page can upload
	/// it after the service is gone.</summary>
	public string? LastCompletedFilePath
	{
		get { lock (_gate) { return _lastCompletedFilePath; } }
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

	public void MarkStopped()
	{
		lock (_gate)
		{
			// Only promote a path that was actually being recorded — MarkStopped can arrive
			// twice (user stop racing an auto-stop), and the second must not clear the first
			// result or resurrect a stale one.
			if (_filePath is not null)
			{
				_lastCompletedFilePath = _filePath;
			}

			_filePath = null;
			_startedAt = null;
		}
	}
}
