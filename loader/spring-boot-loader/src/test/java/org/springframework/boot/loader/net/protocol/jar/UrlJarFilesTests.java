/*
 * Copyright 2012-present the original author or authors.
 */

package org.springframework.boot.loader.net.protocol.jar;

import java.io.File;
import java.net.URL;
import java.net.URLConnection;
import java.util.function.Consumer;
import java.util.jar.JarFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import org.springframework.boot.loader.net.protocol.Handlers;
import org.springframework.boot.loader.testsupport.TestJar;
import org.springframework.boot.loader.zip.AssertFileChannelDataBlocksClosed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link UrlJarFiles}.
 *
 * @author Phillip Webb
 */
@AssertFileChannelDataBlocksClosed
class UrlJarFilesTests {

	@TempDir
	File temp;

	private UrlJarFileFactory factory = mock(UrlJarFileFactory.class);

	private final UrlJarFiles jarFiles = new UrlJarFiles(this.factory);

	private File file;

	private URL url;

	private JarFileUrlKey key;

	@BeforeAll
	static void registerHandlers() {
		Handlers.register();
	}

	@BeforeEach
	void setup() throws Exception {
		this.file = new File(this.temp, "test.jar");
		this.url = new URL("nested:" + this.file.getAbsolutePath() + "/!nested.jar");
		this.key = new JarFileUrlKey(this.url);
		TestJar.create(this.file);
	}

	@Test
	void getOrCreateWhenUsingCachingReturnsCachedWhenAvailable() throws Exception {
		given(this.factory.createJarFile(any(), any())).willCallRealMethod();
		JarFile jarFile1 = this.jarFiles.getOrCreate(this.key, this.url);
		this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile1);
		JarFile jarFile2 = this.jarFiles.getOrCreate(this.key, this.url);
		JarFile jarFile3 = this.jarFiles.getOrCreate(this.key, this.url);
		assertThat(jarFile1).isSameAs(jarFile2).isSameAs(jarFile3);
	}

	@Test
	void createJarFileDoesNotUseCacheAndWiresOnClose() throws Exception {
		given(this.factory.createJarFile(any(), any())).willCallRealMethod();
		JarFile jarFile1 = this.jarFiles.createJarFile(this.url);
		JarFile jarFile2 = this.jarFiles.createJarFile(this.url);
		try {
			assertThat(jarFile1).isNotSameAs(jarFile2);
			assertThat(this.jarFiles.getCached(this.key)).isNull();
			ArgumentCaptor<Consumer<JarFile>> onClose = ArgumentCaptor.captor();
			then(this.factory).should(times(2)).createJarFile(any(), onClose.capture());
			this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile1);
			onClose.getValue().accept(jarFile1);
			assertThat(this.jarFiles.getCached(this.key)).isNull();
		}
		finally {
			jarFile1.close();
			jarFile2.close();
		}
	}

	@Test
	void getCachedWhenNotCachedReturnsNull() {
		assertThat(this.jarFiles.getCached(this.key)).isNull();
	}

	@Test
	void getCachedWhenCachedReturnsCachedJar() throws Exception {
		given(this.factory.createJarFile(any(), any())).willCallRealMethod();
		JarFile jarFile = this.factory.createJarFile(this.url, null);
		this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile);
		assertThat(this.jarFiles.getCached(this.key)).isSameAs(jarFile);
	}

	@Test
	void cacheIfAbsentWhenNotAlreadyCachedCachesAndReturnsTrue() throws Exception {
		given(this.factory.createJarFile(any(), any())).willCallRealMethod();
		JarFile jarFile = this.factory.createJarFile(this.url, null);
		assertThat(this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile)).isTrue();
		assertThat(this.jarFiles.getCached(this.key)).isSameAs(jarFile);
	}

	@Test
	void cacheIfAbsentWhenAlreadyCachedLeavesCacheAndReturnsFalse() throws Exception {
		given(this.factory.createJarFile(any(), any())).willCallRealMethod();
		JarFile jarFile1 = this.factory.createJarFile(this.url, null);
		JarFile jarFile2 = this.factory.createJarFile(this.url, null);
		assertThat(this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile1)).isTrue();
		assertThat(this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile2)).isFalse();
		assertThat(this.jarFiles.getCached(this.key)).isSameAs(jarFile1);
	}

	@Test
	void closeIfNotCachedWhenNotCachedClosesJarFile() throws Exception {
		JarFile jarFile = mock(JarFile.class);
		this.jarFiles.closeIfNotCached(jarFile);
		then(jarFile).should().close();
	}

	@Test
	void closeIfNotCachedWhenCachedDoesNotCloseJarFile() throws Exception {
		JarFile jarFile = mock(JarFile.class);
		this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile);
		this.jarFiles.closeIfNotCached(jarFile);
		then(jarFile).should(never()).close();
	}

	@Test
	void reconnectReconnectsAndAppliesUseCaches() throws Exception {
		JarFile jarFile = mock(JarFile.class);
		this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile);
		URLConnection existingConnection = mock(URLConnection.class);
		given(existingConnection.getUseCaches()).willReturn(true);
		URLConnection connection = this.jarFiles.reconnect(jarFile, existingConnection);
		assertThat(connection).isNotSameAs(existingConnection);
		assertThat(connection.getUseCaches()).isTrue();
	}

	@Test
	void reconnectWhenExistingConnectionIsNullReconnects() throws Exception {
		JarFile jarFile = mock(JarFile.class);
		this.jarFiles.cacheIfAbsent(this.key, this.url, jarFile);
		URLConnection connection = this.jarFiles.reconnect(jarFile, null);
		assertThat(connection).isNotNull();
		assertThat(connection.getUseCaches()).isTrue();
	}

}
