/*
 * Copyright 2012-present the original author or authors.
 */

package org.springframework.boot.loader.net.protocol.jar;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Provides access to {@link UrlJarFile} and {@link UrlNestedJarFile} instances taking
 * care of caching concerns when necessary.
 * <p>
 * This class is thread-safe and designed to be shared by all {@link JarUrlConnection}
 * instances.
 *
 * @author Phillip Webb
 * @author Sharan Bharmshetty
 */
class UrlJarFiles {

	private final UrlJarFileFactory factory;

	private final Cache cache = new Cache();

	/**
	 * Create a new {@link UrlJarFiles} instance.
	 */
	UrlJarFiles() {
		this(new UrlJarFileFactory());
	}

	/**
	 * Create a new {@link UrlJarFiles} instance.
	 * @param factory the {@link UrlJarFileFactory} to use.
	 */
	UrlJarFiles(UrlJarFileFactory factory) {
		this.factory = factory;
	}

	JarFile getOrCreateAndCache(String jarFileSpec, boolean runtimeRef) throws IOException {
		JarFileUrlKey jarFileUrlKey = new JarFileUrlKey("jar", "", -1, jarFileSpec, runtimeRef);
		JarFile jarFile = this.cache.get(jarFileUrlKey);
		if (jarFile == null) {
			URL jarFileUrl = new URL(jarFileSpec);
			if (runtimeRef) {
				jarFileUrl = new URL(jarFileUrl, "#runtime");
			}
			jarFile = createJarFile(jarFileUrl);
			this.cache.putIfAbsent(jarFileUrlKey, jarFile, jarFileUrl);
		}
		return jarFile;
	}

	/**
	 * Create a new {@link JarFile} without interacting with the cache.
	 * @param jarFileUrl the jar file URL
	 * @return a new {@link JarFile} instance
	 * @throws IOException on I/O error
	 */
	JarFile createJarFile(URL jarFileUrl) throws IOException {
		return this.factory.createJarFile(jarFileUrl, this::onClose);
	}

	/**
	 * Get an existing {@link JarFile} instance from the cache, or create a new
	 * {@link JarFile} instance that can be
	 * {@link #cacheIfAbsent(JarFileUrlKey, URL, JarFile) cached later}.
	 * @param jarFileUrlKey the jar file URL key
	 * @param jarFileUrl the jar file URL
	 * @return a new or existing {@link JarFile} instance
	 * @throws IOException on I/O error
	 */
	JarFile getOrCreate(JarFileUrlKey jarFileUrlKey, URL jarFileUrl) throws IOException {
		JarFile cached = getCached(jarFileUrlKey);
		return (cached != null) ? cached : createJarFile(jarFileUrl);
	}

	/**
	 * Return the cached {@link JarFile} if available.
	 * @param jarFileUrlKey the jar file URL key
	 * @return the cached jar or {@code null}
	 */
	JarFile getCached(JarFileUrlKey jarFileUrlKey) {
		return this.cache.get(jarFileUrlKey);
	}

	/**
	 * Cache the given {@link JarFile} if there is no existing entry.
	 * @param jarFileUrlKey the jar file URL key
	 * @param jarFileUrl the jar file URL
	 * @param jarFile the jar file
	 * @return {@code true} if that file was added to the cache
	 */
	boolean cacheIfAbsent(JarFileUrlKey jarFileUrlKey, URL jarFileUrl, JarFile jarFile) {
		return this.cache.putIfAbsent(jarFileUrlKey, jarFile, jarFileUrl);
	}

	/**
	 * Close the given {@link JarFile} only if it is not contained in the cache.
	 * @param jarFile the jar file
	 * @throws IOException on I/O error
	 */
	void closeIfNotCached(JarFile jarFile) throws IOException {
		if (!this.cache.contains(jarFile)) {
			jarFile.close();
		}
	}

