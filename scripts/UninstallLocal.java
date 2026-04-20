// JDK-only local uninstaller.
// Keep this file limited to the Java standard library so it can run directly with:
//   java scripts/UninstallLocal.java
// or via Maven as a task runner with:
//   mvn exec:exec@uninstall-local
// Do not add Maven/Gradle dependencies here; the Java source launcher will not resolve them.

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class UninstallLocal {
    private static final String APP_NAME = "oqx";

    public static void main(String[] args) {
        try {
            new UninstallLocal().run(args);
        } catch (Exception e) {
            System.err.println("Uninstall failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        Options options = parseArgs(args);
        if (options.help) {
            printUsage();
            return;
        }

        Path installDir = options.installDir != null ? options.installDir : defaultInstallDir();

        List<Path> removed = new ArrayList<>();
        List<Path> missing = new ArrayList<>();
        removeIfExists(installDir.resolve(APP_NAME), removed, missing);
        removeIfExists(installDir.resolve(APP_NAME + ".cmd"), removed, missing);
        removeIfExists(installDir.resolve(APP_NAME + ".jar"), removed, missing);

        System.out.println("Uninstall oqx");
        System.out.println("  install dir:  " + installDir);
        System.out.println();

        if (!removed.isEmpty()) {
            System.out.println("Removed:");
            for (Path path : removed) {
                System.out.println("  " + path);
            }
            System.out.println();
        }

        if (!missing.isEmpty()) {
            System.out.println("Not found:");
            for (Path path : missing) {
                System.out.println("  " + path);
            }
            System.out.println();
        }

        if (removed.isEmpty()) {
            System.out.println("Nothing to do: oqx is already uninstalled from " + installDir);
        }
    }

    // ---- argument parsing ----

    private Options parseArgs(String[] args) {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.help = true;
            } else if (arg.startsWith("--install-dir=")) {
                options.installDir = Paths.get(arg.substring("--install-dir=".length())).toAbsolutePath().normalize();
            } else if (arg.equals("--install-dir")) {
                i = requireValue(args, i, arg);
                options.installDir = Paths.get(args[i]).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg + "\nUse --help for usage.");
            }
        }
        return options;
    }

    private int requireValue(String[] args, int index, String argName) {
        int next = index + 1;
        if (next >= args.length) {
            throw new IllegalArgumentException("Missing value for " + argName);
        }
        return next;
    }

    private void printUsage() {
        System.out.println("Uninstall a local oqx launcher and jar.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/UninstallLocal.java [--install-dir <path>]");
        System.out.println("  mvn exec:exec@uninstall-local [-Doqx.uninstallLocal.args=\"--install-dir <path>\"]");
        System.out.println();
        System.out.println("Defaults (when --install-dir is omitted):");
        System.out.println("  Uses the same PATH scan and preference order as install-local.");
        System.out.println("  Prefers: ~/.bio/bin > ~/.local/bin > ~/bin > any other writable home PATH entry.");
        System.out.println();
        System.out.println("Safety:");
        System.out.println("  Removes only oqx, oqx.cmd, and oqx.jar from the selected install directory.");
        System.out.println("  Never deletes the install directory itself.");
    }

    // ---- uninstall ----

    private void removeIfExists(Path path, List<Path> removed, List<Path> missing) throws IOException {
        if (!Files.exists(path)) {
            missing.add(path);
            return;
        }

        if (Files.isDirectory(path)) {
            throw new IllegalStateException("Refusing to remove directory: " + path);
        }

        Files.delete(path);
        removed.add(path);
    }

    // ---- install-dir resolution (kept in sync with InstallLocal.java) ----

    private Path defaultInstallDir() {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            exitNoInstallDir(home);
        }

        List<Path> candidates = new ArrayList<>();
        for (String part : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                Path entry = Paths.get(part).toAbsolutePath().normalize();
                if (isUnderHome(entry, home) && Files.isDirectory(entry) && Files.isWritable(entry)) {
                    candidates.add(entry);
                }
            } catch (Exception ignored) {
                // Skip malformed PATH entries.
            }
        }

        if (candidates.isEmpty()) {
            exitNoInstallDir(home);
        }

        Path bioBin = home.resolve(".bio").resolve("bin").toAbsolutePath().normalize();
        if (candidates.contains(bioBin)) {
            return bioBin;
        }

        Path localBin = home.resolve(".local").resolve("bin").toAbsolutePath().normalize();
        if (candidates.contains(localBin)) {
            return localBin;
        }

        Path homeBin = home.resolve("bin").toAbsolutePath().normalize();
        if (candidates.contains(homeBin)) {
            return homeBin;
        }

        candidates.sort(Comparator.comparing(Path::toString));
        return candidates.get(0);
    }

    private boolean isUnderHome(Path dir, Path home) {
        return dir.startsWith(home);
    }

    private void exitNoInstallDir(Path home) {
        System.err.println("Error: no writable directory under your home was found on PATH.");
        System.err.println();
        System.err.println("Use --install-dir <path> to point at the oqx install directory,");
        if (isWindows()) {
            System.err.println("or create a directory and add it to your user PATH, for example:");
            System.err.println("  mkdir %USERPROFILE%\\bin");
            System.err.println("  Then add %USERPROFILE%\\bin to your user PATH via System Properties.");
        } else {
            System.err.println("or create a directory and add it to your PATH, for example:");
            System.err.println("  mkdir -p " + home.resolve(".local/bin"));
            System.err.println("  export PATH=\"" + home.resolve(".local/bin") + ":$PATH\"");
            System.err.println("  # add that line to ~/.profile or your shell rc");
        }
        System.err.println();
        System.err.println("Then re-run this uninstaller.");
        System.exit(1);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class Options {
        private boolean help;
        private Path installDir;
    }
}
