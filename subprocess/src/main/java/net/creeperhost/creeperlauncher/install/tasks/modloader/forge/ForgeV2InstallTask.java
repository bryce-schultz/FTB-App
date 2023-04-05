package net.creeperhost.creeperlauncher.install.tasks.modloader.forge;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.jdkutils.JavaInstall;
import net.covers1624.jdkutils.JavaVersion;
import net.covers1624.quack.gson.JsonUtils;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;
import net.creeperhost.creeperlauncher.Constants;
import net.creeperhost.creeperlauncher.data.forge.installerv2.InstallManifest;
import net.creeperhost.creeperlauncher.install.tasks.NewDownloadTask;
import net.creeperhost.creeperlauncher.install.tasks.TaskProgressListener;
import net.creeperhost.creeperlauncher.minecraft.jsons.VersionManifest;
import net.creeperhost.creeperlauncher.pack.CancellationToken;
import net.creeperhost.creeperlauncher.pack.Instance;
import net.creeperhost.creeperlauncher.util.StreamGobblerLog;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by covers1624 on 14/1/22.
 */
@SuppressWarnings ("UnstableApiUsage")
public class ForgeV2InstallTask extends AbstractForgeInstallTask {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Instance instance;
    private final Path installerJar;

    public ForgeV2InstallTask(Instance instance, Path installerJar) {
        this.instance = instance;
        this.installerJar = installerJar;
    }

    @Override
    public void execute(@Nullable CancellationToken cancelToken, @Nullable TaskProgressListener listener) throws Throwable {
        Path versionsDir = Constants.BIN_LOCATION.resolve("versions");
        Path librariesDir = Constants.BIN_LOCATION.resolve("libraries");

        try (FileSystem fs = IOUtils.getJarFileSystem(installerJar, true)) {
            Path installerRoot = fs.getPath("/");
            // Parse the installer manifest.
            InstallManifest instManifest = JsonUtils.parse(InstallManifest.GSON, installerRoot.resolve("install_profile.json"), InstallManifest.class);
            VersionManifest forgeManifest = JsonUtils.parse(VersionManifest.GSON, installerRoot.resolve(instManifest.json), VersionManifest.class);

            // Install the Version Manifest.
            versionName = instManifest.version;
            Files.copy(
                    installerRoot.resolve(instManifest.json),
                    IOUtils.makeParents(versionsDir.resolve(instManifest.version).resolve(instManifest.version + ".json")),
                    StandardCopyOption.REPLACE_EXISTING
            );
            VersionManifest vanillaManifest = downloadVanilla(versionsDir, instManifest.minecraft);

            List<VersionManifest.Library> libraries = new LinkedList<>();
            libraries.addAll(forgeManifest.libraries);
            libraries.addAll(instManifest.libraries);

            for (VersionManifest.Library library : libraries) {
                if (cancelToken != null) cancelToken.throwIfCancelled();
                processLibrary(cancelToken, installerRoot, librariesDir, library);
            }

            runProcessors(cancelToken, instManifest, vanillaManifest, installerRoot, librariesDir, versionsDir);

            ForgeLegacyLibraryHelper.installLegacyLibs(cancelToken, instance, instManifest.minecraft);
        }
    }

