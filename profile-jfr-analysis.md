# JFR analysis: `profile-old.jfr`

Recording of `smoketest.web.secure.jdbc.SampleWebSecureJdbcApplication`
(`smoke-test/spring-boot-smoke-test-web-secure-jdbc`), captured with the JDK 25
`profile` settings on 2026-10-24.

| Metric | Value |
|---|---|
| Recording | 17 s (14.2 → 31.2), app startup **3.31 s** (`Started` at 12:51:17.948) |
| Classes loaded | **9,185** (5,931 app + 3,194 bootstrap + 60 platform) |
| GC | 9 collections (7 young, 2 old), **47 ms total pause**, ~320 MB allocated before GC |
| Java samples | only **130** (`jdk.ExecutionSample`, 10 ms period) — too sparse for CPU ranking |
| Native samples | class-loading `stat`/zip-inflate while starting, then idle in `accept`/`epoll` |
| Exceptions | **688 recorded** by `jdk.JavaExceptionThrow` (844 per `ExceptionStatistics`; event throttled at 300/s) |

The hot-methods view is noise (3 samples on top). The useful signal is the exception
timeline, which maps cleanly onto startup phases. About **209 of the 688 recorded throws
(~30%) originate in Spring Boot code**; most of the rest are JVM-internal lambda
resolution or Spring Framework reflection.

## Boot-side findings (ranked)

| # | Location | Recorded throwables | Trigger |
|---|---|---|---|
| 1 | `BindConverter.convert` — `core/spring-boot/.../bind/BindConverter.java:93` | **73** (36 `ConversionFailedException` + 37 `IllegalArgumentException`) | null → primitive conversion attempts |
| 2 | `DefaultBindConstructorProvider$Constructors.isInnerClass` — `:132` | **21** `NoSuchFieldException("this$0")` | one per `@ConfigurationProperties` class |
| 3 | `StaticResourceJars.addUrlFile` — `module/spring-boot-web-server/.../StaticResourceJars.java:94` | **29** `FileNotFoundException("... Is a directory")` | every classpath dir opened as `JarFile` |
| 4 | `FilteringSpringBootCondition` / `OnClassCondition` | **66** `ClassNotFoundException` | conditional class-presence probes |
| 5 | `AutoConfigurationSorter$AutoConfigurationClass.isAvailable` — `:183` | **14** (7 `FileNotFoundException` + 7 `IllegalStateException`) | absent auto-config classes referenced by `@AutoConfigureAfter(Name)` |
| 6 | `JavaBeanBinder$Bean.isInstantiable` — `:304` | 3 `NoSuchMethodException` | types without a no-arg constructor |
| 7 | `ClearCachesApplicationListener` — `:33` | 2 `NoSuchMethodException` | `clearCache` probe on JDK 9+ loaders |
| 8 | `SpringApplicationBannerPrinter` — `:74` | 1 `IllegalArgumentException` | `"banner.txt"` probed as a URL |

### 1. The binder converts `null` to primitives — 4 throwables per property

`Binder.handleBindResult` (`Binder.java:392`) calls `converter.convert(result, target)`
even when `result == null`. `BindConverter` then asks both conversion-service delegates;
each `canConvert(null, primitive)` returns `true`, each `convert(...)` throws
`ConversionFailedException` wrapping `IllegalArgumentException`, both are swallowed, and
the method finally returns `null` anyway (`BindConverter.java:108`).

That is **2 exceptions per delegate × 2 delegates = 4 stack traces per absent primitive
property**. Timeline: 24 throws at 15.285–15.288 binding `ApplicationProperties` (its six
`boolean` fields), 49 throws at 16.602–17.293 while binding `@ConfigurationProperties`
beans. This scales with every app's `@ConfigurationProperties` primitives.

Fix — behavior-preserving because a primitive target can never receive a non-null
conversion result, and the method already returns `null` when both delegates fail:

```java
private @Nullable Object convert(@Nullable Object source, @Nullable TypeDescriptor sourceType,
        TypeDescriptor targetType) {
    if (source == null && targetType.isPrimitive()) {
        return null;   // Binder.handleBindResult(392) converts an absent value
    }
    ...
}
```

Do **not** short-circuit *all* null sources: `null → Optional` legitimately returns
`Optional.empty()` inside `GenericConversionService.convertNullSource`.

### 2. `isInnerClass` throws for every normal class

```java
private static boolean isInnerClass(Class<?> type) {
    try {
        return type.getDeclaredField("this$0").isSynthetic();
    }
    catch (NoSuchFieldException ex) {
        return false;
    }
}
```

Top-level classes — the vast majority — always throw here. Called from
`ConfigurationPropertiesBeanRegistrar.createBeanDefinition` → `deduceBindMethod`, so it is
**one exception per `@ConfigurationProperties` class per startup** (20 here), plus
`ValueObjectBinder`.

```java
private static boolean isInnerClass(Class<?> type) {
    if (type.getEnclosingClass() == null) {
        return false;   // top-level classes never have an enclosing instance
    }
    try {
        return type.getDeclaredField("this$0").isSynthetic();
    }
    catch (NoSuchFieldException ex) {
        return false;
    }
}
```

The field probe is kept for local/anonymous/static-nested classes so semantics are
unchanged.

### 3. Static resource scan opens directories as ZIP files

