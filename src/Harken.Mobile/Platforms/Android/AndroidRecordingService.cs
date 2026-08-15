using Android.Content;
using Harken.Mobile.Services;

namespace Harken.Mobile;

public class AndroidRecordingService : IRecordingService
{
	public void StartRecording()
	{
		var context = global::Android.App.Application.Context;
		var intent = new Intent(context, typeof(RecordingForegroundService));

		if (OperatingSystem.IsAndroidVersionAtLeast(26))
		{
			context.StartForegroundService(intent);
		}
		else
		{
			context.StartService(intent);
		}
	}

	public void StopRecording()
	{
		var context = global::Android.App.Application.Context;
		var intent = new Intent(context, typeof(RecordingForegroundService));
		context.StopService(intent);
	}
}