    private void runProcessors(@Nullable CancellationToken cancelToken, InstallManifest manifest, VersionManifest vanillaManifest, Path installerRoot, Path librariesDir, Path versionsDir) throws IOException {
        String javaTarget = instance.versionManifest.getTargetVersion("runtime");
        Path javaHome;
        if (javaTarget == null) {
            javaHome = Constants.getJdkManager().provisionJdk(vanillaManifest.getJavaVersionOrDefault(JavaVersion.JAVA_1_8), null, true, null);
        } else {
            javaHome = Constants.getJdkManager().provisionJdk(javaTarget, true, null);
        }
        Path javaExecutable = JavaInstall.getJavaExecutable(javaHome, true);

        Path tempDir = Files.createTempDirectory("forge_installer_");
        tempDir.toFile().deleteOnExit();
        Map<String, String> data = new HashMap<>();
        for (Map.Entry<String, InstallManifest.DataEntry> entry : manifest.data.entrySet()) {
            String value = entry.getValue().client;
            if (surroundedBy(value, '[', ']')) { // Artifact
                value = MavenNotation.parse(topAndTail(value)).toPath(librariesDir).toAbsolutePath().toString();
            } else if (surroundedBy(value, '\'', '\'')) { // Literal
                value = topAndTail(value);
            } else {
                Path extracted = tempDir.resolve(StringUtils.removeStart(value, "/"));
                Files.copy(installerRoot.resolve(value), IOUtils.makeParents(extracted), StandardCopyOption.REPLACE_EXISTING);
                value = extracted.toAbsolutePath().toString();
            }
            data.put(entry.getKey(), value); // Hard coded to client.
        }

        data.put("SIDE", "client");
        data.put("MINECRAFT_JAR", versionsDir.resolve(vanillaManifest.id).resolve(vanillaManifest.id + ".jar").toAbsolutePath().toString());
        data.put("MINECRAFT_VERSION", vanillaManifest.id);
        data.put("ROOT", Constants.BIN_LOCATION.toAbsolutePath().toString());
        data.put("INSTALLER", installerJar.toAbsolutePath().toString());
        data.put("LIBRARY_DIR", librariesDir.toAbsolutePath().toString());

        for (InstallManifest.Processor processor : manifest.processors) {
            if (cancelToken != null) cancelToken.throwIfCancelled();
            if (processor.sides.isEmpty() || processor.sides.contains("client")) {
                runProcessor(cancelToken, vanillaManifest, processor, data, javaExecutable, librariesDir);
            }
        }
    }