`addUrlFile` short-circuits `file.isDirectory() && META-INF/resources exists` only when
the directory *has* resources; otherwise it falls through to `new JarFile(file)`, which
throws `FileNotFoundException("... Is a directory")` after a native open. All 29 throws
happened within 6 ms at 16.861–16.867, from
`TomcatServletWebServerFactory$StaticResourceConfigurer`. In an exploded/IDE classpath
this is one failed zip open per classpath directory.

```java
private void addUrlFile(List<URL> urls, URL url, File file) {
    if (file.isFile() && file.getName().endsWith(".jar")) {
        if (isResourcesJar(file)) {
            urls.add(url);
        }
    }
    else if (file.isDirectory() && new File(file, "META-INF/resources").isDirectory()) {
        urls.add(url);
    }
}
```

Equivalent to today's logic (the `.jar` suffix was already required inside
`isResourcesJar(JarFile)`), but non-jars and directories are skipped before the expensive
`JarFile` open.

### 4. Class-presence probes are repeated and uncached

161 `ClassNotFoundException`s, 112 unique names. Boot-side breakdown: 37
`OnClassCondition.getMatchOutcome`, 16 import-filter (`StandardOutcomesResolver`), 6
`OnBeanCondition$Spec.resolveWhenPossible`, 3 `OnWebApplicationCondition`, 2
`OnEnabledResourceChainCondition`. The same missing name is probed repeatedly
(`jakarta.validation.Validator` 5×, `org.aspectj.weaver.Advice` 5×, SAML/OAuth
repositories 4×…). Two cheap improvements:

- `OnClassCondition.getMatchOutcome` (`OnClassCondition.java:101`) resolves every
  candidate twice on the success path — once via `ClassNameFilter.MISSING`, once via
  `ClassNameFilter.PRESENT` to build the report message. Build both lists in one pass.
- A classloader-scoped presence cache in
  `FilteringSpringBootCondition.filter`/`ClassNameFilter.isPresent` would remove the
  duplicate probes (~49 duplicate CNFEs across the run, ~30 Boot-side). Caveats: the two
  evaluation phases use different condition instances, so a per-instance cache won't span
  them; a static cache needs `ClassLoader`-keyed storage (weak keys) to avoid leaks/stale
  classpath results. Worth it mainly if this path shows up in larger apps.

The other ~95 `ClassNotFoundException`s come from Framework/Tomcat (e.g.
`DefaultHttpMessageConverters$DefaultBuilder.<clinit>` accounts for 16), not Boot.

### 5–8. Smaller items

- `AutoConfigurationSorter.isAvailable` wraps an absent-class metadata read in `Exception`
  control flow: 7 FNFE + 7 ISE in an 11 ms window, for the 7 auto-configs referenced by
  `@AutoConfigureAfter(before)Name` but not on the classpath (`Jackson2`, `Jsonb`, `Gson`,
  `Validation`, `RSocketMessaging`, `KotlinxSerializationJson`, `MockMvc` test
  auto-config). A `classLoader.getResource(className.replace('.', '/') + ".class")`
  pre-check (needs a `ClassLoader` passed in) or a negative-availability map would remove
  the stack traces; each name is currently probed once, so the ceiling is small.
- `JavaBeanBinder$Bean.isInstantiable` uses `getDeclaredConstructor()` and catches
  `NoSuchMethodException`; scanning `getDeclaredConstructors()` for a zero-arg one avoids
  the throw (3 occurrences).
- `ClearCachesApplicationListener` probes `clearCache` on every loader on JDK 9+ where it
  no longer exists (2 throws). Micro.
- `SpringApplicationBannerPrinter` resolves the default `"banner.txt"` through
  `ResourceUtils.toURL`, which throws and internally catches
  `IllegalArgumentException("URI is not absolute")` before falling back to a classpath
  resource (1 throw). Using `"classpath:banner.txt"` as `DEFAULT_BANNER_LOCATION` would
  skip the URL probe.

## What not to chase

- **148 `NoSuchMethodException`s** from Spring Framework's
  `DisposableBeanAdapter.inferDestroyMethodsIfNecessary` (`Class.getMethod("close"/"shutdown")`
  per bean, lines 443/447) — the single largest exception source, but framework-side;
  Boot's `@Bean` processing just enables inference. Worth an upstream issue, not a Boot
  patch.
- **139 `NoSuchMethodError`/`IncompatibleClassChangeError`** from
  `MethodHandleNatives.resolve`, `InvokerBytecodeGenerator.lookupPregenerated`,
  `DirectMethodHandle.make` — normal JVM lambda-form/DirectMethodHandle resolution (Graal
  JVMCI), not application behavior.
- **21 `NoSuchFieldException`s** from Framework's `CachedIntrospectionResults.isPlainAccessor`,
  22 from `ObjectToObjectConverter`, Tomcat `LocalStrings` bundle probes, JNDI
  `InitialContext` — expected framework/container probes.
- **GC/JIT are healthy**: 47 ms of pauses, no contention events, 43 of 58 deopts are normal
  `null_assert_or_unreached0`. Do not tune GC from this recording.

## Bottom line

The JFR shows a startup dominated by **class loading and JIT** (9.2k classes, ~320 MB
churn, 60% of sampled allocation is `byte[]` from class files), not by a Boot CPU hot
spot. The exception-driven control flows above are real but individually small —
together roughly 10–30 ms of a 3.3 s startup — with fixes 1–3 being low-risk and worth
doing because they scale with the number of `@ConfigurationProperties` classes and
classpath entries. If the goal is a materially faster startup, the larger lever is
class-loading reduction (AppCDS/AOT), not these micro-optimizations.
