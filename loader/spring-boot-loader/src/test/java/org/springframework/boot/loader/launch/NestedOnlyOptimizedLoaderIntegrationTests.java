/*
 * Copyright 2012-present the original author or authors.
 */

package org.springframework.boot.loader.launch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.loader.net.protocol.Handlers;
import org.springframework.boot.loader.testsupport.TestJar;
import org.springframework.boot.loader.zip.AssertFileChannelDataBlocksClosed;
import org.springframework.core.test.tools.SourceFile;
import org.springframework.core.test.tools.SourceFiles;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.util.function.ThrowingConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link LaunchedClassLoader} when all classpath entries are nested
 * jars, which enables the optimized nested-only class loading path.
 */
@AssertFileChannelDataBlocksClosed
class NestedOnlyOptimizedLoaderIntegrationTests {

	private static final String FIXTURE_PACKAGE = "com.example.fixture";

	private static final String APP = FIXTURE_PACKAGE + ".App";

	private static final String TARGET = FIXTURE_PACKAGE + ".Target";

	private static final String PARALLEL_ONE = FIXTURE_PACKAGE + ".ParallelOne";

	private static final String PARALLEL_TWO = FIXTURE_PACKAGE + ".ParallelTwo";

	private static final String VERSIONED = FIXTURE_PACKAGE + ".Versioned";

	private static final String ROOT = "Root";

	private static final String RESOURCE = "com/example/fixture/resource.txt";

	@TempDir
	File tempDir;

	private final Map<String, byte[]> classes = compileFixtures();

	private File archive;

	@BeforeAll
	static void registerHandlers() {
		Handlers.register();
	}

	@BeforeEach
	void buildArchive() throws IOException {
		this.archive = buildArchive(this.classes);
	}

