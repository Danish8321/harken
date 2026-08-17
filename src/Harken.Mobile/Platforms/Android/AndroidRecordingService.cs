using Android.Content;
using Harken.Mobile.Services;

namespace Harken.Mobile;

public class AndroidRecordingService : IRecordingService
{
	/// <summary>Intent extras carrying the recording to the foreground service, which is a
	/// separate component and cannot be handed an object reference.</summary>
	public const string RecordingIdExtra = "harken.recordingId";
	public const string FilePathExtra = "harken.filePath";

	private readonly RecordingState _state;

	public AndroidRecordingService(RecordingState state)
	{
		_state = state;
	}

	/// <summary>Forwarded straight from the shared state the foreground service writes.</summary>
	public event Action<RecordingCompleted>? Completed
	{
		add => _state.Completed += value;
		remove => _state.Completed -= value;
	}

	public bool IsRecording => _state.IsRecording;

	public TimeSpan Elapsed => _state.Elapsed;

	public Guid StartRecording()
	{
		var recordingId = Guid.NewGuid();
		var filePath = Path.Combine(FileSystem.AppDataDirectory, $"{recordingId:N}.wav");

		// Marked started here, not in the service, so the page sees the recording the moment
		// it asks rather than after the service has been scheduled and run. The service is
		// still the authority on failure: if it cannot open the file it marks stopped.
		_state.MarkStarted(recordingId, filePath);

		var context = global::Android.App.Application.Context;
		var intent = new Intent(context, typeof(RecordingForegroundService));
		intent.PutExtra(RecordingIdExtra, recordingId.ToString());
		intent.PutExtra(FilePathExtra, filePath);

		if (OperatingSystem.IsAndroidVersionAtLeast(26))
		{
			context.StartForegroundService(intent);
		}
		else
		{
			context.StartService(intent);
		}

		return recordingId;
	}

	public void StopRecording()
	{
		var context = global::Android.App.Application.Context;
		var intent = new Intent(context, typeof(RecordingForegroundService));
		context.StopService(intent);
	}
}
