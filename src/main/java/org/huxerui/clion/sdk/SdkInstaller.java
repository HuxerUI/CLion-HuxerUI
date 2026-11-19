package org.huxerui.clion.sdk;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.util.io.HttpRequests;
import com.intellij.util.io.RequestBuilder;
import org.huxerui.clion.settings.HuxerUISettings;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SdkInstaller {
    private static final String releases = "https://github.com/HuxerUI/HuxerUI/releases";

    public Path InstallLatest(ProgressIndicator indicator) throws IOException {
        return Install("", indicator);
    }

    public Path Install(String requested_version, ProgressIndicator indicator) throws IOException {
        return Install(requested_version, HuxerUISettings.InstallDirectory(), indicator);
    }

    Path Install(String requested_version, Path sdk_root, ProgressIndicator indicator) throws IOException {
        if (!requested_version.isEmpty() && !requested_version.matches("v?\\d+\\.\\d+\\.\\d+")) {
            throw new IOException("Enter an exact release version, for example 0.5.0");
        }
        indicator.setText("Reading the HuxerUI release");
        String tag = requested_version.isEmpty() ? LatestReleaseTag() : "v" + requested_version.replaceFirst("^v", "");
        String version = tag.substring(1);
        if (!HuxerUISdk.SupportsVersion(version)) {
            String release_label = requested_version.isEmpty() ? "The latest HuxerUI release is "
                    : "The requested HuxerUI release is ";
            throw new IOException(release_label + tag + ". Use SDK 0.5.0 or newer, or select a source checkout.");
        }
        Path destination = sdk_root.resolve(tag);
        if (IsUsableSdk(destination)) {
            return destination;
        }
        Files.createDirectories(sdk_root);
        if (Files.exists(destination)) {
            throw new IOException("SDK destination already exists but is incomplete: " + destination);
        }
        HostPlatform host = HostPlatform.Current();
        String asset = host.SdkArchiveName(version);
        String download_url = releases + "/download/" + tag + "/" + asset;
        String digest = ReadDigest(download_url, asset, indicator);
        Path archive = Files.createTempFile(sdk_root, ".download-", host.archive_suffix());
        Path staging = sdk_root.resolve(".install-" + UUID.randomUUID());
        try {
            indicator.setText("Downloading HuxerUI SDK " + version);
            Download(download_url, archive, indicator);
            VerifyDigest(archive, digest);
            indicator.setText("Installing HuxerUI SDK " + version);
            Files.createDirectories(staging);
            if (asset.endsWith(".zip")) {
                ExtractZip(archive, staging, indicator);
            } else {
                ExtractTarGz(archive, staging, indicator);
            }
            Path extracted_home = FindSdkHome(staging);
            MakeCliExecutable(extracted_home);
            if (!IsUsableSdk(extracted_home)) {
                throw new IOException("Installed archive does not contain the standard HUXERUI_HOME layout");
            }
            try {
                Move(extracted_home, destination);
            } catch (java.nio.file.FileAlreadyExistsException error) {
                if (!IsUsableSdk(destination)) {
                    throw error;
                }
            }
            return destination;
        } finally {
            Files.deleteIfExists(archive);
            if (Files.exists(staging)) {
                DeleteTree(staging);
            }
        }
    }

    private static String LatestReleaseTag() throws IOException {
        String url = ReleaseRequest(HttpRequests.head(releases + "/latest")).connect(
                request -> request.getConnection().getURL().toExternalForm());
        String prefix = releases + "/tag/";
        if (!url.startsWith(prefix)) {
            throw new IOException("Could not find the latest HuxerUI release.");
        }
        String tag = url.substring(prefix.length());
        if (!tag.matches("v\\d+\\.\\d+\\.\\d+")) {
            throw new IOException("Unexpected HuxerUI release tag: " + tag);
        }
        return tag;
    }

    private static String ReadDigest(String url, String asset, ProgressIndicator indicator) throws IOException {
        String body;
        try {
            body = ReleaseRequest(HttpRequests.request(url + ".sha256")).readString(indicator);
        } catch (HttpRequests.HttpStatusException error) {
            if (error.getStatusCode() == 404) {
                throw new IOException("The SDK or its SHA-256 file is missing from the release: " + asset, error);
            }
            throw error;
        }
        String[] fields = body.strip().split("\\s+", 2);
        if (fields.length != 2 || !fields[0].matches("[0-9a-fA-F]{64}")
                || !fields[1].replaceFirst("^\\*", "").equals(asset)) {
            throw new IOException("Invalid SHA-256 file for " + asset);
        }
        return fields[0];
    }

    private static RequestBuilder ReleaseRequest(RequestBuilder request) {
        return request.useProxy(true).followRedirects(true).connectTimeout(20_000)
                .readTimeout(30_000).userAgent("HuxerUI-CLion-Plugin");
    }

    private static void Download(String url, Path destination, ProgressIndicator indicator) throws IOException {
        ReleaseRequest(HttpRequests.request(url))
                .readTimeout(600_000)
                .accept("application/octet-stream")
                .saveToFile(destination, indicator);
    }

    private static void VerifyDigest(Path archive, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SDK SHA-256 mismatch: expected " + expected + ", received " + actual);
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void ExtractZip(Path archive, Path destination, ProgressIndicator indicator) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                indicator.checkCanceled();
                Path output = SafeOutput(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void ExtractTarGz(Path archive, Path destination, ProgressIndicator indicator) throws IOException {
        try (InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            while (ReadFully(input, header)) {
                indicator.checkCanceled();
                if (AllZero(header)) {
                    return;
                }
                String name = TarText(header, 0, 100);
                String prefix = TarText(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = TarOctal(header, 124, 12);
                int type = header[156] & 0xff;
                Path output = SafeOutput(destination, name);
                if (type == '5') {
                    Files.createDirectories(output);
                } else if (type == 0 || type == '0') {
                    Files.createDirectories(output.getParent());
                    try (var file = Files.newOutputStream(output)) {
                        CopyExactly(input, file, size);
                    }
                    if ((TarOctal(header, 100, 8) & 0111) != 0) {
                        MakeExecutable(output);
                    }
                } else {
                    SkipExactly(input, size);
                }
                long padding = (512 - (size % 512)) % 512;
                SkipExactly(input, padding);
            }
        }
    }

    private static Path FindSdkHome(Path staging) throws IOException {
        if (IsUsableSdk(staging)) {
            return staging;
        }
        try (var children = Files.list(staging)) {
            List<Path> directories = children.filter(Files::isDirectory).toList();
            if (directories.size() == 1 && IsUsableSdk(directories.getFirst())) {
                return directories.getFirst();
            }
        }
        throw new IOException("SDK archive does not contain a standard HUXERUI_HOME directory");
    }

    private static boolean IsUsableSdk(Path home) {
        return HuxerUISettings.SdkLayout.IsValid(home) && HuxerUISettings.SdkLayout.FindCli(home) != null;
    }

    private static void MakeCliExecutable(Path home) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        Path cli = HuxerUISettings.SdkLayout.FindCli(home);
        if (cli == null) {
            return;
        }
        MakeExecutable(cli);
    }

    private static void MakeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!file.toFile().setExecutable(true, false)) {
                throw new IOException("Unable to make the SDK file executable: " + file);
            }
        }
    }

    private static Path SafeOutput(Path root, String name) throws IOException {
        Path output = root.resolve(name.replace('\\', '/')).normalize();
        if (!output.startsWith(root)) {
            throw new IOException("SDK archive contains an unsafe path: " + name);
        }
        return output;
    }

    private static void Move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private static void DeleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean ReadFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                return offset == 0 ? false : ThrowTruncated();
            }
            offset += count;
        }
        return true;
    }

    private static boolean ThrowTruncated() throws IOException {
        throw new IOException("SDK tar archive is truncated");
    }

    private static boolean AllZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String TarText(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            ++end;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long TarOctal(byte[] header, int offset, int length) throws IOException {
        String value = TarText(header, offset, length).trim();
        try {
            return value.isEmpty() ? 0 : Long.parseLong(value, 8);
        } catch (NumberFormatException error) {
            throw new IOException("SDK tar archive contains an invalid size", error);
        }
    }

    private static void CopyExactly(InputStream input, java.io.OutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = bytes;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("SDK tar archive is truncated");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void SkipExactly(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) {
                    throw new IOException("SDK tar archive is truncated");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
