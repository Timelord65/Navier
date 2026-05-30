public record CliArgs(String torrentPath, String outputDir, String trackerOverride) {

    public static CliArgs parse(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException(usage());
        }

        String torrentPath = expandHome(args[0]);
        String outputDir = null;
        String trackerOverride = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for -o\n" + usage());
                    outputDir = expandHome(args[++i]);
                }
                case "-t" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for -t\n" + usage());
                    trackerOverride = args[++i];
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i] + "\n" + usage());
            }
        }

        return new CliArgs(torrentPath, outputDir, trackerOverride);
    }

    public static String usage() {
        return "Usage: navier <torrent-file> [-o <output-dir>] [-t <tracker-url>]";
    }

    private static String expandHome(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