	/**
	 * Reconnect to the {@link JarFile}, returning a replacement {@link URLConnection}.
	 * @param jarFile the jar file
	 * @param existingConnection the existing connection
	 * @return a newly opened connection inhering the same {@code useCaches} value as the
	 * existing connection
	 * @throws IOException on I/O error
	 */
	URLConnection reconnect(JarFile jarFile, URLConnection existingConnection) throws IOException {
		Boolean useCaches = (existingConnection != null) ? existingConnection.getUseCaches() : null;
		URLConnection connection = openConnection(jarFile);
		if (useCaches != null && connection != null) {
			connection.setUseCaches(useCaches);
		}
		return connection;
	}

	private URLConnection openConnection(JarFile jarFile) throws IOException {
		URL url = this.cache.get(jarFile);
		return (url != null) ? url.openConnection() : null;
	}

	private void onClose(JarFile jarFile) {
		this.cache.remove(jarFile);
	}

	void clearCache() {
		this.cache.clear();
	}

	/**
	 * Internal cache.
	 */
	private static final class Cache {

		private final Map<JarFileUrlKey, JarFile> jarFileUrlToJarFile = new HashMap<>();

		private final Map<JarFile, URL> jarFileToJarFileUrl = new HashMap<>();

		/**
		 * Get a {@link JarFile} from the cache given a jar file URL.
		 * @param jarFileUrl the jar file URL
		 * @return the cached {@link JarFile} or {@code null}
		 */
		JarFile get(URL jarFileUrl) {
			return get(new JarFileUrlKey(jarFileUrl));
		}

		/**
		 * Get a {@link JarFile} from the cache given a jar file URL key.
		 * @param jarFileUrlKey the jar file URL key
		 * @return the cached {@link JarFile} or {@code null}
		 */
		JarFile get(JarFileUrlKey jarFileUrlKey) {
			synchronized (this) {
				return this.jarFileUrlToJarFile.get(jarFileUrlKey);
			}
		}

		/**
		 * Get a jar file URL from the cache given a jar file.
		 * @param jarFile the jar file
		 * @return the cached {@link URL} or {@code null}
		 */
		URL get(JarFile jarFile) {
			synchronized (this) {
				return this.jarFileToJarFileUrl.get(jarFile);
			}
		}

		/**
		 * Put the given jar file URL and jar file into the cache if they aren't already
		 * there.
		 * @param jarFileUrl the jar file URL
		 * @param jarFile the jar file
		 * @return {@code true} if the items were added to the cache or {@code false} if
		 * they were already there
		 */
		boolean putIfAbsent(URL jarFileUrl, JarFile jarFile) {
			return putIfAbsent(new JarFileUrlKey(jarFileUrl), jarFile, jarFileUrl);
		}

		/**
		 * Put the given jar file URL and jar file into the cache if they aren't already
		 * there.
		 * @param jarFileUrlKey the jar file URL key
		 * @param jarFileUrl the jar file URL
		 * @param jarFile the jar file
		 * @return {@code true} if the items were added to the cache or {@code false} if
		 * they were already there
		 */
		boolean putIfAbsent(JarFileUrlKey jarFileUrlKey, JarFile jarFile, URL jarFileUrl) {
			synchronized (this) {
				JarFile cached = this.jarFileUrlToJarFile.get(jarFileUrlKey);
				if (cached == null) {
					this.jarFileUrlToJarFile.put(jarFileUrlKey, jarFile);
					this.jarFileToJarFileUrl.put(jarFile, jarFileUrl);
					return true;
				}
				return false;
			}
		}

		/**
		 * Return if the given jar file is contained in the cache.
		 * @param jarFile the jar file to check
		 * @return {@code true} if the jar file is cached
		 */
		boolean contains(JarFile jarFile) {
			synchronized (this) {
				return this.jarFileToJarFileUrl.containsKey(jarFile);
			}
		}

		/**
		 * Remove the given jar and any related URL file from the cache.
		 * @param jarFile the jar file to remove
		 */
		void remove(JarFile jarFile) {
			synchronized (this) {
				URL removedUrl = this.jarFileToJarFileUrl.remove(jarFile);
				if (removedUrl != null) {
					this.jarFileUrlToJarFile.remove(new JarFileUrlKey(removedUrl));
				}
			}
		}

		void clear() {
			synchronized (this) {
				this.jarFileToJarFileUrl.clear();
				this.jarFileUrlToJarFile.clear();
			}
		}

	}

}
