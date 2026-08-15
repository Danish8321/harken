namespace Harken.Core.Contracts;

public interface ICaptionClient
{
    Task ReceiveCaption(CaptionUpdate update);
    Task SessionStarted(Guid sessionId);
}
