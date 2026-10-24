/*
 * Copyright 2012-present the original author or authors.
 */

package org.springframework.boot.loader.net.protocol.jar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.loader.net.protocol.Handlers;
import org.springframework.boot.loader.testsupport.TestJar;
import org.springframework.boot.loader.zip.AssertFileChannelDataBlocksClosed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link JarUrlClassLoader}.
 *
 * @author Phillip Webb
 * @author Jan Mohr
 */
@AssertFileChannelDataBlocksClosed
class JarUrlClassLoaderTests {

	private static final File APP_JAR_FILE = new File("src/test/resources/jars/app.jar").getAbsoluteFile();

	private static final URL APP_JAR;
	static {
		try {
			APP_JAR = new URL("jar:" + APP_JAR_FILE.toURI().toURL() + "!/");
		}
		catch (MalformedURLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@TempDir
	File tempDir;

	@BeforeAll
	static void setup() {
		Handlers.register();
	}

	@Test
	void resolveResourceFromArchive() throws Exception {
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(APP_JAR)) {
			assertThat(loader.getResource("demo/Application.java")).isNotNull();
		}
	}

	@Test
	void resolveResourcesFromArchive() throws Exception {
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(APP_JAR)) {
			assertThat(loader.getResources("demo/Application.java").hasMoreElements()).isTrue();
		}
	}

