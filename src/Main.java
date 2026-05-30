import TorrentClient.FileStorage;
import TorrentClient.TorrentDownloader;
import TorrentClient.TorrentMetadata;
import TorrentClient.TrackerClient;

import java.io.File;

public class Main {

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");

        CliArgs cli;
        try {
            cli = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        try {
            File torrentFile = new File(cli.torrentPath());
            if (!torrentFile.exists()) {
                throw new IllegalArgumentException("Torrent file not found: " + torrentFile.getAbsolutePath());
            }

            TorrentMetadata metadata = new TorrentMetadata(torrentFile);
            if (metadata.multiFile) {
                throw new UnsupportedOperationException("Multi-file torrents are not supported yet");
            }

            File outDir = cli.outputDir() != null
                    ? new File(cli.outputDir())
                    : torrentFile.getParentFile();
            outDir.mkdirs();

            File outputFile = new File(outDir, metadata.name);
            System.out.println("Output:   " + outputFile.getAbsolutePath());

            FileStorage storage = new FileStorage(outputFile, metadata);
            new TorrentDownloader(metadata, storage, TrackerClient.generatePeerId(), cli.trackerOverride())
                    .download();

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
