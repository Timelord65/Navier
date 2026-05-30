package TorrentClient;

import Bencoder.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static java.util.Objects.isNull;

public class TrackerClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public final TorrentMetadata metadata;
    private final String announceUrl;
    private final byte[] peerId;
    private final int listeningPort;
    private long downloaded = 0;

    public TrackerClient(TorrentMetadata metadata) {
        this(metadata, generatePeerId(), 51413);
    }

    public TrackerClient(TorrentMetadata metadata, byte[] peerId) {
        this(metadata, peerId, 51413);
    }

    public TrackerClient(TorrentMetadata metadata, byte[] peerId, int listeningPort) {
        this(metadata, peerId, metadata.announceUrl, listeningPort);
    }

    public TrackerClient(TorrentMetadata metadata, byte[] peerId, String announceUrl, int listeningPort) {
        this.metadata = metadata;
        this.peerId = peerId;
        this.announceUrl = announceUrl;
        this.listeningPort = listeningPort;
    }

    public static List<Peer> collectPeersFromAllTrackers(TorrentMetadata metadata, byte[] peerId) throws Exception {
        return collectPeersFromUrls(metadata.announceUrls, metadata, peerId);
    }

    public static List<Peer> collectPeersFromUrls(List<String> urls, TorrentMetadata metadata, byte[] peerId) throws Exception {
        List<Peer> allPeers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String url : urls) {
            try {
                TrackerClient client = new TrackerClient(metadata, peerId, url, 51413);
                for (Peer peer : client.announceStarted()) {
                    String key = peer.ip() + ":" + peer.port();
                    if (seen.add(key)) {
                        allPeers.add(peer);
                    }
                }
            } catch (Exception e) {
                System.err.println("Tracker " + url + " failed: " + e.getMessage());
            }
        }
        return allPeers;
    }

    public static byte[] generatePeerId() {
        byte[] id = new byte[20];
        byte[] prefix = "-NV0001-".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, id, 0, prefix.length);

        Random random = new Random();
        for (int i = prefix.length; i < 20; i++) {
            id[i] = (byte) ('0' + random.nextInt(10));
        }
        return id;
    }

    public List<Peer> getPeers() throws Exception {
        return getPeers(null);
    }

    public List<Peer> getPeersForRefresh() throws Exception {
        return getPeers(null);
    }

    public List<Peer> announceStarted() throws Exception {
        return getPeers("started");
    }

    private List<Peer> getPeers(String event) throws Exception {
        long left = Math.max(0, metadata.totalSize - downloaded);

        StringBuilder url = new StringBuilder(announceUrl)
                .append("?info_hash=").append(TorrentMetadata.urlEncodeHash(metadata.infoHashBytes))
                .append("&peer_id=").append(TorrentMetadata.urlEncodeHash(peerId))
                .append("&port=").append(listeningPort)
                .append("&uploaded=0")
                .append("&downloaded=").append(downloaded)
                .append("&left=").append(left)
                .append("&compact=1")
                .append("&numwant=200");
        if (event != null && !event.isEmpty()) {
            url.append("&event=").append(event);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("User-Agent", "Navier/1.0")
                .header("Accept", "*/*")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Tracker returned status code: " + response.statusCode());
        }

        BDecoder decoder = new BDecoder(response.body());
        BElement<?> root = decoder.decode();

        if (!(root instanceof BDict dict)) {
            throw new RuntimeException("Invalid tracker response: not a dictionary");
        }

        if (dict.getValue().containsKey("failure reason")) {
            BString failure = (BString) dict.getValue().get("failure reason");
            throw new RuntimeException("Tracker error: " + new String(failure.getValue(), StandardCharsets.UTF_8));
        }

        BElement<?> peersElement = dict.getValue().get("peers");
        if (isNull(peersElement)) {
            throw new RuntimeException("Tracker response missing 'peers' key");
        }
        return switch (peersElement) {
            case BString bStr -> parseCompactPeers(bStr);
            case BList bList -> parseDictionaryPeers(bList);
            default -> throw new RuntimeException("Unknown peers format: " + peersElement.getClass());
        };
    }

    public void updateDownloaded(long bytes) {
        this.downloaded = bytes;
    }

    private List<Peer> parseCompactPeers(BString peersString) {
        byte[] peersData = peersString.getValue();
        List<Peer> peers = new ArrayList<>();

        if (peersData.length % 6 != 0) {
            throw new RuntimeException("Invalid compact peers length: " + peersData.length);
        }

        for (int i = 0; i < peersData.length; i += 6) {
            String ip = (peersData[i] & 0xFF) + "." +
                    (peersData[i + 1] & 0xFF) + "." +
                    (peersData[i + 2] & 0xFF) + "." +
                    (peersData[i + 3] & 0xFF);

            int port = ((peersData[i + 4] & 0xFF) << 8) | (peersData[i + 5] & 0xFF);
            if (port > 0) {
                peers.add(new Peer(ip, port));
            }
        }

        return peers;
    }

    private List<Peer> parseDictionaryPeers(BList peersList) {
        List<Peer> peers = new ArrayList<>();
        for (BElement<?> elem : peersList.getValue()) {
            if (elem instanceof BDict peerDict) {
                BString ipElem = (BString) peerDict.getValue().get("ip");
                BInteger portElem = (BInteger) peerDict.getValue().get("port");

                if (ipElem != null && portElem != null) {
                    String ip = new String(ipElem.getValue(), StandardCharsets.UTF_8);
                    int port = portElem.getValue().intValue();
                    peers.add(new Peer(ip, port));
                }
            }
        }
        return peers;
    }

    public record Peer(String ip, int port) {
        @Override
        public String toString() {
            return ip + ":" + port;
        }
    }
}