	@Test
	void resolveRootPathFromArchive() throws Exception {
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(APP_JAR)) {
			assertThat(loader.getResource("")).isNotNull();
		}
	}

	@Test
	void resolveRootResourcesFromArchive() throws Exception {
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(APP_JAR)) {
			assertThat(loader.getResources("").hasMoreElements()).isTrue();
		}
	}

	@Test
	void resolveFromNested() throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		TestJar.create(jarFile);
		URL url = JarUrl.create(jarFile, "nested.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			URL resource = loader.getResource("3.dat");
			assertThat(resource).hasToString(url + "3.dat");
			try (InputStream input = resource.openConnection().getInputStream()) {
				assertThat(input.read()).isEqualTo(3);
			}
		}
	}

	@Test
	void resolveFromNestedWhenNameNeedsEncoding() throws Exception {
		ByteArrayOutputStream nestedJarData = new ByteArrayOutputStream();
		try (JarOutputStream jarOutputStream = new JarOutputStream(nestedJarData)) {
			jarOutputStream.putNextEntry(new JarEntry("dir with space/a#b.txt"));
			jarOutputStream.write(42);
			jarOutputStream.closeEntry();
		}
		File jarFile = new File(this.tempDir, "test.jar");
		try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile))) {
			writeStoredEntry(jarOutputStream, "nested.jar", nestedJarData.toByteArray());
		}
		URL url = JarUrl.create(jarFile, "nested.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			URL resource = loader.getResource("dir with space/a#b.txt");
			assertThat(resource).isNotNull();
			try (InputStream input = resource.openConnection().getInputStream()) {
				assertThat(input.read()).isEqualTo(42);
			}
		}
	}

	@Test
	void resolveFromNestedWhenMissingReturnsNull() throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		TestJar.create(jarFile);
		URL url = JarUrl.create(jarFile, "nested.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			assertThat(loader.getResource("missing.dat")).isNull();
			assertThat(loader.getResources("missing.dat").hasMoreElements()).isFalse();
		}
	}

	@Test
	void resolveResourcesFromMultipleNestedReturnsResourcesInOrder() throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		TestJar.create(jarFile);
		URL nestedUrl = JarUrl.create(jarFile, "nested.jar");
		URL anotherNestedUrl = JarUrl.create(jarFile, "another-nested.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(nestedUrl, anotherNestedUrl)) {
			assertThat(Collections.list(loader.getResources("3.dat"))).map(URL::toString)
				.containsExactly(nestedUrl + "3.dat", anotherNestedUrl + "3.dat");
		}
	}

	@Test
	void resolveResourcesFromMultipleNestedWhenUrlEndedReturnsResourcesInOrder() throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		TestJar.create(jarFile);
		URL nestedUrl = JarUrl.create(jarFile, "nested.jar");
		URL anotherNestedUrl = JarUrl.create(jarFile, "another-nested.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(nestedUrl, anotherNestedUrl)) {
			assertThat(Collections.list(loader.getResources("\u00E4.dat"))).map(URL::toString)
				.containsExactly(nestedUrl + "%c3%a4.dat", anotherNestedUrl + "%c3%a4.dat");
		}
	}

	@Test
	void resolveFromNestedWhenClassPathAlsoContainsOtherUrls() throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		TestJar.create(jarFile);
		URL url = JarUrl.create(jarFile, "nested.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(APP_JAR, url)) {
			assertThat(loader.getResource("3.dat")).hasToString(url + "3.dat");
			assertThat(loader.loadClass("demo.Application")).hasToString("class demo.Application");
		}
	}

	@Test
	void loadClassesFromMultipleNestedJars() throws Exception {
		File jarFile = createJarWithNestedJars(createNestedJar(null, "demo.Application"),
				createNestedJar(null, "app.Second"));
		URL firstUrl = JarUrl.create(jarFile, "first.jar");
		URL secondUrl = JarUrl.create(jarFile, "second.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(firstUrl, secondUrl)) {
			assertThat(loader.loadClass("demo.Application")).isNotNull();
			assertThat(loader.loadClass("app.Second")).isNotNull();
		}
	}

	@Test
	void loadClassFromLaterNestedJarWhenEarlierJarContainsThePackage() throws Exception {
		File jarFile = createJarWithNestedJars(createNestedJar("1.0", "demo.Second"),
				createNestedJar("2.0", "demo.Application", "demo.Second"));
		URL firstUrl = JarUrl.create(jarFile, "first.jar");
		URL secondUrl = JarUrl.create(jarFile, "second.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(firstUrl, secondUrl)) {
			Class<?> application = loader.loadClass("demo.Application");
			assertThat(codeSource(application)).isEqualTo(secondUrl.toString());
			Class<?> second = loader.loadClass("demo.Second");
			assertThat(codeSource(second)).isEqualTo(firstUrl.toString());
		}
	}

	@Test
	void loadClassWhenEarlierJarContainsPackageButNotClass() throws Exception {
		File jarFile = createJarWithNestedJars(createNestedJarWithEntries("demo/resource.txt"),
				createNestedJar(null, "demo.Application"));
		URL firstUrl = JarUrl.create(jarFile, "first.jar");
		URL secondUrl = JarUrl.create(jarFile, "second.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(firstUrl, secondUrl)) {
			Class<?> application = loader.loadClass("demo.Application");
			assertThat(codeSource(application)).isEqualTo(secondUrl.toString());
		}
	}

	@Test
	void loadClassInDefaultPackage() throws Exception {
		File jarFile = createJarWithNestedJars(createNestedJar(null, "app.Second"), createNestedJar(null, "Root"));
		URL firstUrl = JarUrl.create(jarFile, "first.jar");
		URL secondUrl = JarUrl.create(jarFile, "second.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(firstUrl, secondUrl)) {
			Class<?> root = loader.loadClass("Root");
			assertThat(codeSource(root)).isEqualTo(secondUrl.toString());
		}
	}

	@Test
	void loadClassAfterClearCache() throws Exception {
		File jarFile = createJarWithNestedJars(createNestedJar(null, "demo.Application", "demo.Second"),
				createNestedJar(null, "app.Second"));
		URL url = JarUrl.create(jarFile, "first.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			assertThat(loader.loadClass("demo.Application")).isNotNull();
			loader.clearCache();
			assertThat(loader.loadClass("demo.Second")).isNotNull();
		}
	}

	@Test
	void loadClassWhenPackageOnlyExistsInVersionedEntry() throws Exception {
		File jarFile = createJarWithNestedJars(createMultiReleaseNestedJar("demo.Versioned"),
				createNestedJar(null, "app.Second"));
		URL url = JarUrl.create(jarFile, "first.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			assertThat(loader.loadClass("demo.Versioned")).isNotNull();
		}
	}

	private String codeSource(Class<?> type) {
		return type.getProtectionDomain().getCodeSource().getLocation().toString();
	}

	private File createJarWithNestedJars(byte[] first, byte[] second) throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile))) {
			writeStoredEntry(jarOutputStream, "first.jar", first);
			writeStoredEntry(jarOutputStream, "second.jar", second);
		}
		return jarFile;
	}

	private byte[] createNestedJar(@Nullable String implementationVersion, String... classNames) throws Exception {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (implementationVersion != null) {
			manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
		}
		try (JarOutputStream jarOutputStream = new JarOutputStream(data, manifest)) {
			for (String className : classNames) {
				jarOutputStream.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
				jarOutputStream.write(compileClass(className));
				jarOutputStream.closeEntry();
			}
		}
		return data.toByteArray();
	}

	private byte[] createNestedJarWithEntries(String... entryNames) throws Exception {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		try (JarOutputStream jarOutputStream = new JarOutputStream(data, manifest)) {
			for (String entryName : entryNames) {
				jarOutputStream.putNextEntry(new JarEntry(entryName));
				jarOutputStream.write(42);
				jarOutputStream.closeEntry();
			}
		}
		return data.toByteArray();
	}

	private byte[] createMultiReleaseNestedJar(String className) throws Exception {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().putValue("Multi-Release", "true");
		try (JarOutputStream jarOutputStream = new JarOutputStream(data, manifest)) {
			String entryName = "META-INF/versions/%d/%s.class".formatted(TestJar.MULTI_JAR_VERSION,
					className.replace('.', '/'));
			jarOutputStream.putNextEntry(new JarEntry(entryName));
			jarOutputStream.write(compileClass(className));
			jarOutputStream.closeEntry();
		}
		return data.toByteArray();
	}

	private byte[] compileClass(String className) throws Exception {
		Path sourceDirectory = this.tempDir.toPath().resolve("source");
		Path outputDirectory = this.tempDir.toPath().resolve("classes");
		Path sourceFile = sourceDirectory.resolve(className.replace('.', '/') + ".java");
		Files.createDirectories(sourceFile.getParent());
		Files.createDirectories(outputDirectory);
		int lastDot = className.lastIndexOf('.');
		String source = (lastDot != -1) ? "package %s; public class %s {}".formatted(className.substring(0, lastDot),
				className.substring(lastDot + 1)) : "public class %s {}".formatted(className);
		Files.writeString(sourceFile, source);
		int result = ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "-d", outputDirectory.toString(), sourceFile.toString());
		assertThat(result).isZero();
		return Files.readAllBytes(outputDirectory.resolve(className.replace('.', '/') + ".class"));
	}

	@Test
	void loadClassFromNestedAfterMissingClassInSamePackageDefinesPackageFromManifest() throws Exception {
		byte[] classData;
		try (JarFile appJar = new JarFile(APP_JAR_FILE);
				InputStream inputStream = appJar.getInputStream(appJar.getEntry("demo/Application.class"))) {
			classData = inputStream.readAllBytes();
		}
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.2.3");
		ByteArrayOutputStream nestedJarData = new ByteArrayOutputStream();
		try (JarOutputStream jarOutputStream = new JarOutputStream(nestedJarData, manifest)) {
			jarOutputStream.putNextEntry(new JarEntry("demo/"));
			jarOutputStream.closeEntry();
			jarOutputStream.putNextEntry(new JarEntry("demo/Application.class"));
			jarOutputStream.write(classData);
			jarOutputStream.closeEntry();
		}
		File jarFile = new File(this.tempDir, "test.jar");
		try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile))) {
			writeStoredEntry(jarOutputStream, "app.jar", nestedJarData.toByteArray());
		}
		URL url = JarUrl.create(jarFile, "app.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			assertThatExceptionOfType(ClassNotFoundException.class).isThrownBy(() -> loader.loadClass("demo.Missing"));
			Package pkg = loader.loadClass("demo.Application").getPackage();
			assertThat(pkg.getImplementationVersion()).isEqualTo("1.2.3");
		}
	}

	private void writeStoredEntry(JarOutputStream jarOutputStream, String name, byte[] data) throws Exception {
		JarEntry entry = new JarEntry(name);
		entry.setSize(data.length);
		entry.setCompressedSize(data.length);
		CRC32 crc32 = new CRC32();
		crc32.update(data);
		entry.setCrc(crc32.getValue());
		entry.setMethod(ZipEntry.STORED);
		jarOutputStream.putNextEntry(entry);
		jarOutputStream.write(data);
		jarOutputStream.closeEntry();
	}

	@Test
	void loadClass() throws Exception {
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(APP_JAR)) {
			assertThat(loader.loadClass("demo.Application")).isNotNull().hasToString("class demo.Application");
		}
	}

	@Test
	void loadClassFromNested() throws Exception {
		File jarFile = createJarWithNestedAppJar(Files.readAllBytes(APP_JAR_FILE.toPath()));
		URL url = JarUrl.create(jarFile, "app.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			assertThat(loader.loadClass("demo.Application")).isNotNull().hasToString("class demo.Application");
		}
	}

	@Test
	void loadClassFromNestedWhenJarHasNoDirectoryEntriesDefinesPackageFromManifest() throws Exception {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.2.3");
		ByteArrayOutputStream nestedAppJarData = new ByteArrayOutputStream();
		try (JarOutputStream nestedAppJar = new JarOutputStream(nestedAppJarData, manifest)) {
			try (JarInputStream sourceAppJar = new JarInputStream(new FileInputStream(APP_JAR_FILE))) {
				ZipEntry entry = sourceAppJar.getNextEntry();
				while (entry != null) {
					if (entry.getName().endsWith(".class")) {
						nestedAppJar.putNextEntry(entry);
						sourceAppJar.transferTo(nestedAppJar);
						nestedAppJar.closeEntry();
					}
					entry = sourceAppJar.getNextEntry();
				}
			}
		}
		File jarFile = createJarWithNestedAppJar(nestedAppJarData.toByteArray());
		URL url = JarUrl.create(jarFile, "app.jar");
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(url)) {
			Package pkg = loader.loadClass("demo.Application").getPackage();
			assertThat(pkg.getImplementationVersion()).isEqualTo("1.2.3");
		}
	}

	private File createJarWithNestedAppJar(byte[] nestedAppJarData) throws Exception {
		File jarFile = new File(this.tempDir, "test.jar");
		FileOutputStream fileOutputStream = new FileOutputStream(jarFile);
		try (JarOutputStream jarOutputStream = new JarOutputStream(fileOutputStream)) {
			JarEntry nestedEntry = new JarEntry("app.jar");
			nestedEntry.setSize(nestedAppJarData.length);
			nestedEntry.setCompressedSize(nestedAppJarData.length);
			CRC32 crc32 = new CRC32();
			crc32.update(nestedAppJarData);
			nestedEntry.setCrc(crc32.getValue());
			nestedEntry.setMethod(ZipEntry.STORED);
			jarOutputStream.putNextEntry(nestedEntry);
			jarOutputStream.write(nestedAppJarData);
			jarOutputStream.closeEntry();
		}
		return jarFile;
	}

	static class TestJarUrlClassLoader extends JarUrlClassLoader {

		TestJarUrlClassLoader(URL... urls) {
			super(urls, JarUrlClassLoaderTests.class.getClassLoader());
		}

	}

}
