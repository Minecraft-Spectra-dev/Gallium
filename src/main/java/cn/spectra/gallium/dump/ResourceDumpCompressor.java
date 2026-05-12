package cn.spectra.gallium.dump;

import cn.spectra.gallium.Gallium;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ResourceDumpCompressor {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/**
	 * 压缩最新的资源转储文件
	 */
	public static void compressLatestDump() {
		Minecraft client = Minecraft.getInstance();
		Path gameDir = client.gameDirectory.toPath().toAbsolutePath();
		Path debugDir = TextureUtil.getDebugTexturePath(gameDir);
		Path outputDir = gameDir.resolve("gallium").resolve("dumps");

		if (!Files.exists(debugDir)) {
			Gallium.LOGGER.warn("Debug texture directory does not exist: {}", debugDir);
			return;
		}

		try {
			// 检查是否有转储文件
			boolean hasFiles;
			try (Stream<Path> walk = Files.walk(debugDir)) {
				hasFiles = walk.filter(Files::isRegularFile).findAny().isPresent();
			}
			if (!hasFiles) {
				Gallium.LOGGER.warn("No dump files found in: {}", debugDir);
				return;
			}

			// 创建输出目录
			Files.createDirectories(outputDir);

			// 生成带时间戳的 zip 文件名
			String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
			Path zipPath = outputDir.resolve("dump-" + timestamp + ".zip");

			// 压缩
			compressDirectory(debugDir, zipPath);

			Gallium.LOGGER.info("Resource dump compressed to: {}", zipPath);
		} catch (IOException e) {
			Gallium.LOGGER.error("Failed to compress resource dump", e);
		}
	}

	/**
	 * 将目录压缩为 zip 文件
	 */
	private static void compressDirectory(Path sourceDir, Path zipPath) throws IOException {
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath));
			 Stream<Path> walk = Files.walk(sourceDir)) {
			walk.filter(path -> !Files.isDirectory(path))
				.forEach(path -> {
					ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString().replace('\\', '/'));
					try {
						zos.putNextEntry(zipEntry);
						Files.copy(path, zos);
						zos.closeEntry();
					} catch (IOException e) {
						Gallium.LOGGER.error("Failed to add file to zip: {}", path, e);
					}
				});
		}
	}
}