	@Test
	void loadsClassFromLaterNestedJarWhenEarlierJarContainsOnlyThePackage() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			assertThat(loader.getURLs()).allMatch((url) -> url.toString().startsWith("jar:nested:"));
			Class<?> target = loader.loadClass(TARGET);
			assertThat(codeSource(target)).endsWith("BOOT-INF/lib/b-target.jar!/");
		}
	}

	@Test
	void duplicateClassResolvesToFirstNestedJar() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			Class<?> target = loader.loadClass(TARGET);
			assertThat(target.getPackage().getImplementationVersion()).isEqualTo("1.0");
			assertThat(codeSource(target)).endsWith("BOOT-INF/lib/b-target.jar!/");
		}
	}

	@Test
	void loadsClassFromBootInfClasses() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			Class<?> app = loader.loadClass(APP);
			assertThat(codeSource(app)).endsWith("BOOT-INF/classes/!/");
		}
	}

	@Test
	void loadsClassInDefaultPackage() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			Class<?> root = loader.loadClass(ROOT);
			assertThat(codeSource(root)).endsWith("BOOT-INF/classes/!/");
		}
	}

	@Test
	void loadsClassWhenPackageOnlyExistsInVersionedEntry() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			Class<?> versioned = loader.loadClass(VERSIONED);
			assertThat(codeSource(versioned)).endsWith("BOOT-INF/lib/e-multi-release.jar!/");
		}
	}

	@Test
	void findsResourcesInNestedJarOrder() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			List<String> resources = Collections.list(loader.getResources(RESOURCE))
				.stream()
				.map(URL::toString)
				.toList();
			assertThat(resources).hasSize(2);
			assertThat(resources.get(0)).endsWith("BOOT-INF/lib/a-resource-only.jar!/" + RESOURCE);
			assertThat(resources.get(1)).endsWith("BOOT-INF/lib/b-target.jar!/" + RESOURCE);
		}
	}

	@Test
	void clearsCachesAndContinuesLoadingClasses() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			assertThat(loader.loadClass(TARGET)).isNotNull();
			loader.clearCache();
			Class<?> parallel = loader.loadClass(PARALLEL_ONE);
			assertThat(codeSource(parallel)).endsWith("BOOT-INF/lib/d-parallel.jar!/");
			assertThat(loader.getResource(RESOURCE)).isNotNull();
		}
	}

	@Test
	void loadsClassesInParallelFromSamePackage() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try (LaunchedClassLoader loader = createClassLoader()) {
			CountDownLatch start = new CountDownLatch(1);
			Future<Class<?>> first = executor.submit(() -> {
				start.await();
				return loader.loadClass(PARALLEL_ONE);
			});
			Future<Class<?>> second = executor.submit(() -> {
				start.await();
				return loader.loadClass(PARALLEL_TWO);
			});
			start.countDown();
			assertThat(first.get()).isNotNull();
			assertThat(second.get()).isNotNull();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void missingClassThrowsClassNotFoundException() throws Exception {
		try (LaunchedClassLoader loader = createClassLoader()) {
			assertThatExceptionOfType(ClassNotFoundException.class)
				.isThrownBy(() -> loader.loadClass(FIXTURE_PACKAGE + ".Missing"));
		}
	}

	private LaunchedClassLoader createClassLoader() throws Exception {
		try (JarFileArchive archive = new JarFileArchive(this.archive)) {
			JarLauncher launcher = new JarLauncher(archive);
			return (LaunchedClassLoader) launcher.createClassLoader(launcher.getClassPathUrls());
		}
	}

	private String codeSource(Class<?> type) {
		return type.getProtectionDomain().getCodeSource().getLocation().toString();
	}

	private Map<String, byte[]> compileFixtures() {
		Map<String, byte[]> compiledClasses = new HashMap<>();
		SourceFile target = sourceFile("Target");
		SourceFile app = sourceFile("App");
		SourceFile parallelOne = sourceFile("ParallelOne");
		SourceFile parallelTwo = sourceFile("ParallelTwo");
		SourceFile versioned = sourceFile("Versioned");
		SourceFile root = SourceFile.of("Root.java", "public class Root { }");
		TestCompiler.forSystem()
			.compile(SourceFiles.of(target, app, parallelOne, parallelTwo, versioned, root),
					ThrowingConsumer.of((compiled) -> {
						readClass(compiled.getClassLoader(), TARGET, compiledClasses);
						readClass(compiled.getClassLoader(), APP, compiledClasses);
						readClass(compiled.getClassLoader(), PARALLEL_ONE, compiledClasses);
						readClass(compiled.getClassLoader(), PARALLEL_TWO, compiledClasses);
						readClass(compiled.getClassLoader(), VERSIONED, compiledClasses);
						readClass(compiled.getClassLoader(), ROOT, compiledClasses);
					}));
		return compiledClasses;
	}

	private SourceFile sourceFile(String simpleName) {
		String className = FIXTURE_PACKAGE + "." + simpleName;
		return SourceFile.of(className.replace('.', '/') + ".java",
				"package %s; public class %s { }".formatted(FIXTURE_PACKAGE, simpleName));
	}

	private void readClass(ClassLoader classLoader, String className, Map<String, byte[]> compiledClasses)
			throws IOException {
		try (InputStream inputStream = classLoader.getResourceAsStream(className.replace('.', '/') + ".class")) {
			assertThat(inputStream).isNotNull();
			compiledClasses.put(className, inputStream.readAllBytes());
		}
	}

	private File buildArchive(Map<String, byte[]> compiledClasses) throws IOException {
		File archive = new File(this.tempDir, "archive.jar");
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(archive))) {
			writeManifest(out, newManifest(null));
			out.putNextEntry(new JarEntry("BOOT-INF/"));
			out.closeEntry();
			out.putNextEntry(new JarEntry("BOOT-INF/classes/"));
			out.closeEntry();
			writeEntry(out, "BOOT-INF/classes/" + entryName(APP), compiledClasses.get(APP));
			writeEntry(out, "BOOT-INF/classes/" + entryName(ROOT), compiledClasses.get(ROOT));
			out.putNextEntry(new JarEntry("BOOT-INF/lib/"));
			out.closeEntry();
			writeNestedJar(out, "BOOT-INF/lib/a-resource-only.jar",
					createJar(newManifest(null), Map.of(RESOURCE, "a".getBytes(StandardCharsets.UTF_8))));
			writeNestedJar(out, "BOOT-INF/lib/b-target.jar", createJar(newManifest("1.0"), Map.of(entryName(TARGET),
					compiledClasses.get(TARGET), RESOURCE, "b".getBytes(StandardCharsets.UTF_8))));
			writeNestedJar(out, "BOOT-INF/lib/c-duplicate.jar",
					createJar(newManifest("2.0"), Map.of(entryName(TARGET), compiledClasses.get(TARGET))));
			writeNestedJar(out, "BOOT-INF/lib/d-parallel.jar",
					createJar(newManifest(null), Map.of(entryName(PARALLEL_ONE), compiledClasses.get(PARALLEL_ONE),
							entryName(PARALLEL_TWO), compiledClasses.get(PARALLEL_TWO))));
			writeNestedJar(out, "BOOT-INF/lib/e-multi-release.jar", createMultiReleaseJar(compiledClasses));
		}
		return archive;
	}

	private byte[] createJar(Manifest manifest, Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		try (JarOutputStream out = new JarOutputStream(data, manifest)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				writeEntry(out, entry.getKey(), entry.getValue());
			}
		}
		return data.toByteArray();
	}

	private byte[] createMultiReleaseJar(Map<String, byte[]> compiledClasses) throws IOException {
		Manifest manifest = newManifest(null);
		manifest.getMainAttributes().putValue("Multi-Release", "true");
		String entryName = "META-INF/versions/%d/%s".formatted(TestJar.MULTI_JAR_VERSION, entryName(VERSIONED));
		return createJar(manifest, Map.of(entryName, compiledClasses.get(VERSIONED)));
	}

	private Manifest newManifest(@Nullable String implementationVersion) {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (implementationVersion != null) {
			manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
		}
		return manifest;
	}

	private void writeManifest(JarOutputStream out, Manifest manifest) throws IOException {
		out.putNextEntry(new JarEntry("META-INF/"));
		out.closeEntry();
		out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
		manifest.write(out);
		out.closeEntry();
	}

	private void writeEntry(JarOutputStream out, String name, byte[] data) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(data);
		out.closeEntry();
	}

	private void writeNestedJar(JarOutputStream out, String name, byte[] data) throws IOException {
		JarEntry entry = new JarEntry(name);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(data.length);
		entry.setCompressedSize(data.length);
		CRC32 crc = new CRC32();
		crc.update(data);
		entry.setCrc(crc.getValue());
		out.putNextEntry(entry);
		out.write(data);
		out.closeEntry();
	}

	private String entryName(String className) {
		return className.replace('.', '/') + ".class";
	}

}
