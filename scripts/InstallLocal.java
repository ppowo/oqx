// JDK-only local installer.
// Keep this file limited to the Java standard library so it can run directly with:
//   java scripts/InstallLocal.java
// or via Maven as a task runner with:
//   mvn exec:exec@install-local
// Do not add Maven/Gradle dependencies here; the Java source launcher will not resolve them.

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InstallLocal {
    private static final String APP_NAME = "oqx";
    private static final String MAIN_CLASS = "oqx.Main";
    private static final String JAVA_RELEASE = "17";

    public static void main(String[] args) {
        try {
            new InstallLocal().run(args);
        } catch (Exception e) {
            System.err.println("Install failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        Options options = parseArgs(args);
        if (options.help) {
            printUsage();
            return;
        }

        Path projectRoot = findProjectRoot();
        Path sourceRoot = projectRoot.resolve("src").resolve("main").resolve("java");
        Path mainSource = sourceRoot.resolve("oqx").resolve("Main.java");
        if (!Files.isRegularFile(mainSource)) {
            throw new IllegalStateException("Expected app source at " + mainSource);
        }

        Path buildRoot = projectRoot.resolve("build").resolve("install-local");
        recreateDirectory(buildRoot);

        Path classesDir = buildRoot.resolve("classes");
        Files.createDirectories(classesDir);
        compileSources(sourceRoot, classesDir);

        Path builtJar = buildRoot.resolve(APP_NAME + ".jar");
        createJar(classesDir, builtJar);

        Path installDir = options.installDir != null ? options.installDir : defaultInstallDir();
        Files.createDirectories(installDir);

        Path installedJar = installDir.resolve(APP_NAME + ".jar");
        Files.copy(builtJar, installedJar, StandardCopyOption.REPLACE_EXISTING);

        Path launcher = writeLauncher(installDir);

        System.out.println("Installed " + APP_NAME);
        System.out.println("  project root: " + projectRoot);
        System.out.println("  install dir:  " + installDir);
        System.out.println("  jar:          " + installedJar);
        System.out.println("  launcher:     " + launcher);

        if (!isOnPath(installDir)) {
            printPathHint(installDir);
        }

        System.out.println();
        System.out.println("Try it with:");
        System.out.println("  " + APP_NAME);
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
        System.out.println("Build and install a local oqx launcher from source.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/InstallLocal.java [--install-dir <path>]");
        System.out.println("  mvn exec:exec@install-local [-Doqx.installLocal.args=\"--install-dir <path>\"]");
        System.out.println();
        System.out.println("The launcher and jar are installed side by side (JBang-style sibling layout).");
        System.out.println();
        System.out.println("Defaults (when --install-dir is omitted):");
        System.out.println("  Scans PATH for writable directories under your home.");
        System.out.println("  Prefers: ~/.bio/bin > ~/.local/bin > ~/bin > any other match.");
        System.out.println("  ~/.bio/bin is a personal convention (edge case), preferred if present.");
        System.out.println("  If no writable home-subdirectory is on PATH, the installer exits");
        System.out.println("  with instructions for creating one.");
    }

    // ---- build ----

    private Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("scripts").resolve("InstallLocal.java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find project root containing scripts/InstallLocal.java");
    }

    private void compileSources(Path sourceRoot, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK is required to run this installer (no compiler found).");
        }

        List<Path> sourceFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sourceFiles = stream
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                .sorted()
                .collect(Collectors.toList());
        }

        if (sourceFiles.isEmpty()) {
            throw new IllegalStateException("No Java source files found under " + sourceRoot);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of("--release", JAVA_RELEASE, "-d", classesDir.toString());
            boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits).call());
            if (!ok) {
                throw new IllegalStateException(formatDiagnostics(diagnostics));
            }
        }
    }

    private String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder("Compilation failed:\n");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            sb.append("- ")
              .append(diagnostic.getKind())
              .append(": ")
              .append(diagnostic.getMessage(Locale.ROOT));

            JavaFileObject source = diagnostic.getSource();
            if (source != null) {
                sb.append(" (").append(source.getName());
                if (diagnostic.getLineNumber() >= 0) {
                    sb.append(":").append(diagnostic.getLineNumber());
                }
                sb.append(")");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private void createJar(Path classesDir, Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, MAIN_CLASS);

        try (OutputStream outputStream = Files.newOutputStream(jarPath);
             JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest);
             Stream<Path> stream = Files.walk(classesDir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                String entryName = classesDir.relativize(file).toString().replace(File.separatorChar, '/');
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                jarOutputStream.putNextEntry(entry);
                Files.copy(file, jarOutputStream);
                jarOutputStream.closeEntry();
            }
        }
    }

    // ---- install ----

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

        // Pick the best candidate:
        //  1. ~/.bio/bin — personal pattern (edge case), preferred if present
        //  2. ~/.local/bin — most standard XDG-like location
        //  3. ~/bin — traditional Unix location
        //  4. first found (sorted for determinism)
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
        if (isWindows()) {
            System.err.println("Create a directory and add it to your user PATH, for example:");
            System.err.println("  mkdir %USERPROFILE%\\bin");
            System.err.println("  Then add %USERPROFILE%\\bin to your user PATH via System Properties.");
        } else {
            System.err.println("Create a directory and add it to your PATH, for example:");
            System.err.println("  mkdir -p " + home.resolve(".local/bin"));
            System.err.println("  export PATH=\"" + home.resolve(".local/bin") + ":$PATH\"");
            System.err.println("  # add that line to ~/.profile or your shell rc");
        }
        System.err.println();
        System.err.println("Then re-run this installer.");
        System.exit(1);
    }

    // ---- launchers (sibling layout: resolve jar next to the launcher itself) ----

    private Path writeLauncher(Path installDir) throws IOException {
        if (isWindows()) {
            Path launcher = installDir.resolve(APP_NAME + ".cmd");
            Files.writeString(launcher, windowsLauncher(), StandardCharsets.UTF_8);
            return launcher;
        }

        Path launcher = installDir.resolve(APP_NAME);
        Files.writeString(launcher, unixLauncher(), StandardCharsets.UTF_8);
        makeExecutable(launcher);
        return launcher;
    }

    private String unixLauncher() {
        return "#!/usr/bin/env sh\n"
            + "set -eu\n\n"
            + "dir=$(cd \"$(dirname \"$0\")\" && pwd)\n\n"
            + "if [ -n \"${JAVA_HOME:-}\" ] && [ -x \"$JAVA_HOME/bin/java\" ]; then\n"
            + "  java_cmd=\"$JAVA_HOME/bin/java\"\n"
            + "elif command -v java >/dev/null 2>&1; then\n"
            + "  java_cmd=\"$(command -v java)\"\n"
            + "else\n"
            + "  echo \"oqx requires Java on PATH or JAVA_HOME\" >&2\n"
            + "  exit 1\n"
            + "fi\n\n"
            + "exec \"$java_cmd\" -jar \"$dir/" + APP_NAME + ".jar\" \"$@\"\n";
    }

    private String windowsLauncher() {
        return "@echo off\r\n"
            + "setlocal\r\n"
            + "set \"DIR=%~dp0\"\r\n"
            + "if defined JAVA_HOME if exist \"%JAVA_HOME%\\bin\\java.exe\" set \"JAVA_CMD=%JAVA_HOME%\\bin\\java.exe\"\r\n"
            + "if not defined JAVA_CMD (\r\n"
            + "  where java >nul 2>nul\r\n"
            + "  if errorlevel 1 (\r\n"
            + "    echo oqx requires Java on PATH or JAVA_HOME 1>&2\r\n"
            + "    exit /b 1\r\n"
            + "  )\r\n"
            + "  set \"JAVA_CMD=java\"\r\n"
            + ")\r\n"
            + "\"%JAVA_CMD%\" -jar \"%DIR%" + APP_NAME + ".jar\" %*\r\n";
    }

    private void makeExecutable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem; best effort only.
        }
    }

    // ---- PATH checks ----

    private boolean isOnPath(Path dir) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }

        Path candidate = dir.toAbsolutePath().normalize();
        for (String part : path.split(Pattern.quote(File.pathSeparator))) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                Path pathEntry = Paths.get(part).toAbsolutePath().normalize();
                if (samePath(candidate, pathEntry)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Skip malformed PATH entries.
            }
        }
        return false;
    }

    private boolean samePath(Path a, Path b) {
        String left = a.toString();
        String right = b.toString();
        if (isWindows()) {
            return left.equalsIgnoreCase(right);
        }
        return left.equals(right);
    }

    private void printPathHint(Path installDir) {
        System.out.println();
        System.out.println("Note: " + installDir + " is not currently on your PATH.");
        if (isWindows()) {
            System.out.println("Add it to your user PATH, then open a new terminal.");
        } else {
            System.out.println("Add this to your shell profile:");
            System.out.println("  export PATH=\"" + installDir + ":$PATH\"");
        }
    }

    // ---- utilities ----

    private void recreateDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            deleteRecursively(dir);
        }
        Files.createDirectories(dir);
    }

    private void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(stream.collect(Collectors.toList()));
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class Options {
        private boolean help;
        private Path installDir;
    }
}