    private void runProcessor(@Nullable CancellationToken cancelToken, VersionManifest vanillaManifest, InstallManifest.Processor processor, Map<String, String> data, Path javaExecutable, Path librariesDir) throws IOException {
        Map<Path, String> outputs = new HashMap<>();

        boolean cached = !processor.outputs.isEmpty();
        for (Map.Entry<String, String> entry : processor.outputs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (surroundedBy(key, '[', ']')) {
                key = MavenNotation.parse(topAndTail(key)).toPath(librariesDir).toAbsolutePath().toString();
            } else {
                key = replaceTokens(data, key);
            }

            if (value != null) {
                value = replaceTokens(data, value);
            }

            Path output = Path.of(key);
            outputs.put(output, value);

            if (Files.notExists(output)) {
                cached = false;
                continue;
            }
            HashCode hash = HashUtils.hash(Hashing.sha1(), output);
            if (!HashUtils.equals(hash, value)) {
                LOGGER.warn("Output '{}' failed to validate, it will be deleted.", output);
                LOGGER.warn(" Expected: {}", value);
                LOGGER.warn(" Got     : {}", hash);
                Files.delete(output);
                cached = false;
            } else {
                LOGGER.info("Output '{}' Validated: {}", output, hash);
            }
        }
        if (cached) {
            LOGGER.info("Cache validated, Skipping processor.");
            return;
        }

        List<String> args = new ArrayList<>(processor.args.size());
        for (String arg : processor.args) {
            if (surroundedBy(arg, '[', ']')) {
                args.add(MavenNotation.parse(topAndTail(arg)).toPath(librariesDir).toAbsolutePath().toString());
            } else {
                args.add(replaceTokens(data, arg));
            }
        }
        // Do custom stuff for DOWNLOAD_MOJMAPS as this bypasses our proxy settings, etc.
        if (args.size() > 2 && args.get(0).equals("--task") && args.get(1).equals("DOWNLOAD_MOJMAPS")) {
            if (downloadMojMaps(cancelToken, vanillaManifest, args.subList(2, args.size()))) {
                return;
            }
        }

        Path jar = processor.jar.toPath(librariesDir);
        List<Path> classpath = new ArrayList<>(processor.classpath.size() + 2);
        classpath.add(jar);
        for (MavenNotation mavenNotation : processor.classpath) {
            Path path = mavenNotation.toPath(librariesDir);
            classpath.add(path);
        }
        // Theoretically not needed, but mimics the classloading structure of the Forge installer properly
        classpath.add(installerJar);
        for (Path path : classpath) {
            if (!Files.exists(path)) {
                LOGGER.error("Processor classpath entry does not exist: {}", path);
                throw new IOException("Processor classpath entry does not exist: " + path);
            }
        }

        List<String> command = new ArrayList<>(5 + args.size());
        command.add(javaExecutable.toAbsolutePath().toString());
        command.add("-cp");
        command.add(classpath.stream().map(e -> e.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator)));
        command.add(getMainClass(jar));
        command.add(jar.toAbsolutePath().toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder()
                .directory(Constants.BIN_LOCATION.toFile())
                .command(command);
        LOGGER.info("Starting processor with command '{}'", String.join(" ", builder.command()));
        try {
            Process process = builder.start();
            StreamGobblerLog stdoutGobbler = new StreamGobblerLog()
                    .setInput(process.getInputStream())
                    .setOutput(LOGGER::info);
            stdoutGobbler.start();
            StreamGobblerLog stderrGobbler = new StreamGobblerLog()
                    .setInput(process.getErrorStream())
                    .setOutput(LOGGER::warn);
            stderrGobbler.start();

            process.onExit().thenRunAsync(() -> {
                stdoutGobbler.stop();
                stderrGobbler.stop();
            });
            while (process.isAlive()) {
                try {
                    process.waitFor();
                } catch (InterruptedException ignored) {
                }
            }
            int exit = process.exitValue();
            if (exit != 0) {
                LOGGER.error("Processor process exited with non zero exit status. {}", exit);
                throw new IOException("Processor process exited with non zero exit status code. " + exit);
            }
        } catch (IOException ex) {
            LOGGER.error("Failed to run processor.", ex);
            throw ex;
        }

        boolean validated = true;
        for (Map.Entry<Path, String> entry : outputs.entrySet()) {
            Path output = entry.getKey();
            String value = entry.getValue();
            if (Files.notExists(output)) {
                LOGGER.error("Output '{}' does not exist.", output);
                validated = false;
            } else {
                HashCode hash = HashUtils.hash(Hashing.sha1(), output);
                if (!HashUtils.equals(hash, value)) {
                    LOGGER.warn("Output '{}' failed to validate.", output);
                    LOGGER.warn(" Expected: {}", value);
                    LOGGER.warn(" Got     : {}", hash);
                    validated = false;
                } else {
                    LOGGER.info("Output '{}' Validated: {}", output, hash);
                }
            }
        }
        if (!validated) {
            LOGGER.error("Processor output validation errors occurred.");
            throw new IOException("Processor output validation errors occurred.");
        }
    }

    private boolean downloadMojMaps(@Nullable CancellationToken cancelToken, VersionManifest vanillaManifest, List<String> args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<String> versionOpt = parser.accepts("version").withRequiredArg().ofType(String.class).required();
        OptionSpec<String> sideOpt = parser.accepts("side").withRequiredArg().ofType(String.class).required();
        OptionSpec<Path> outputOpt = parser.accepts("output").withRequiredArg().withValuesConvertedBy(new PathConverter()).required();

        OptionSet optSet;
        try {
            optSet = parser.parse(args.toArray(new String[0]));
        } catch (OptionException ex) {
            LOGGER.warn("Failed to parse DOWNLOAD_MOJMAPS args. Falling back to InstallerTools invoke. Args: {}", args, ex);
            return false;
        }
        String version = optSet.valueOf(versionOpt);
        String side = optSet.valueOf(sideOpt);
        Path output = optSet.valueOf(outputOpt);
        if (!vanillaManifest.id.equals(version)) {
            LOGGER.warn("DOWNLOAD_MOJMAPS uses different minecraft version? Args: {}. Expected mc: {}", args, vanillaManifest.id);
            return false;
        }


        NewDownloadTask task = vanillaManifest.getDownload(side + "_mappings", output);
        if (task == null) {
            LOGGER.warn("Failed to find {}_mappings download. Falling back to InstallerTools invoke.", side);
            return false;
        }
        task.execute(cancelToken, null);
        return true;
    }
}
