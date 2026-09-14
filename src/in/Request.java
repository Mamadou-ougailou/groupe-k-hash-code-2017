
public class Request {
    public int videoId;
    public int endpointId;
    public int numRequests;

    public Request(int videoId, int endpointId, int numRequests) {
        this.videoId = videoId;
        this.endpointId = endpointId;
        this.numRequests = numRequests;
    }
}
