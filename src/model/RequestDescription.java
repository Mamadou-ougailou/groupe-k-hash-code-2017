package model;

public class RequestDescription {
    private Video video;
    private Endpoint endpoint;
    private int numberOfRequests;

    public RequestDescription(Video video, Endpoint endpoint, int numberOfRequests) {
        this.video = video;
        this.endpoint = endpoint;
        this.numberOfRequests = numberOfRequests;
    }

    public Video getVideo() {
        return video;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public int getNumberOfRequests() {
        return numberOfRequests;
    }
}
